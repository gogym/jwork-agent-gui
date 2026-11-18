/*
 * Copyright 2026 gogym
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gettyio.jwork.agent.core.chat;

import com.gettyio.jwork.agent.core.memory.MemoryWriteTool;
import com.gettyio.jwork.agent.core.subagent.BuiltinSubagents;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.tools.McpServerRegistrationListener;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 基于 AgentScope {@link HarnessAgent} 的单体智能体运行体（一个实例对应一个智能体定义）。
 * <p>由 {@link MultiAgentGateway} 按 agentCode 装配与路由，本身不感知智能体选择。</p>
 * <p>流式逐 token 映射（M2）+ HITL 工具确认回流（M3）：权限引擎按会话档位评估工具调用，
 * 对副作用工具（文件写/Shell）抛出 {@link RequireUserConfirmEvent}，运行体调用
 * {@link ToolConfirmCallback} 获取用户决策（自动通过档不弹窗但仍送评估，黑名单照样拦截），
 * 构造携带 {@link Msg#METADATA_CONFIRM_RESULTS} 的恢复消息续跑同一条流，UI 侧感知为连续事件流。</p>
 * <p>模型与工作区在首次调用时惰性装配，以便未配置模型的启动流程也能显示空窗口。</p>
 * <p><b>agentId 必须显式给出</b>：Harness 未指定 agentId 时会回退用 {@code name} 作为智能体标识，
 * 而它同时是会话上下文在磁盘上的目录键（{@code ~/.agentscope/state/<agentId>/} 与
 * {@code <workspace>/agents/<agentId>/sessions/<sessionId>.jsonl}）。用可改的显示名当键，
 * 改一次名就等于让该智能体名下所有历史会话失忆（界面气泡仍在，模型侧上下文全丢），
 * 因此这里固定为不可变的 agentCode。</p>
 *
 * @author gogym
 */
