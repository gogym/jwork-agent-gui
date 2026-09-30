package com.gettyio.gim.agent.core.chat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.McpServerRegistrationListener;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * 基于 AgentScope {@link HarnessAgent} 的单体智能体运行体（一个实例对应一个智能体定义）。
 * <p>由 {@link MultiAgentGateway} 按 agentCode 装配与路由，本身不感知智能体选择。</p>
 * <p>流式逐 token 映射（M2）+ HITL 工具确认回流（M3）：当权限引擎抛出
 * {@link RequireUserConfirmEvent} 时，运行体调用 {@link ToolConfirmCallback} 获取用户决策，
 * 构造携带 {@link Msg#METADATA_CONFIRM_RESULTS} 的恢复消息续跑同一条流，UI 侧感知为连续事件流。</p>
 * <p>模型与工作区在首次调用时惰性装配，以便未配置模型的启动流程也能显示空窗口。</p>
 * <p><b>agentId 必须显式给出</b>：Harness 未指定 agentId 时会回退用 {@code name} 作为智能体标识，
 * 而它同时是会话上下文在磁盘上的目录键（{@code ~/.agentscope/state/<agentId>/} 与
 * {@code <workspace>/agents/<agentId>/sessions/<sessionId>.jsonl}）。用可改的显示名当键，
 * 改一次名就等于让该智能体名下所有历史会话失忆（界面气泡仍在，模型侧上下文全丢），
 * 因此这里固定为不可变的 agentCode。</p>
 */
public class HarnessAgentGateway implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentGateway.class);

    /** 关闭运行体时等待单个 MCP 客户端摘除的上限 */
    private static final Duration MCP_RELEASE_TIMEOUT = Duration.ofSeconds(5);

    /** 不可变标识（= agentCode）：上下文存储的目录键，不随显示名改动 */
    private final String agentId;
    private final String agentName;
    private final String sysPrompt;
    private final Supplier<Model> modelSupplier;
    private final Path workspace;
    private volatile ToolConfirmCallback confirmCallback;
    private volatile Path skillsDir;
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

    private volatile HarnessAgent agent;

    public HarnessAgentGateway(String agentId, String agentName, String sysPrompt,
                               Supplier<Model> modelSupplier, Path workspace) {
        this.agentId = agentId;
        this.agentName = agentName;
        this.sysPrompt = sysPrompt;
        this.modelSupplier = modelSupplier;
        this.workspace = workspace;
    }

    /** 运行期注入/更新工具确认回调（可空表示无 HITL 通道，敏感工具将被拒绝） */
    public void setConfirmCallback(ToolConfirmCallback confirmCallback) {
        this.confirmCallback = confirmCallback;
    }

    /** 指定技能目录（workspace/skills），Harness 将以 FileSystemSkillRepository 加载其中的 SKILL.md */
    public void setSkillsDir(Path skillsDir) {
        this.skillsDir = skillsDir;
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
        if (local != null) {
            local.getToolkit().registerTool(target);
        }
    }

    private HarnessAgent agent() {
        HarnessAgent local = this.agent;
        if (local == null) {
            synchronized (this) {
                local = this.agent;
                if (local == null) {
                    log.info("装配 HarnessAgent: id={}, name={}, workspace={}", agentId, agentName, workspace);
                    HarnessAgent.Builder builder = HarnessAgent.builder()
                            .name(agentName)
                            // 上下文目录键固定用编码：显示名可以改，改了也不影响既有会话的记忆
                            .agentId(agentId)
                            .sysPrompt(sysPrompt)
                            .model(modelSupplier.get())
                            .workspace(workspace)
                            // 默认权限模式：对副作用工具（文件/Shell）触发确认，交由 HITL 链路处理
                            .permissionContext(PermissionContextState.builder()
                                    .mode(PermissionMode.DEFAULT)
                                    .build());
                    Path skills = this.skillsDir;
                    Set<String> allowedSkills = this.skillAllowlist;
                    if (allowedSkills != null && allowedSkills.isEmpty()) {
                        // 严格白名单：该智能体未声明任何技能，连技能仓库都不必扫描
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
                    local = builder.build();
                    for (Object target : toolTargets) {
                        local.getToolkit().registerTool(target);
                    }
                    this.agent = local;
                }
            }
        }
        return local;
    }

    /** 非流式单轮问答（自主会话，无 UI 确认通道） */
    public Mono<String> ask(String sessionId, String userText) {
        RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
        Msg input = Msg.builder().role(MsgRole.USER).textContent(userText).build();
        return Mono.fromCallable(() -> {
            // 自主会话（无 UI）：不进入询问模式，敏感工具不阻塞等待确认
            HarnessAgent a = agent();
            a.setPermissionMode(ctx, PermissionMode.DONT_ASK);
            return a.call(input, ctx).block();
        }).map(Msg::getTextContent).subscribeOn(Schedulers.boundedElastic());
    }

    /** 流式对话，产出归一化事件流 */
    public Flux<StreamEvent> stream(String sessionId, String userText) {
        RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
        Msg input = Msg.builder().role(MsgRole.USER).textContent(userText).build();
        return runEvents(input, ctx, sessionId);
    }

    /** 消费一次 Agent 轮次的事件流；遇到确认事件则解析决策并续跑，形成连续流 */
    private Flux<StreamEvent> runEvents(Msg input, RuntimeContext ctx, String sessionId) {
        return Flux.defer(() -> agent().streamEvents(input, ctx))
                .concatMap(event -> handle(event, ctx, sessionId));
    }

    private Flux<StreamEvent> handle(AgentEvent event, RuntimeContext ctx, String sessionId) {
        if (event instanceof RequireUserConfirmEvent rc) {
            List<ToolUseBlock> calls = rc.getToolCalls() == null ? List.of() : rc.getToolCalls();
            String toolName = calls.isEmpty() ? "tool" : calls.get(0).getName();
            String toolInput = describeInput(calls);
            StreamEvent ask = StreamEvent.confirmRequest(sessionId, toolName, toolInput);

            return confirmDecision(sessionId, toolName, toolInput)
                    .map(allowed -> toResults(calls, allowed))
                    .flatMapMany(results -> {
                        Msg resume = buildConfirmMsg(rc, results);
                        return Flux.concat(Flux.just(ask), runEvents(resume, ctx, sessionId));
                    });
        }
        StreamEvent mapped = AgentEventMapper.toStreamEvent(sessionId, event);
        return mapped == null ? Flux.empty() : Flux.just(mapped);
    }

    private Mono<Boolean> confirmDecision(String sessionId, String toolName, String toolInput) {
        ToolConfirmCallback callback = this.confirmCallback;
        if (callback == null) {
            log.warn("无 HITL 通道，敏感工具被拒绝: tool={}", toolName);
            return Mono.just(false);
        }
        return Mono.defer(() -> {
            CompletableFuture<Boolean> future = callback.confirm(sessionId, toolName, toolInput);
            return Mono.fromFuture(future);
        }).onErrorResume(e -> {
            log.warn("工具确认流程异常，默认拒绝: tool={}", toolName, e);
            return Mono.just(false);
        }).defaultIfEmpty(false);
    }

    private static List<ConfirmResult> toResults(List<ToolUseBlock> calls, boolean allowed) {
        List<ConfirmResult> results = new ArrayList<>(calls.size());
        for (ToolUseBlock call : calls) {
            results.add(new ConfirmResult(allowed, call));
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

    private static String describeInput(List<ToolUseBlock> calls) {
        StringBuilder sb = new StringBuilder();
        for (ToolUseBlock call : calls) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(call.getName()).append('(').append(Objects.toString(call.getInput(), "")).append(')');
        }
        return sb.toString();
    }

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
