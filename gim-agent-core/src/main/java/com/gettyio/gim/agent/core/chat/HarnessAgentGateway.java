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
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * 基于 AgentScope {@link HarnessAgent} 的对话网关实现。
 * <p>流式逐 token 映射（M2）+ HITL 工具确认回流（M3）：当权限引擎抛出
 * {@link RequireUserConfirmEvent} 时，网关调用 {@link ToolConfirmCallback} 获取用户决策，
 * 构造携带 {@link Msg#METADATA_CONFIRM_RESULTS} 的恢复消息续跑同一条流，UI 侧感知为连续事件流。</p>
 * <p>模型与工作区在首次调用时惰性装配，以便未配置模型的启动流程也能显示空窗口。</p>
 */
public class HarnessAgentGateway implements AgentGateway, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentGateway.class);

    private final String agentName;
    private final String sysPrompt;
    private final Supplier<Model> modelSupplier;
    private final Path workspace;
    private volatile ToolConfirmCallback confirmCallback;
    private volatile Path skillsDir;
    /** 运行期追加的自定义工具宿主对象（含 @Tool 注解方法），在 Agent 装配后注册进其 Toolkit */
    private final List<Object> toolTargets = new CopyOnWriteArrayList<>();

    private volatile HarnessAgent agent;

    public HarnessAgentGateway(String agentName, String sysPrompt, Supplier<Model> modelSupplier, Path workspace) {
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
                    log.info("装配 HarnessAgent: name={}, workspace={}", agentName, workspace);
                    HarnessAgent.Builder builder = HarnessAgent.builder()
                            .name(agentName)
                            .sysPrompt(sysPrompt)
                            .model(modelSupplier.get())
                            .workspace(workspace)
                            // 默认权限模式：对副作用工具（文件/Shell）触发确认，交由 HITL 链路处理
                            .permissionContext(PermissionContextState.builder()
                                    .mode(PermissionMode.DEFAULT)
                                    .build());
                    Path skills = this.skillsDir;
                    if (skills != null) {
                        builder = builder.skillRepository(new FileSystemSkillRepository(skills, false));
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

    @Override
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

    @Override
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
            local.close();
            this.agent = null;
        }
    }
}