public class HarnessAgentGateway implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentGateway.class);

    /** 关闭运行体时等待单个 MCP 客户端摘除的上限 */
    private static final Duration MCP_RELEASE_TIMEOUT = Duration.ofSeconds(5);

    /** 工具入参/输出写入事件与落盘前的截断上限（字符），避免超长结果撑爆一列 */
    private static final int TOOL_TEXT_MAX = 8000;

    /** 框架内置 Shell 工具名：自定义 Shell 工具以同名注册即替换（ToolRegistry 同名覆盖）。 */
    private static final String SHELL_TOOL_NAME = "execute";

    /** 主模型调用失败后的框架级重试次数（配合备用模型 failover） */
    private static final int FRAMEWORK_MAX_RETRIES = 2;

    /** 单轮步数触顶后的自动续跑次数上限：每次续跑重新给满步数预算，超出则提示用户手动继续。 */
    private static final int MAX_ITER_CONTINUES = 3;

    /** 步数触顶后续跑指令：以 USER 消息补发，令模型接着做剩余步骤而不是重新起手。 */
    private static final String ITER_CONTINUE_PROMPT =
            "（系统提示：本轮步数上限已重置，请从当前进度继续完成上一任务，勿重复已完成的步骤。）";

    /** 本地降级压缩插入的说明消息名：与框架摘要消息同族，供后续轮次辨认来源。 */
    private static final String LOCAL_COMPACTION_MSG_NAME = "local-compaction";

    /**
     * 框架内置的压缩默认配置（只读参照其默认阈值参数：reserved / keepTokens* ）。
     * 不可变对象，作静态常量复用，避免每次装配智能体都新建一份。
     */
    private static final CompactionConfig FRAMEWORK_DEFAULT_COMPACTION = CompactionConfig.builder().build();

    /** 上下文窗口上限的 AppSetting 键：运行体自动压缩阈值与输入框占用条分母共用此单一来源。 */
    public static final String SETTING_CONTEXT_MAX_TOKENS = "agent.context.maxTokens";

    /**
     * 上下文窗口 token 上限默认值（AppSetting 键 {@link #SETTING_CONTEXT_MAX_TOKENS} 可调）；
     * 超出时框架自动压缩。显示占用条与运行体压缩阈值统一以此为准，避免两套默认值不一致。
     */
    public static final int DEFAULT_MAX_CONTEXT_TOKENS = 128_000;

    /** ReAct 单轮步数上限的 AppSetting 键：框架默认 10 步，读表→统计→产出报告的长任务必然触顶。 */
    public static final String SETTING_MAX_ITERS = "agent.maxIters";

    /** 单轮步数上限默认值（AppSetting 键 {@link #SETTING_MAX_ITERS} 可调，钳制 {@value #MIN_MAX_ITERS}–{@value #MAX_MAX_ITERS}）。 */
    public static final int DEFAULT_MAX_ITERS = 40;

    /** 步数上限允许的最小值 */
    public static final int MIN_MAX_ITERS = 5;

    /** 步数上限允许的最大值 */
    public static final int MAX_MAX_ITERS = 200;

    /** 长期记忆总开关的 AppSetting 键：{@code "false"} 即关闭记忆工具/沉淀与 MEMORY.md 注入（其余/缺省为开）。 */
    public static final String SETTING_MEMORY_ENABLED = "agent.memory.enabled";

    /** 记忆自动沉淀档位的 AppSetting 键：{@code always}（默认）/ {@code throttled}（≥ 10min）/ {@code never}。 */
    public static final String SETTING_MEMORY_FLUSH_MODE = "agent.memory.flushMode";

    /** 不可变标识（= agentCode）：上下文存储的目录键，不随显示名改动 */
    private final String agentId;
    private final String agentName;
    private final String sysPrompt;
    private final Supplier<Model> modelSupplier;
    /** 备用模型供体（可空）：主模型故障时由 Harness 框架带重试切换；惰性解析避免过早装配第二个模型 */
    private volatile Supplier<Model> fallbackSupplier;
    /** 当前模型能否收图片（可空=视为能）：read_file 读到图片时据此决定是否附多模态块 */
    private volatile BooleanSupplier visionCapableSupplier;
    private final Path workspace;
    private volatile ToolConfirmCallback confirmCallback;
    private volatile Path skillsDir;
    /**
     * 授权项目目录（绝对路径）；非空时以 projectWritable 本地文件系统装配：
     * 读先查工作区再回落项目目录，项目内绝对路径的写操作直接落在项目文件上。
     */
    private volatile Path projectDir;
    /**
     * 各会话「本轮自动通过档」快照：只在轮次在途期间有值，收尾即摘除。
     * <p>档位归轮次（{@link Turn#autoApprove}，一条流一份），这张表只是把它按会话 ID 透给
     * 只认 sessionId 的工具侧——沙箱 Shell 要据此决定走一次性容器还是本地执行。</p>
     * <p>曾经它是运行体上的一个 volatile 布尔，那是错的：运行体按「智能体+供应商+模型」复用，
     * A 会话选了「自动」、B 会话选了「询问」，后写的那次会覆盖前者，B 的副作用工具就替 A 背了书；
     * 无人值守的 {@link #ask} 更会继承上一次 UI 轮次残留的「自动」。</p>
     */
    private final Map<String, Boolean> autoApproveBySession = new ConcurrentHashMap<>();
    /**
     * 技能名白名单（由智能体定义的 skills_json 解析而来）。
     * {@code null} 表示不干预（沿用仓库内全部技能）；空集表示该智能体没有技能。
     */
    private volatile Set<String> skillAllowlist;
    /** MCP 服务与工具目录配置：非空即覆盖 workspace/tools.json，由本智能体自己声明 */
    private volatile ToolsConfig toolsConfig;
    /** MCP 逐个服务的注册结果回报（回写连接状态） */
    private volatile McpServerRegistrationListener mcpRegistrationListener;
    /** 运行期追加的自定义工具宿主对象（含 @Tool 注解方法），在 Agent 装配后注册进其 Toolkit */
    private final List<Object> toolTargets = new CopyOnWriteArrayList<>();
    /** 上下文窗口 token 上限（装配时下发给 Harness）；由装配方按 AppSetting 覆盖，默认 {@link #DEFAULT_MAX_CONTEXT_TOKENS} */
    private volatile int maxContextTokens = DEFAULT_MAX_CONTEXT_TOKENS;
    /** ReAct 单轮步数上限（装配时下发给 Harness）；由装配方按 AppSetting 覆盖，默认 {@link #DEFAULT_MAX_ITERS} */
    private volatile int maxIters = DEFAULT_MAX_ITERS;
    /** 长期记忆配置（{@code null} 沿用 Harness 内置默认）：由装配方按应用设置下发 flush 节流等。 */
    private volatile MemoryConfig memoryConfig;
    /** 会话状态存储覆盖（可空=框架默认落盘存储）：供嵌入式/单测场景把状态留在内存或自有介质。 */
    private volatile AgentStateStore stateStoreOverride;
    /** 长期记忆开关（默认开）：关闭时不下发记忆工具与记忆钩子，MEMORY.md 也不再注入工作区上下文。 */
    private volatile boolean memoryEnabled = true;
    /** 计划模式开关（默认开）：开启后注册 plan_enter/plan_write/plan_exit 工具与只读中间件，供规划轮使用。 */
    private volatile boolean planModeEnabled = true;
    /**
     * 可委派的子智能体声明（由装配方按其它启用智能体定义下发）。
     * 非空时装配走静态子智能体中间件，精确按库内定义注册委派对象；空/null 即不启用子智能体编排。
     */
    private volatile List<SubagentDeclaration> subagentDeclarations;

    /** 自定义 Shell 工具工厂（可空）：装配后替换框架内置 execute 工具（如 Docker 沙箱装饰） */
    private volatile ShellToolFactory shellToolFactory;

    /**
     * 产物交付根目录（可空=不暴露 {@code deliver_artifact}）：模型产出的最终文件按会话落到
     * {@code <此>/<sessionId>/<文件名>}，用户在聊天里的产物卡与工作区右栏都能直接打开。
     */
    private volatile Path artifactSessionsDir;

    private volatile HarnessAgent agent;

    /**
     * 每会话在途生成计数（流式/非流式共用）：手动压缩前探测 >0 即拒绝，
     * 避免压缩与在途轮次并发改写同一份会话上下文。只在订阅/终止钩子里增减，线程安全。
     */
    private final Map<String, AtomicInteger> inflight = new ConcurrentHashMap<>();

    /**
     * 轮次串行队列：同一运行体（智能体+供应商+模型）上的轮次首尾相接，见 {@link TurnQueue}。
     * <p>它的存在也是 {@link #autoApproveBySession} 能按会话取值的前提——只要有一轮在途，
     * 就只有一轮在途。</p>
     */
    private final TurnQueue turnQueue;

    public HarnessAgentGateway(String agentId, String agentName, String sysPrompt,
                               Supplier<Model> modelSupplier, Path workspace) {
        this.agentId = agentId;
        this.agentName = agentName;
        this.sysPrompt = sysPrompt;
        this.modelSupplier = modelSupplier;
        this.workspace = workspace;
        this.turnQueue = new TurnQueue(agentId);
    }

    /** 运行期注入/更新工具确认回调（可空表示无 HITL 通道，敏感工具将被拒绝） */
    public void setConfirmCallback(ToolConfirmCallback confirmCallback) {
        this.confirmCallback = confirmCallback;
    }

    /** 不可变标识（= agentCode），供宿主注册每智能体专属工具时使用（如 ScheduleTools 绑任务归属） */
    public String getAgentId() {
        return agentId;
    }

    /** 智能体工作区（装配前即可用）：供宿主注册绑定落点的专属工具（如分类记忆写入） */
    public Path getWorkspace() {
        return workspace;
    }

    /** 注入备用模型供体：装配时解析一次，有备用则启用框架级重试 + failover；null 表示不启用 */
    public void setFallbackSupplier(Supplier<Model> fallbackSupplier) {
        this.fallbackSupplier = fallbackSupplier;
    }

    /**
     * 注入「当前模型能否收图片」供体：read_file 覆盖工具在每次调用时读它。
     * <p>给供体而不是布尔，因为生效模型要到请求里才定得下来（消息级覆盖 → 智能体自带 → 应用默认）；
     * 方向判错会毁掉整轮请求——非视觉模型收到 image 块，provider 直接拒。</p>
     */
    public void setVisionCapableSupplier(BooleanSupplier visionCapableSupplier) {
        this.visionCapableSupplier = visionCapableSupplier;
    }

    /** 上下文窗口 token 上限（正数生效，装配前下发）；非法值忽略、保留默认。 */
    public void setMaxContextTokens(int maxContextTokens) {
        if (maxContextTokens > 0) {
            this.maxContextTokens = maxContextTokens;
        }
    }

    /**
     * ReAct 单轮步数上限（装配前下发）：钳制到 {@value #MIN_MAX_ITERS}–{@value #MAX_MAX_ITERS}；
     * 非正数表示不干预，保留默认。上限必须钳得住——否则一处误填就能让模型无限跑下去烧 token。
     */
    public void setMaxIters(int maxIters) {
        if (maxIters > 0) {
            this.maxIters = Math.min(MAX_MAX_ITERS, Math.max(MIN_MAX_ITERS, maxIters));
        }
    }

    /** 当前生效的单轮步数上限（供设置页回显与用量提示）。 */
    public int getMaxIters() {
        return maxIters;
    }

    /** 指定技能目录（workspace/skills），Harness 将以 FileSystemSkillRepository 加载其中的 SKILL.md */
    public void setSkillsDir(Path skillsDir) {
        this.skillsDir = skillsDir;
    }

    /**
     * 下发长期记忆配置（flush 触发策略、记忆模型等）。{@code null} 表示沿用 Harness 默认（每次调用后 flush）。
     * 需在智能体首次装配（或失效重建）前调用才会生效。
     */
    public void setMemoryConfig(MemoryConfig memoryConfig) {
        this.memoryConfig = memoryConfig;
    }

    /** 注入会话状态存储覆盖；须在首次装配（或失效重建）前设置，null 表示沿用框架默认落盘存储。 */
    public void setStateStoreOverride(AgentStateStore stateStoreOverride) {
        this.stateStoreOverride = stateStoreOverride;
    }

    /**
     * 长期记忆总开关；关闭后不再注册 memory_save/search/get/session_search 工具与沉淀钩子，
     * 且 MEMORY.md 不再注入工作区上下文（框架 includeMemoryContext 同关；AGENTS.md 等注入不受影响）。
     */
    public void setMemoryEnabled(boolean memoryEnabled) {
        this.memoryEnabled = memoryEnabled;
    }

    /**
     * 计划模式总开关：开启后装配时调 {@code enablePlanMode}（注册 plan_* 工具与只读中间件）。
     * 需在智能体首次装配（或失效重建）前调用才生效；规划轮依赖它存在。
     */
    public void setPlanModeEnabled(boolean planModeEnabled) {
        this.planModeEnabled = planModeEnabled;
    }

    /**
     * 下发数据库里定义的其它可委派子智能体（{@code null}/空即「用户没有自定义智能体」）。
     * 装配时总会先并入内置只读 {@code explore}（见 {@link BuiltinSubagents}），因此委派工具
     * {@code agent_spawn} 恒定在场；同名以用户定义为准。
     * 需在智能体首次装配（或失效重建）前调用才生效。
     */
    public void setSubagentDeclarations(List<SubagentDeclaration> subagentDeclarations) {
        this.subagentDeclarations = subagentDeclarations;
    }

    /** 授权项目目录；必须在首次装配前设置（null 表示不开放项目目录读写，写操作全部落在工作区） */
    public void setProjectDir(Path projectDir) {
        this.projectDir = projectDir;
    }

    /**
     * 技能白名单：仅名单内的技能对模型可见（按 SKILL.md 的 name 匹配）。
     * 传入空集即关掉该智能体的全部技能；传 null 表示不做过滤。
     */
    public void setSkillAllowlist(Set<String> skillAllowlist) {
        this.skillAllowlist = skillAllowlist == null ? null
                : Set.copyOf(skillAllowlist);
    }

    /**
     * MCP 与工具目录配置；非空会覆盖 workspace/tools.json，并在装配时真正建立 MCP 连接。
     *
     * @param listener 每个 MCP 服务的注册结果回报，可为 null
     */
    public void setToolsConfig(ToolsConfig toolsConfig, McpServerRegistrationListener listener) {
        this.toolsConfig = toolsConfig;
        this.mcpRegistrationListener = listener;
    }

    /**
     * 注册自定义工具宿主对象（其方法需标注 {@code @io.agentscope.core.tool.Tool}）。
     * 若 Agent 已装配则立即注册，否则在首次装配时补注册，避免过早触发模型初始化。
     */
    public void registerToolTarget(Object target) {
        if (target == null) {
            return;
        }
        toolTargets.add(target);
        HarnessAgent local = this.agent;
        if (local != null && memoryToolAllowed(target)) {
            local.getToolkit().registerTool(target);
        }
    }

    /** 记忆类自定义工具（如分类记忆写入）与框架记忆工具同门禁：记忆关闭时不注册。 */
    private boolean memoryToolAllowed(Object target) {
        return this.memoryEnabled || !(target instanceof MemoryWriteTool);
    }

    /**
     * 自定义 Shell 工具工厂：智能体装配完成后以同名 {@code execute} 工具替换框架内置实现
     * （{@link io.agentscope.harness.agent.tool.ShellExecuteTool}）；返回 {@code null} 表示不替换。
     */
    @FunctionalInterface
    public interface ShellToolFactory {
        /**
         * @param localFilesystem 框架本地档文件系统（与内置 Shell 工具同源，可委托其本地执行）
         * @param workspace       智能体工作区
         * @param project         授权项目目录（可空）
         * @param autoApprove     「该会话本轮是否自动通过档」查询，入参为 sessionId；
         *                        会话不在途时返回 false。按会话查而不是拿一个布尔，是因为运行体跨会话复用
         * @return 替代工具宿主对象（含 {@code @Tool} 方法）；{@code null} 表示保持内置实现
         */
        Object create(AbstractSandboxFilesystem localFilesystem, Path workspace, Path project,
                      Predicate<String> autoApprove);
    }

    /** 注入自定义 Shell 工具工厂；须在首次装配（或失效重建）前设置。 */
    public void setShellToolFactory(ShellToolFactory shellToolFactory) {
        this.shellToolFactory = shellToolFactory;
    }

    /**
     * 注入产物交付根目录（须在首次装配或失效重建前设置）：非空即向模型暴露 {@code deliver_artifact}，
     * 交付文件落 {@code <sessionsDir>/<sessionId>/}；传 null 表示不提供交付（旧行为）。
     */
    public void setArtifactSessionsDir(Path sessionsDir) {
        this.artifactSessionsDir = sessionsDir;
    }

    /** 装配（或取已装配的）运行体；包内可见供同包测试 seeding 会话状态。 */
    HarnessAgent agent() {
        HarnessAgent local = this.agent;
        if (local == null) {
            synchronized (this) {
                local = this.agent;
                if (local == null) {
                    log.info("装配 HarnessAgent: id={}, name={}, workspace={}", agentId, agentName, workspace);
                    Model harnessModel = modelSupplier.get();
                    CompactionConfig autoCompaction = autoCompactionConfig(harnessModel);
                    log.info("自动压缩阈值: agent={}, window={}, triggerTokens={}, keepTokens={}", agentId,
                            effectiveContextWindow(harnessModel),
                            autoCompaction.getTriggerTokens(), autoCompaction.getKeepTokens());
                    HarnessAgent.Builder builder = HarnessAgent.builder()
                            .name(agentName)
                            // 上下文目录键固定用编码：显示名可以改，改了也不影响既有会话的记忆
                            .agentId(agentId)
                            .sysPrompt(sysPrompt)
                            .model(harnessModel)
                            .workspace(workspace)
                            // 待办清单：开启后框架向模型提供内置 todo 工具（其调用会以工具卡片形式回显）
                            .enableTaskList(true)
                            .maxContextTokens(this.maxContextTokens)
                            // 单轮步数上限：框架默认 10 步，多步数据分析（探查→计算→产出文件）会在半路被强制收尾，
                            // 故按应用设置下发；触顶后本运行体再自动续跑若干轮（见 stream 的续跑链）
                            .maxIters(this.maxIters)
                            // 自动压缩：框架默认按「模型自报窗口 - 缓冲」动态推算阈值（模型未上报窗口时兜底 160k），
                            // 与本应用占用条的分母（设置页的上下文上限）并非同一口径，故显式下发按上限推算的配置
                            .compaction(autoCompaction)
                            // 初始权限上下文：真实档位由每次对话前的 setPermissionMode 设定；
                            // 副作用工具（文件写/Shell）在询问档下触发确认，交由 HITL 链路处理
                            .permissionContext(PermissionContextState.builder()
                                    .mode(PermissionMode.DEFAULT)
                                    .build());
                    Path project = this.projectDir;
                    AgentStateStore storeOverride = this.stateStoreOverride;
                    if (storeOverride != null) {
                        builder = builder.stateStore(storeOverride);
                    }
                    if (project != null) {
                        // 用户显式授权的项目目录：开启 projectWritable，写操作可落在项目文件上；
                        // ROOTED 路径策略仍以 项目+工作区 为硬边界，越界路径被文件系统拒绝
                        builder = builder.filesystem(new LocalFilesystemSpec()
                                .project(project)
                                .projectWritable(true));
                    }
                    Path skills = this.skillsDir;
                    Set<String> allowedSkills = this.skillAllowlist;
                    if (allowedSkills != null && allowedSkills.isEmpty()) {
                        // 用户将技能清成了严格空白名单：连技能仓库都不必扫描
                        // （null 是「从未声明」，走下面的仓库+框架默认 SkillFilter.all()）
                        builder = builder.skillFilter(SkillFilter.none());
                    } else {
                        if (skills != null) {
                            builder = builder.skillRepository(new FileSystemSkillRepository(skills, false));
                        }
                        if (allowedSkills != null) {
                            builder = builder.skillFilter(SkillFilter.only(allowedSkills.toArray(new String[0])));
                        }
                    }
                    ToolsConfig tools = this.toolsConfig;
                    if (tools != null) {
                        // 智能体自己声明的 MCP 服务覆盖 workspace/tools.json
                        builder = builder.toolsConfig(tools);
                        McpServerRegistrationListener listener = this.mcpRegistrationListener;
                        if (listener != null) {
                            builder = builder.mcpServerRegistrationListener(listener);
                        }
                    }
                    Supplier<Model> fallbackSrc = this.fallbackSupplier;
                    if (fallbackSrc != null) {
                        try {
                            Model fallback = fallbackSrc.get();
                            if (fallback != null) {
                                // 主模型调用失败先就地重试，重试耗尽切备用模型
                                builder = builder.maxRetries(FRAMEWORK_MAX_RETRIES).fallbackModel(fallback);
                            }
                        } catch (RuntimeException e) {
                            log.warn("备用模型解析失败，仅用主模型继续: agent={}", agentId, e);
                        }
                    }
                    if (this.planModeEnabled) {
                        // 注册 plan_enter/plan_write/plan_exit 工具与 PlanModeMiddleware（计划态只读）；
                        // allowShellInPlanMode 默认不开（设计阶段不跑副作用命令）
                        builder = builder.enablePlanMode();
                    }
                    MemoryConfig memCfg = this.memoryConfig;
                    if (memCfg != null) {
                        // 记忆沉淀策略（flush 节流 / 记忆模型）按应用设置下发；null 即沿用框架默认
                        builder = builder.memory(memCfg);
                    }
                    if (!this.memoryEnabled) {
                        // 关记忆工具与沉淀钩子；框架据此同步关闭 <memory_context> 注入（MEMORY.md 不再进上下文），
                        // 其余工作区上下文（AGENTS.md / 知识索引）照常
                        builder = builder.disableMemoryTools().disableMemoryHooks();
                    }
                    // 内置只读探索子代理始终在场，库内定义按名去重后一并静态注册（同名以用户定义为准）。
                    // 走静态而非动态中间件：动态那档每步重扫工作区 subagents/，与本应用「声明来自数据库、
                    // 装配期一次性下发」的形态不匹配；磁盘上的 subagents/*.md 在静态档仍会被扫进来一次。
                    builder = builder.subagents(BuiltinSubagents.withExplore(this.subagentDeclarations))
                            .disableDynamicSubagents();
                    Path deliverRoot = this.artifactSessionsDir;
                    if (deliverRoot != null) {
                        // 交付闭环：模型产出最终文件后调 deliver_artifact，落到本机可打开的会话目录，
                        // 工具结果里的绝对路径同时供 UI 产物卡与工作区右栏反查
                        builder = builder.artifactDeliveryTarget(new SessionArtifactDelivery(deliverRoot));
                    }
                    local = builder.build();
                    ShellToolFactory shellFactory = this.shellToolFactory;
                    if (shellFactory != null && local.getToolkit().getTool(SHELL_TOOL_NAME) != null) {
                        // 内置 execute 在 build 内注册，tools.json 过滤也已在 build 内生效：
                        // 被过滤时 getTool 为空，此时保持不动以尊重配置
                        AbstractFilesystem fs = local.getWorkspaceManager().getFilesystem();
                        if (fs instanceof AbstractSandboxFilesystem sandboxFs) {
                            try {
                                Object replacement = shellFactory.create(sandboxFs, workspace,
                                        this.projectDir, this::isAutoApproveTurn);
                                if (replacement != null) {
                                    local.getToolkit().registerTool(replacement);
                                    log.info("已替换内置 Shell 工具: agent={}", agentId);
                                }
                            } catch (RuntimeException e) {
                                log.warn("Shell 工具替换失败，沿用框架内置实现: agent={}", agentId, e);
                            }
                        }
                    }
                    if (local.getToolkit().getTool(OverwritableWriteFileTool.NAME) != null) {
                        // 同名注册即覆盖框架内置 write_file：补 overwrite 参数，免得模型为绕开「文件已存在」
                        // 一路换名，工作区堆满 v2/final 中间版本、交付出去的还可能是旧那份
                        AbstractFilesystem fs = local.getWorkspaceManager().getFilesystem();
                        if (fs != null) {
                            local.getToolkit().registerTool(new OverwritableWriteFileTool(fs));
                        }
                    }
                    if (local.getToolkit().getTool(LineNumberedReadFileTool.NAME) != null) {
                        // 同名注册即覆盖框架内置 read_file：它的描述承诺行号但实现不给，二进制还整份
                        // Base64 回灌且不限长——读一张图就烧掉几万字符乱码。这里补行号、限行限字符，
                        // 文档走抽取、表格转 inspect_file、图片按模型能力附块，其余二进制只报不灌
                        AbstractFilesystem fs = local.getWorkspaceManager().getFilesystem();
                        if (fs != null) {
                            local.getToolkit().registerTool(new LineNumberedReadFileTool(
                                    fs, workspace, this.visionCapableSupplier));
                        }
                    }
                    AbstractFilesystem fsForSearch = local.getWorkspaceManager().getFilesystem();
                    if (fsForSearch != null) {
                        // grep_files：框架按字面量搜（Pattern.quote），get.*Name 一类写法一律搜不到，
                        // 模型搜不到就把整份文件读进上下文肉眼找；这里换成 JDK 正则 + 分页与三档输出
                        if (local.getToolkit().getTool(RegexGrepTool.NAME) != null) {
                            local.getToolkit().registerTool(new RegexGrepTool(fsForSearch));
                        }
                        // glob_files：框架按遍历序截断，历史文件占满前 200 条、刚生成的那份落在截断外
                        if (local.getToolkit().getTool(MtimeOrderedGlobTool.NAME) != null) {
                            local.getToolkit().registerTool(new MtimeOrderedGlobTool(fsForSearch));
                        }
                    }
                    for (Object target : toolTargets) {
                        // 记忆关闭：分类记忆工具与框架记忆工具（memory_save 等）同门禁，一并跳过
                        if (!memoryToolAllowed(target)) {
                            continue;
                        }
                        local.getToolkit().registerTool(target);
                    }
                    this.agent = local;
                }
            }
        }
        return local;
    }

    /** 非流式单轮问答（自主会话，无 UI 确认通道）：以只读档运行，副作用工具被明确拒绝 */
    public Mono<String> ask(String sessionId, String userText) {
        RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
        Msg input = Msg.builder().role(MsgRole.USER).textContent(userText).build();
        Mono<String> turn = Mono.fromCallable(() -> {
            // 自主会话（无 UI）：只读档——读类工具可用，写/Shell 直接 DENY，不弹确认也不静默放行
            HarnessAgent a = agent();
            a.setPermissionMode(ctx, PermissionMode.EXPLORE);
            return a.call(input, ctx).block();
        }).map(Msg::getTextContent).subscribeOn(Schedulers.boundedElastic())
                .doOnSubscribe(sig -> retainInflight(sessionId))
                .doFinally(sig -> releaseInflight(sessionId));
        // 它跑的是同一个 HarnessAgent 实例，与 UI 轮次一样要排队；不登记档位即天然落回询问档
        return turnQueue.serialize(turn);
    }

    /**
     * 中断该会话在途的生成：按 sessionId 构造上下文调 Harness {@code interrupt}，打断模型/工具循环。
     * <p>运行体尚未装配时静默（没有在途生成可打断）；事件流的取消与半截落盘由订阅方（UI）负责。</p>
     */
    public void stop(String sessionId) {
        HarnessAgent local = this.agent;
        if (local == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            local.interrupt(RuntimeContext.builder().sessionId(sessionId).build());
        } catch (RuntimeException e) {
            log.warn("中断生成失败: session={}", sessionId, e);
        }
    }

    /**
     * 手动压缩指定会话的模型上下文：与框架自动压缩同一条链路（记忆沉淀 → 历史转存 JSONL →
     * LLM 摘要 → [摘要] + 尾部保留），结果直接替换并持久化会话状态，下一轮起生效。
     * <p>生成中的会话拒绝执行（避免与在途轮次竞争同一份上下文）；会话过短或上下文为空时
     * 返回 {@code compacted=false} 的结果，不做任何修改。</p>
     * <p>摘要链路失败（额度耗尽/鉴权失败/网络断等重试后仍不可用）时自动降级为本地归档压缩：
     * 不耗模型、占用立即下降，代价是旧历史无语义摘要（见 {@link #localArchiveCompaction}）。</p>
     */
    public Mono<ContextCompactionResult> compactContext(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Mono.empty();
        }
        return Mono.defer(() -> {
            if (inflightCount(sessionId) > 0) {
                return Mono.error(new IllegalStateException("会话正在生成中，请先停止后再压缩"));
            }
            HarnessAgent a = agent();
            // 先清本变体的状态缓存再加载：同一会话可能由另一模型变体跑过，缓存可能已落后
            a.getDelegate().clearStateCache(null, sessionId);
            AgentState state = a.getDelegate().getAgentState(null, sessionId);
            List<Msg> context = state.contextMutable();
            if (context.isEmpty()) {
                return Mono.just(ContextCompactionResult.skipped());
            }
            Model model = a.getModel();
            MemoryConfig memCfg = this.memoryConfig;
            String flushPrompt = memCfg != null ? memCfg.flushPrompt() : null;
            MemoryFlushManager flushManager = flushPrompt != null
                    ? new MemoryFlushManager(a.getWorkspaceManager(), model, flushPrompt)
                    : new MemoryFlushManager(a.getWorkspaceManager(), model);
            ConversationCompactor compactor = new ConversationCompactor(model, flushManager);
            RuntimeContext rc = RuntimeContext.builder().sessionId(sessionId).build();
            // 入参用快照：压缩器会先就地裁剪参数/工具结果，避免中途异常破坏活动上下文
            List<Msg> snapshot = new ArrayList<>(context);
            return compactor.compactIfNeeded(rc, snapshot, forceCompactionConfig(model), agentName, sessionId)
                    .map(opt -> {
                        if (opt.isEmpty()) {
                            // 压缩器把摘要失败吞成 Optional.empty（jar 版 summarizePrefix 的
                            // onErrorResume 返回空 Optional，错误不外抛），不能只靠 onErrorResume
                            // 兜底：快照超出尾部保留预算却没压成，说明摘要链路失败，主动降级本地压缩
                            if (TokenCounterUtil.calculateToken(snapshot) > dynamicKeepTokens(usableWindow(model))) {
                                log.warn("摘要压缩未生效（模型调用失败被压缩器吞掉），降级本地归档压缩: agent={}, session={}",
                                        agentId, sessionId);
                                return localArchiveCompaction(a, model, sessionId, snapshot, context);
                            }
                            return ContextCompactionResult.skipped();
                        }
                        List<Msg> compacted = opt.get();
                        context.clear();
                        context.addAll(compacted);
                        a.getDelegate().saveAgentState(null, sessionId);
                        log.info("手动压缩完成: agent={}, session={}, {} 条 -> {} 条",
                                agentId, sessionId, snapshot.size(), compacted.size());
                        return new ContextCompactionResult(true, TokenCounterUtil.calculateToken(compacted), false);
                    })
                    // 摘要调用重试耗尽仍失败：降级本地归档压缩，额度耗尽时压缩不再完全不可用
                    .onErrorResume(e -> {
                        log.warn("摘要压缩失败，降级本地归档压缩: agent={}, session={}", agentId, sessionId, e);
                        return Mono.just(localArchiveCompaction(a, model, sessionId, snapshot, context));
                    });
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 本地降级压缩：整段历史归档到工作区 JSONL，尾部按与摘要压缩同口径的 token 预算保留
     * （{@link #dynamicKeepTokens}），头部插一条说明消息告知归档位置。全程不耗模型。
     * <p>归档写失败即中止压缩并报错——宁可压缩失败也不能静默丢历史；归档成功后旧历史
     * 虽无语义摘要，智能体仍可按说明消息里的路径 read_file 取回细节。</p>
     */
    private ContextCompactionResult localArchiveCompaction(HarnessAgent a, Model model, String sessionId,
                                                           List<Msg> snapshot, List<Msg> context) {
        Path dir = workspace.resolve("sessions").resolve(sessionId);
        Path archive = dir.resolve("context-archive-" + System.currentTimeMillis() + ".jsonl");
        try {
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder();
            for (Msg m : snapshot) {
                sb.append("{\"role\":\"").append(jsonEscape(m.getRole() == null ? "" : m.getRole().name()))
                        .append("\",\"name\":\"").append(jsonEscape(m.getName() == null ? "" : m.getName()))
                        .append("\",\"text\":\"").append(jsonEscape(m.getTextContent() == null ? "" : m.getTextContent()))
                        .append("\"}\n");
            }
            Files.writeString(archive, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("本地压缩失败：历史归档写入不了 " + archive, e);
        }
        int keepBudget = dynamicKeepTokens(usableWindow(model));
        LinkedList<Msg> tail = new LinkedList<>();
        int kept = 0;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Msg m = snapshot.get(i);
            int cost = TokenCounterUtil.calculateToken(List.of(m));
            // 尾部至少留一条：单条超预算时不能把上下文压成只剩说明消息
            if (!tail.isEmpty() && kept + cost > keepBudget) {
                break;
            }
            tail.addFirst(m);
            kept += cost;
        }
        String notice = "[本地压缩] 模型不可用，早期历史未生成摘要：" + (snapshot.size() - tail.size())
                + " 条消息已归档到 " + archive.toAbsolutePath()
                + "；需要细节时请用 read_file 读取该文件。以下为保留的近期对话。";
        Msg head = Msg.builder().role(MsgRole.USER).name(LOCAL_COMPACTION_MSG_NAME).textContent(notice).build();
        context.clear();
        context.add(head);
        context.addAll(tail);
        a.getDelegate().saveAgentState(null, sessionId);
        log.info("本地降级压缩完成: agent={}, session={}, {} 条 -> {} 条, archive={}",
                agentId, sessionId, snapshot.size(), context.size(), archive);
        return new ContextCompactionResult(true, TokenCounterUtil.calculateToken(context), true);
    }

    /** JSONL 归档的字符串转义：仅处理 JSON 必需的控制字符与引号/反斜杠。 */
    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * 手动压缩的强制配置：{@code triggerMessages(1)} 保证非空上下文必然触发；尾部保留量与自动压缩
     * 同口径（见 {@link #dynamicKeepTokens(int)}），差别只在不看消息条数、也不算是否超过阈值。
     */
    private CompactionConfig forceCompactionConfig(Model model) {
        return CompactionConfig.builder()
                .triggerMessages(1)
                .keepTokens(dynamicKeepTokens(usableWindow(model)))
                .build();
    }

    /**
     * 自动压缩配置：把框架的动态阈值（{@code triggerTokens=0} → 按模型自报窗口推算，推算不出时兜底
     * 160k）改为按本运行体的上下文上限推算，与输入框占用条同一分母。
     * <p>不改的话，模型自报窗口为 1M（或干脆没上报）时阈值远高于用户看到并超过的上限，
     * 占用条已经爆表也不会压缩。</p>
     * <p>消息条数阈值沿用框架默认（50 条）：条数与 token 两个条件任一超出即压缩。</p>
     */
    private CompactionConfig autoCompactionConfig(Model model) {
        int usable = usableWindow(model);
        return FRAMEWORK_DEFAULT_COMPACTION.withEffective(usable, dynamicKeepTokens(usable));
    }

    /**
     * 生效的上下文窗口：取应用设置上限与模型自报窗口的较小值；模型未上报窗口时只用上限。
     */
    private int effectiveContextWindow(Model model) {
        int cap = this.maxContextTokens;
        int window = model == null ? 0 : model.getContextWindowSize();
        return window > 0 ? Math.min(window, cap) : cap;
    }

    /**
     * 扣掉压缩自身开销后的可用窗口：压缩那轮要把整段前缀送给模型做摘要，必须留出缓冲；
     * 缓冲取框架默认（20k）与窗口四分之一中的较小值，小窗口才不会被挤到刚起步就压缩。
     */
    private int usableWindow(Model model) {
        int window = effectiveContextWindow(model);
        int reserved = Math.min(FRAMEWORK_DEFAULT_COMPACTION.getReserved(), Math.max(1, window / 4));
        return Math.max(1, window - reserved);
    }

    /** 尾部保留 token：复刻框架动态公式 {@code min(最大, max(最小, 可用窗口×比例))}。 */
    private static int dynamicKeepTokens(int usableWindow) {
        return Math.min(FRAMEWORK_DEFAULT_COMPACTION.getKeepTokensMax(),
                Math.max(FRAMEWORK_DEFAULT_COMPACTION.getKeepTokensMin(),
                        (int) (usableWindow * FRAMEWORK_DEFAULT_COMPACTION.getKeepTokensRatio())));
    }

    /** 在途生成计数 +1（订阅时调用，请与 {@link #releaseInflight} 配对）。 */
    private void retainInflight(String sessionId) {
        inflight.computeIfAbsent(sessionId, k -> new AtomicInteger()).incrementAndGet();
    }

    /** 在途生成计数 -1，归零时摘除键（终止时调用；重复调用减到负数前的键已被摘除，无副作用）。 */
    private void releaseInflight(String sessionId) {
        inflight.computeIfPresent(sessionId, (k, v) -> v.decrementAndGet() <= 0 ? null : v);
    }

    private int inflightCount(String sessionId) {
        AtomicInteger counter = inflight.get(sessionId);
        return counter == null ? 0 : counter.get();
    }

    /** 流式对话：按 {@link ChatRequest} 组装多模态输入、设定权限模式后产出归一化事件流 */
    public Flux<StreamEvent> stream(ChatRequest request) {
        String sessionId = request.getSessionId();
        RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
        Msg input = buildUserMsg(request.getText(), request.getImages());
        PermissionMode mode = request.getPermissionMode() == null
                ? PermissionMode.ACCEPT_EDITS : request.getPermissionMode();
        // 规划轮：仅当请求标 planMode 且本智能体已装配计划模式时，进入/退出只读设计态
        boolean planTurn = request.isPlanMode() && this.planModeEnabled;
        // 整条流（含 HITL 续跑、步数续跑的每一段）共享一份轮次状态；
        // 自动通过档随之绑定到这一轮：它只对本条流生效，不写回运行体，别的会话抄不走
        Turn turn = new Turn(ctx, sessionId, request.isAutoApprove());
        Flux<StreamEvent> body = Flux.defer(() -> {
                    autoApproveBySession.put(sessionId, turn.autoApprove);
                    HarnessAgent a = agent();
                    a.setPermissionMode(ctx, mode);
                    if (planTurn) {
                        a.enterPlanMode(ctx);
                    }
                    return runEvents(input, turn);
                })
                // 步数触顶续跑接在主轮之后：主轮事件流（含框架补发的收尾摘要）必须完全收口，
                // 才能再起下一轮——否则会与之交错，且智能体不允许上一回复未结束时重入
                .concatWith(Flux.defer(() -> continueAfterIterLimit(turn)))
                .doFinally(sig -> {
                    if (planTurn) {
                        // 本轮结束时回到 BUILD（幂等）；若模型已自行 plan_exit 则无影响
                        try {
                            agent().exitPlanMode(ctx);
                        } catch (RuntimeException e) {
                            log.warn("退出计划态失败: session={}", sessionId, e);
                        }
                    }
                })
                .concatWith(Flux.defer(() -> {
                    TokenUsage total = turn.usageAcc.get();
                    return total == null || total.isEmpty()
                            ? Flux.empty() : Flux.just(StreamEvent.usage(sessionId, total));
                }))
                // 在途计数覆盖整条链（含用量收尾段）：生成结束前拒绝手动压缩，避免并发改写上下文
                .doOnSubscribe(sig -> retainInflight(sessionId))
                .doFinally(sig -> {
                    releaseInflight(sessionId);
                    autoApproveBySession.remove(sessionId);
                });
        return turnQueue.serialize(body);
    }

    /** 某会话本轮是否处于自动通过档；会话不在途（自主轮、后台任务、已收尾）时为 false。 */
    boolean isAutoApproveTurn(String sessionId) {
        return sessionId != null && Boolean.TRUE.equals(autoApproveBySession.get(sessionId));
    }

    /**
     * 步数触顶后的自动续跑：主轮被 {@code maxIters} 截断时补发一条续跑指令再跑一轮，
     * 直到任务自然收尾或续跑次数用尽。
     * <p>用尽后只提示、由用户手动继续——不设上限地自我续跑等于无人踩油门，一旦模型绕圈就是纯烧 token。</p>
     */
    private Flux<StreamEvent> continueAfterIterLimit(Turn turn) {
        return Flux.defer(() -> {
            if (!turn.iterLimitHit.getAndSet(false)) {
                return Flux.empty();
            }
            int nth = turn.iterContinues.incrementAndGet();
            if (nth > MAX_ITER_CONTINUES) {
                log.info("步数续跑次数用尽: session={}, maxIters={}", turn.sessionId, maxIters);
                return Flux.just(StreamEvent.notice(turn.sessionId, "iterStop",
                        maxIters + "," + MAX_ITER_CONTINUES));
            }
            log.info("步数触顶，自动续跑 {}/{}: session={}, maxIters={}",
                    nth, MAX_ITER_CONTINUES, turn.sessionId, maxIters);
            Msg resume = Msg.builder().role(MsgRole.USER).textContent(ITER_CONTINUE_PROMPT).build();
            return Flux.concat(
                    Flux.just(StreamEvent.notice(turn.sessionId, "iterContinue",
                            maxIters + "," + nth + "," + MAX_ITER_CONTINUES)),
                    runEvents(resume, turn),
                    continueAfterIterLimit(turn));
        });
    }

    /** 组装 USER 消息：文本块 + 若干图片块（Base64）；无文本时至少留一个空文本块以满足角色约束。 */
    private static Msg buildUserMsg(String text, List<ImageAttachment> images) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            blocks.add(TextBlock.builder().text(text).build());
        }
        if (images != null) {
            for (ImageAttachment img : images) {
                if (img == null || img.base64Data() == null || img.base64Data().isBlank()) {
                    continue;
                }
                blocks.add(ImageBlock.builder()
                        .source(new Base64Source(img.mediaType(), img.base64Data()))
                        .build());
            }
        }
        if (blocks.isEmpty()) {
            blocks.add(TextBlock.builder().text("").build());
        }
        return Msg.builder().role(MsgRole.USER).content(blocks).build();
    }

    /** 消费一次 Agent 轮次的事件流；遇到确认事件则解析决策并续跑，形成连续流 */
    private Flux<StreamEvent> runEvents(Msg input, Turn turn) {
        return Flux.defer(() -> agent().streamEvents(input, turn.ctx))
                .concatMap(event -> handle(event, turn));
    }

    private Flux<StreamEvent> handle(AgentEvent event, Turn turn) {
        String sessionId = turn.sessionId;
        Map<String, ToolTrack> toolTracks = turn.toolTracks;
        // 每次模型调用结束都累加其用量（含工具循环里的多轮），不下发给 UI
        if (event instanceof ModelCallEndEvent mce) {
            accumulate(turn.usageAcc, mce.getUsage());
            return Flux.empty();
        }
        // 步数触顶：框架随后补发一轮收尾摘要，本运行体在摘要流结束后自动续跑（见 continueAfterIterLimit）
        if (event instanceof ExceedMaxItersEvent e) {
            turn.iterLimitHit.set(true);
            log.warn("单轮步数触顶: agent={}, session={}, iter={}/{}",
                    agentId, sessionId, e.getCurrentIter(), e.getMaxIters());
            return Flux.empty();
        }
        // 工具生命周期：跟踪表按 toolCallId 聚合入参与输出，收尾合成一条 TOOL_END 下发（供落盘与折叠渲染）
        if (event instanceof ToolCallStartEvent e) {
            toolTracks.put(key(e.getToolCallId(), e.getToolCallName()), new ToolTrack(e.getToolCallName()));
            return Flux.just(StreamEvent.toolStart(sessionId, e.getToolCallName(), null));
        }
        if (event instanceof ToolCallDeltaEvent e) {
            ToolTrack t = toolTracks.get(key(e.getToolCallId(), e.getToolCallName()));
            if (t != null && e.getDelta() != null) {
                t.args.append(e.getDelta());
            }
            return Flux.empty();
        }
        if (event instanceof ToolCallEndEvent e) {
            // 入参是随调用流式吐出的，Start 事件时刻还没有：此刻补发一次带真实入参的 TOOL_START，
            // 让状态栏在工具真正执行的这段时间里说清它动的是哪个文件、跑的是哪条命令。
            // 落盘只看 TOOL_END，重复的 TOOL_START 不进库；渲染侧按「有无入参」区分两次提示。
            ToolTrack t = toolTracks.get(key(e.getToolCallId(), e.getToolCallName()));
            String args = t == null ? null : truncate(t.args.toString());
            if (args == null || args.isBlank()) {
                return Flux.empty();
            }
            return Flux.just(StreamEvent.toolStart(sessionId, e.getToolCallName(), args));
        }
        if (event instanceof ToolResultStartEvent e) {
            toolTracks.computeIfAbsent(key(e.getToolCallId(), e.getToolCallName()),
                    k -> new ToolTrack(e.getToolCallName()));
            return Flux.empty();
        }
        if (event instanceof ToolResultTextDeltaEvent e) {
            ToolTrack t = toolTracks.get(key(e.getToolCallId(), e.getToolCallName()));
            if (t != null && e.getDelta() != null) {
                t.output.append(e.getDelta());
            }
            return Flux.empty();
        }
        if (event instanceof ToolResultEndEvent e) {
            ToolTrack t = toolTracks.remove(key(e.getToolCallId(), e.getToolCallName()));
            String name = e.getToolCallName() != null ? e.getToolCallName() : (t == null ? "tool" : t.name);
            String input = t == null ? null : truncate(t.args.toString());
            String output = t == null ? "" : truncate(t.output.toString());
            long duration = t == null ? 0L : Math.max(0L, System.currentTimeMillis() - t.startMillis);
            boolean ok = e.getState() == null || e.getState() == ToolResultState.SUCCESS;
            return Flux.just(StreamEvent.toolEnd(sessionId, name, input, output, duration, ok));
        }
        // 子代理（agent_spawn / agent_send）的事件被框架原样转进父流并打上 source 标记：
        // 它的正文与思考不能并进主答案（两个智能体的话会混成一段），它的 AgentEnd 更不是本轮结束，
        // 它的确认事件若拿去续跑会把「父代理没发出的工具结果」塞进父代理上下文。
        // 工具生命周期事件放行在上面已处理完毕——子代理跑命令、改文件时，用户得看得见动静。
        if (fromSubagent(event)) {
            String source = event.getSource();
            if (event instanceof AgentStartEvent start) {
                return Flux.just(StreamEvent.notice(sessionId, "subagentStart", subagentName(start, source)));
            }
            log.debug("子代理事件不下发主轮: session={}, source={}, type={}",
                    sessionId, source, event.getType());
            return Flux.empty();
        }
        if (event instanceof RequireUserConfirmEvent rc) {
            List<ToolUseBlock> calls = rc.getToolCalls() == null ? List.of() : rc.getToolCalls();
            String toolName = calls.isEmpty() ? "tool" : calls.get(0).getName();
            // 结构化 JSON：拦截链提取 path，HITL 弹框渲染 diff 预览
            String toolInput = ToolCallsCodec.encode(calls);
            StreamEvent ask = StreamEvent.confirmRequest(sessionId, toolName, toolInput);

            return confirmDecision(turn, toolName, toolInput)
                    .map(outcome -> toResults(calls, outcome))
                    .flatMapMany(results -> {
                        Msg resume = buildConfirmMsg(rc, results);
                        return Flux.concat(Flux.just(ask), runEvents(resume, turn));
                    });
        }
        StreamEvent mapped = AgentEventMapper.toStreamEvent(sessionId, event);
        return mapped == null ? Flux.empty() : Flux.just(mapped);
    }

    /** 事件是否出自子代理：框架把子代理事件转进父流时逐个打上 source 路径，主轮自身的事件没有这个标记。 */
    static boolean fromSubagent(AgentEvent event) {
        String source = event.getSource();
        return source != null && !source.isBlank();
    }

    /** 子代理名：优先事件自带的智能体名，缺失时取 source 路径（父会话/子智能体）的最后一段。 */
    private static String subagentName(AgentStartEvent start, String source) {
        String name = start.getName();
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        int slash = source.lastIndexOf('/');
        return slash < 0 ? source : source.substring(slash + 1);
    }

    /** 工具调用的聚合键：优先 toolCallId，缺失时退回工具名（同名并发极少，够用）。 */
    private static String key(String toolCallId, String toolName) {
        return toolCallId != null && !toolCallId.isBlank() ? toolCallId : String.valueOf(toolName);
    }

    /**
     * 工具文本截断：留头留尾、省略中间，并写明省略了多少。
     * <p>只截前段的旧做法会把长输出的价值砍掉大半——命令失败时真正的报错在末尾，跑脚本时的
     * 统计结果也常打在最后几行；头尾各留一段，用户不必展开日志就能看到结论。</p>
     */
    private static String truncate(String s) {
        if (s == null || s.length() <= TOOL_TEXT_MAX) {
            return s;
        }
        int head = TOOL_TEXT_MAX * 2 / 3;
        int tail = TOOL_TEXT_MAX - head;
        return s.substring(0, head)
                + "\n\n…（中间省略 " + (s.length() - TOOL_TEXT_MAX) + " 字符）…\n\n"
                + s.substring(s.length() - tail);
    }

    /**
     * 一次用户轮次的共享状态：主流程与所有后续段（HITL 确认续跑、步数触顶续跑）共用同一份
     * 上下文、用量累加器与工具跟踪表，收尾时才能给出整轮累计用量与完整工具轨迹。
     */
    private static final class Turn {
        private final RuntimeContext ctx;
        private final String sessionId;
        /**
         * 本轮的自动通过档（审批档位「自动」）：随请求进来，随这条流走到最后一滴。
         * <p>曾经它是运行体上的一个布尔字段，两个会话共用同一运行体时后写覆盖前写，
         * 询问档的会话会被自动档的会话代签放行；确认决策既然要按轮回喂模型，档位也必须按轮存。</p>
         */
        private final boolean autoApprove;
        /** 整条流共享的用量累加器；流结束时一次性下发累计用量 */
        private final AtomicReference<TokenUsage> usageAcc = new AtomicReference<>(TokenUsage.ZERO);
        /** 整条流共享的工具调用跟踪表（toolCallId → 入参/输出/起始时刻），收尾时合成 TOOL_END */
        private final Map<String, ToolTrack> toolTracks = new ConcurrentHashMap<>();
        /** 本段事件流是否被步数上限截断（每段跑完即清零，供续跑链判定） */
        private final AtomicBoolean iterLimitHit = new AtomicBoolean();
        /** 本轮已自动续跑次数 */
        private final AtomicInteger iterContinues = new AtomicInteger();

        private Turn(RuntimeContext ctx, String sessionId, boolean autoApprove) {
            this.ctx = ctx;
            this.sessionId = sessionId;
            this.autoApprove = autoApprove;
        }
    }

    /** 单次工具调用的流式聚合体：起始时刻用于算耗时，args/output 分别累积调用入参与结果文本。 */
    private static final class ToolTrack {
        private final String name;
        private final long startMillis = System.currentTimeMillis();
        private final StringBuilder args = new StringBuilder();
        private final StringBuilder output = new StringBuilder();

        private ToolTrack(String name) {
            this.name = name;
        }
    }

    /** 把一次模型调用的 {@link ChatUsage} 累进本轮总用量（一次调用计一次，耗时秒转毫秒累加；lastInput 随最新一次覆盖）。 */
    private static void accumulate(AtomicReference<TokenUsage> acc, ChatUsage usage) {
        if (usage == null) {
            return;
        }
        long elapsedMillis = Math.round(usage.getTime() * 1000.0);
        TokenUsage delta = new TokenUsage(usage.getInputTokens(), usage.getOutputTokens(),
                usage.getCachedTokens(), 1, elapsedMillis, usage.getInputTokens());
        acc.updateAndGet(cur -> cur.plus(delta));
    }

    /**
     * 取一次工具调用的放行裁决。
     * <p>档位取自 {@code turn}，不取运行体字段：HITL 确认与续跑都发生在同一条流的任意时刻，
     * 其间别的会话完全可能已经改写过「本轮档位」——按轮回喂模型的决策，就得按轮定档位。</p>
     */
    private Mono<ConfirmOutcome> confirmDecision(Turn turn, String toolName, String toolInput) {
        ToolConfirmCallback callback = this.confirmCallback;
        if (callback == null) {
            log.warn("无 HITL 通道，敏感工具被拒绝: tool={}", toolName);
            return Mono.just(ConfirmOutcome.deny("当前没有人工确认通道，敏感工具被默认拒绝"));
        }
        boolean auto = turn.autoApprove;
        return Mono.defer(() -> {
            CompletableFuture<ConfirmOutcome> future = callback.confirm(turn.sessionId, toolName, toolInput, auto);
            return Mono.fromFuture(future);
        }).onErrorResume(e -> {
            log.warn("工具确认流程异常，默认拒绝: tool={}", toolName, e);
            return Mono.just(ConfirmOutcome.deny("确认流程异常，本次操作被拒绝"));
        }).defaultIfEmpty(ConfirmOutcome.deny("确认超时，本次操作被拒绝"));
    }

    private static List<ConfirmResult> toResults(List<ToolUseBlock> calls, ConfirmOutcome outcome) {
        List<ConfirmResult> results = new ArrayList<>(calls.size());
        for (ToolUseBlock call : calls) {
            // 拒绝理由随裁决回喂：框架用它替换默认的 "Permission denied by user" 写进模型上下文
            results.add(new ConfirmResult(outcome.allowed(), call, null,
                    outcome.allowed() ? null : outcome.reason()));
        }
        return results;
    }

    private static Msg buildConfirmMsg(RequireUserConfirmEvent rc, List<ConfirmResult> results) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Msg.METADATA_CONFIRM_REQUEST_REPLY_ID, rc.getReplyId());
        metadata.put(Msg.METADATA_CONFIRM_RESULTS, results);
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent("")
                .metadata(metadata)
                .build();
    }

    /** 关闭运行时（幂等）：先摘除登记的 MCP 客户端再关闭底层 Agent，关闭后置空引用；重复调用无副作用。 */
    @Override
    public void close() {
        HarnessAgent local = this.agent;
        if (local != null) {
            releaseMcpClients(local);
            local.close();
            this.agent = null;
        }
    }

    /**
     * 逐个摘除本智能体登记的 MCP 客户端。
     * <p>{@code HarnessAgent.close()} 不会连带关闭 Toolkit 里的 MCP 客户端，stdio 服务会留下
     * 子进程；而失效重建（改定义/改能力）是常规操作，不手动摘除就会逐次累积。</p>
     */
    private void releaseMcpClients(HarnessAgent local) {
        ToolsConfig tools = this.toolsConfig;
        if (tools == null || tools.getMcpServers() == null) {
            return;
        }
        tools.getMcpServers().keySet().forEach(name -> {
            try {
                local.getToolkit().removeMcpClient(name).block(MCP_RELEASE_TIMEOUT);
            } catch (RuntimeException e) {
                log.warn("摘除 MCP 客户端失败: {}", name, e);
            }
        });
    }
}
