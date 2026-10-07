package com.gettyio.gim.agent.core.chat;

import com.gettyio.gim.agent.core.capability.AgentCapabilityResolver;
import com.gettyio.gim.agent.core.subagent.SubagentSpecs;
import com.gettyio.gim.agent.domain.dao.AgentDefinitionDao;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 多智能体网关：按 agentCode 解析 {@link AgentDefinition}，为每个智能体惰性装配并缓存一个独立的
 * {@link HarnessAgentGateway}（各自的人格、模型与工作区），使 agents 表真正成为运行时依据。
 * <p>字段留空时逐级回退，保证既有单智能体行为不变：</p>
 * <ul>
 *   <li>systemPrompt 为空 → 工作区 AGENTS.md（装配方提供的 fallbackPrompt）；</li>
 *   <li>providerCode/modelCode 未同时指定 → 应用默认模型；</li>
 *   <li>workspaceDir 为空 → 共享工作区目录；相对路径被限制在该目录之内。</li>
 * </ul>
 * <p>技能与 MCP 则相反，走<b>严格白名单</b>：由 {@link AgentCapabilityResolver} 按定义的
 * skills_json / mcp_servers_json 下发，未声明即无能力。</p>
 */
public class MultiAgentGateway implements AgentGateway, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentGateway.class);

    /** 内置默认智能体编码：会话/定时任务未指定智能体时使用 */
    public static final String DEFAULT_AGENT_CODE = "gim-agent";

    /** 运行体缓存键的分隔符：用不可见控制字符，避免与真实编码冲突 */
    private static final char RUNTIME_KEY_SEP = '\u0000';

    private final AgentDefinitionDao agentDao;
    /** 智能体定义 + 消息级模型覆盖 → 模型实例（由装配方提供，通常自带实例缓存） */
    private final ModelResolver modelResolver;
    /** 定义未提供人格时的回退提示词 */
    private final String fallbackPrompt;
    /** 定义未提供工作区时的回退目录 */
    private final Path fallbackWorkspace;
    /** 每个新装配运行体的统一挂载项：HITL 确认回调、技能仓库、自定义工具 */
    private final Consumer<HarnessAgentGateway> assembler;
    /** 每个运行体的差异化能力：按定义的技能/MCP 白名单下发 */
    private final AgentCapabilityResolver capabilities;

    private final Map<String, HarnessAgentGateway> runtimes = new ConcurrentHashMap<>();
    /**
     * 装配与失效共用同一把锁：慢装配（磁盘 I/O + 模型解析）不再跑在 ConcurrentHashMap.computeIfAbsent
     * 里（那会锁住整张表的其他键）；而失效remove+close与装配严格串行，杜绝「装配完成时定义已被失效，
     * 旧实例连同已 close 的运行体回填缓存」这一竞争。
     */
    private final Object buildLock = new Object();

    public MultiAgentGateway(AgentDefinitionDao agentDao,
                             ModelResolver modelResolver,
                             String fallbackPrompt,
                             Path fallbackWorkspace,
                             AgentCapabilityResolver capabilities,
                             Consumer<HarnessAgentGateway> assembler) {
        this.agentDao = agentDao;
        this.modelResolver = modelResolver;
        this.fallbackPrompt = fallbackPrompt;
        this.fallbackWorkspace = fallbackWorkspace;
        this.capabilities = capabilities;
        this.assembler = assembler;
    }

    @Override
    public Mono<String> ask(String agentCode, String sessionId, String userText) {
        ChatRequest req = ChatRequest.builder()
                .agentCode(agentCode)
                .sessionId(sessionId)
                .text(userText)
                .build();
        return gatewayFor(req).ask(sessionId, userText);
    }

    @Override
    public Flux<StreamEvent> stream(ChatRequest request) {
        return gatewayFor(request).stream(request);
    }

    /** 智能体定义被编辑或删除后丢弃其全部模型变体运行时，下次调用按新定义重新装配 */
    public void invalidate(String agentCode) {
        String prefix = normalize(agentCode) + RUNTIME_KEY_SEP;
        synchronized (buildLock) {
            for (String key : List.copyOf(runtimes.keySet())) {
                if (key.startsWith(prefix)) {
                    HarnessAgentGateway gateway = runtimes.remove(key);
                    if (gateway != null) {
                        closeQuietly(gateway);
                        log.info("智能体运行时已失效，下次调用按新定义重装: {}", key);
                    }
                }
            }
        }
    }

    /**
     * 解析指定智能体的工作区目录（与运行时装配同一套回退逻辑）：定义缺失或未填 {@code workspace_dir}
     * 时回退共享工作区。供应用级记忆/知识管理页定位该智能体的 {@code MEMORY.md} 与 {@code knowledge/}。
     */
    public Path workspaceForAgent(String agentCode) {
        AgentDefinition def = agentDao.findByCode(normalize(agentCode)).orElse(null);
        return def == null ? fallbackWorkspace : workspaceFor(def);
    }

    public void invalidateAll() {
        synchronized (buildLock) {
            runtimes.values().forEach(MultiAgentGateway::closeQuietly);
            runtimes.clear();
        }
        log.info("全部智能体运行时已失效");
    }

    @Override
    public void stop(String agentCode, String sessionId) {
        // 未装配则无在途生成可打断；按前缀命中该智能体的全部模型变体运行体逐个中断
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String prefix = normalize(agentCode) + RUNTIME_KEY_SEP;
        for (Map.Entry<String, HarnessAgentGateway> entry : runtimes.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                entry.getValue().stop(sessionId);
            }
        }
    }

    private HarnessAgentGateway gatewayFor(ChatRequest request) {
        String key = runtimeKey(request.getAgentCode(), request.getProviderCode(), request.getModelCode());
        HarnessAgentGateway hit = runtimes.get(key);
        if (hit != null) {
            return hit;
        }
        synchronized (buildLock) {
            // 双检：等锁期间可能已被同键装配或被失效后由另一调用装配完成
            hit = runtimes.get(key);
            if (hit != null) {
                return hit;
            }
            HarnessAgentGateway built = build(request);
            runtimes.put(key, built);
            return built;
        }
    }

    private static String normalize(String agentCode) {
        return agentCode == null || agentCode.isBlank() ? DEFAULT_AGENT_CODE : agentCode;
    }

    /** 运行体缓存键：智能体 + 消息级模型覆盖；未覆盖的两位用哨兵占位，与真实编码不冲突。 */
    private static String runtimeKey(String agentCode, String providerCode, String modelCode) {
        return normalize(agentCode) + RUNTIME_KEY_SEP + nullSafe(providerCode)
                + RUNTIME_KEY_SEP + nullSafe(modelCode);
    }

    private static String nullSafe(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    /** 依定义装配运行体；定义缺失时以默认人格运行（不阻断对话，仅告警） */
    private HarnessAgentGateway build(ChatRequest request) {
        String agentCode = normalize(request.getAgentCode());
        AgentDefinition def = agentDao.findByCode(agentCode).orElse(null);
        if (def == null) {
            log.warn("智能体定义不存在，按默认人格运行: agentCode={}", agentCode);
            def = new AgentDefinition();
            def.setAgentCode(agentCode);
        }
        String prompt = blank(def.getSystemPrompt()) ? fallbackPrompt : def.getSystemPrompt();
        String name = blank(def.getAgentName()) ? agentCode : def.getAgentName();
        Path workspace = workspaceFor(def);
        Path project = projectFor(def);
        log.info("装配智能体: code={}, name={}, override={}/{}, defProvider={}, defModel={}, workspace={}, project={}",
                agentCode, name, request.getProviderCode(), request.getModelCode(),
                def.getProviderCode(), def.getModelCode(), workspace, project);

        final AgentDefinition effective = def;
        final String providerCode = request.getProviderCode();
        final String modelCode = request.getModelCode();
        // 第一个参数是上下文目录键（不可变编码），第二个才是可改的显示名
        HarnessAgentGateway gateway = new HarnessAgentGateway(agentCode, name, prompt,
                () -> modelResolver.resolve(effective, providerCode, modelCode), workspace);
        // 备用模型惰性解析：装配时才找替代，解析不出则保持单模型（不抛错不阻断对话）
        gateway.setFallbackSupplier(() -> modelResolver.resolveFallback(effective, providerCode, modelCode));
        gateway.setProjectDir(project);
        assembler.accept(gateway);
        // 能力在通用挂载之后下发：白名单可以覆盖共享技能目录等全局默认
        capabilities.applyTo(gateway, effective);
        // 子智能体编排：把除自身外的其它启用智能体映射为可委派声明，主控经 agent_spawn 派任子任务
        gateway.setSubagentDeclarations(SubagentSpecs.mapOtherEnabled(agentDao.findEnabled(), agentCode));
        return gateway;
    }

    /** 智能体专属工作区：相对共享目录解析，越界或创建失败则回退共享目录 */
    private Path workspaceFor(AgentDefinition def) {
        String relative = def.getWorkspaceDir();
        if (blank(relative)) {
            return fallbackWorkspace;
        }
        Path candidate = fallbackWorkspace.resolve(relative).normalize();
        if (!candidate.startsWith(fallbackWorkspace)) {
            log.warn("智能体工作区越出共享目录，回退共享目录: {}", relative);
            return fallbackWorkspace;
        }
        try {
            Files.createDirectories(candidate);
        } catch (IOException e) {
            log.warn("创建智能体工作区失败，回退共享目录: {}", candidate, e);
            return fallbackWorkspace;
        }
        return candidate;
    }

    /**
     * 智能体授权的项目目录（绝对路径）：未填、不存在或不是目录则不开放（返回 null，
     * 写操作全部落在工作区），避免把一个失效路径当成可编辑目标。
     */
    private Path projectFor(AgentDefinition def) {
        String configured = def.getProjectPath();
        if (blank(configured)) {
            return null;
        }
        Path candidate = Path.of(configured.trim());
        if (!Files.isDirectory(candidate)) {
            log.warn("智能体项目目录不存在或不是目录，不开放文件编辑: {}", candidate);
            return null;
        }
        return candidate;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static void closeQuietly(HarnessAgentGateway gateway) {
        try {
            gateway.close();
        } catch (RuntimeException e) {
            log.warn("关闭智能体运行时失败", e);
        }
    }

    @Override
    public void close() {
        invalidateAll();
    }

    /**
     * 模型解析器：由装配方提供，把智能体定义与消息级模型覆盖（providerCode/modelCode，可空）
     * 解析成具体的 {@link Model} 实例。覆盖为空时应回退到智能体自带模型或应用默认。
     */
    @FunctionalInterface
    public interface ModelResolver {
        Model resolve(AgentDefinition definition, String providerCode, String modelCode);

        /**
         * 主模型故障时的备用模型：同链解析主模型后取下一个可用替代；无替代返回 {@code null}（不启用 failover）。
         * <p>装配方不覆盖即单模型运行，行为与未引入弹性前一致。</p>
         */
        default Model resolveFallback(AgentDefinition definition, String providerCode, String modelCode) {
            return null;
        }
    }
}
