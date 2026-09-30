package com.gettyio.gim.agent.core.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * 模型连通性探测：按给定规格发一条最小对话，验证 baseUrl / apiKey / 模型名这一组配置是否真的可用。
 * <p>配置页在保存前测一次，比让用户到会话里撞上一条 401 更早暴露问题。探测走独立实例、
 * 不进 {@link AgentScopeModelFactory} 缓存，也永不抛异常——探测失败本身就是要回显的结果。</p>
 */
public class ModelProbe {

    private static final Logger log = LoggerFactory.getLogger(ModelProbe.class);

    /** 探测超时：覆盖 DNS + TLS 握手 + 首字返回，海外提供商也来得及给出结论。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** 只要一个回包就够，限制输出长度，避免真的生成一段话白烧 token。 */
    private static final int MAX_TOKENS = 16;
    private static final String PING = "ping";

    private final AgentScopeModelFactory modelFactory;

    public ModelProbe(AgentScopeModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    /**
     * 探测结果。
     *
     * @param ok     是否连通并拿到回包
     * @param detail 可直接展示的细节：成功为耗时，失败为归一化后的错误原因
     */
    public record Result(boolean ok, String detail) {
    }

    /** 发一条 ping 探测；任何异常（含超时、鉴权失败、规格非法）都收敛成失败结果。 */
    public Result probe(ModelSpec spec) {
        long start = System.nanoTime();
        try {
            Model model = modelFactory.create(spec);
            Msg ping = Msg.builder().role(MsgRole.USER).textContent(PING).build();
            GenerateOptions options = GenerateOptions.builder()
                    .modelName(spec.modelName())
                    .maxTokens(MAX_TOKENS)
                    .build();
            ChatResponse first = model.stream(List.of(ping), List.of(), options).next().block(TIMEOUT);
            if (first == null) {
                return failure(spec, "连接已建立，但模型没有返回任何内容");
            }
            return new Result(true, "已连通 · " + elapsedMs(start) + "ms");
        } catch (Exception e) {
            return failure(spec, describe(e));
        }
    }

    private Result failure(ModelSpec spec, String reason) {
        log.warn("模型连通性探测失败: provider={}, model={}, 原因={}",
                spec.providerCode(), spec.modelName(), reason);
        return new Result(false, reason);
    }

    /**
     * 把异常收敛成一句人话：阻塞超时抛的是 IllegalStateException（文案是 Reactor 的英文串），
     * 其余情形剥到最内层 cause，拿到 SDK 包装的 HTTP 状态与 provider 返回的错误消息。
     */
    private static String describe(Exception e) {
        String raw = e.getMessage();
        if (e instanceof IllegalStateException && raw != null && raw.startsWith("Timeout")) {
            return "请求超时（" + TIMEOUT.toSeconds() + "s），请检查 BaseUrl 与网络";
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = root.getMessage();
        if (text == null || text.isBlank()) {
            text = root.getClass().getSimpleName();
        }
        return text;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
