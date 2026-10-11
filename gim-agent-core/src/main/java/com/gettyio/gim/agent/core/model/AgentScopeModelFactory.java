package com.gettyio.gim.agent.core.model;

import com.gettyio.gim.agent.domain.enums.ApiProtocol;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentScope 模型工厂（自 MVP ChatModelFactory 的 provider/protocol 抽象移植）。
 * <p>按 {@link ApiProtocol} 分发到对应 ChatModel Builder，并按 providerCode:modelCode 缓存实例。</p>
 */
public class AgentScopeModelFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeModelFactory.class);

    private final Map<String, Model> cache = new ConcurrentHashMap<>();

    /**
     * 获取（或创建并缓存）模型实例
     */
    public Model getOrCreate(ModelSpec spec) {
        return cache.computeIfAbsent(spec.cacheKey(), k -> create(spec));
    }

    public Model create(ModelSpec spec) {
        log.info("构建模型实例: protocol={}, provider={}, model={}", spec.protocol(), spec.providerCode(), spec.modelName());
        GenerateOptions options = buildOptions(spec);
        return switch (spec.protocol()) {
            case DASHSCOPE -> dashscope(spec, options);
            case OPENAI_COMPATIBLE -> openAi(spec);
            case ANTHROPIC_COMPATIBLE -> anthropic(spec);
            case OLLAMA -> ollama(spec);
        };
    }

    private GenerateOptions buildOptions(ModelSpec spec) {
        GenerateOptions.Builder b = GenerateOptions.builder().modelName(spec.modelName());
        if (spec.temperature() != null) {
            b.temperature(spec.temperature());
        }
        if (spec.maxTokens() != null) {
            b.maxTokens(spec.maxTokens());
        }
        return b.build();
    }

    private Model dashscope(ModelSpec spec, GenerateOptions options) {
        DashScopeChatModel.Builder builder = DashScopeChatModel.builder()
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .stream(spec.stream())
                .defaultOptions(options);
        if (spec.baseUrl() != null && !spec.baseUrl().isBlank()) {
            builder.baseUrl(spec.baseUrl());
        }
        return builder.build();
    }

    private Model openAi(ModelSpec spec) {
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .stream(spec.stream());
        if (spec.baseUrl() != null && !spec.baseUrl().isBlank()) {
            builder.baseUrl(normalizeOpenAiBaseUrl(spec.baseUrl()));
        }
        return builder.build();
    }

    private Model anthropic(ModelSpec spec) {
        AnthropicChatModel.Builder builder = AnthropicChatModel.builder()
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .stream(spec.stream());
        if (spec.baseUrl() != null && !spec.baseUrl().isBlank()) {
            builder.baseUrl(normalizeAnthropicBaseUrl(spec.baseUrl()));
        }
        return builder.build();
    }

    private Model ollama(ModelSpec spec) {
        OllamaChatModel.Builder builder = OllamaChatModel.builder()
                .modelName(spec.modelName())
                .stream(spec.stream());
        if (spec.baseUrl() != null && !spec.baseUrl().isBlank()) {
            builder.baseUrl(spec.baseUrl());
        }
        return builder.build();
    }

    /**
     * OpenAI 兼容：确保 baseUrl 不含尾部斜杠（SDK 自行拼接 /chat/completions）
     */
    private static String normalizeOpenAiBaseUrl(String baseUrl) {
        String s = baseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * Anthropic：规范化 baseUrl，去掉误配的尾部 /messages（自 MVP 移植）
     */
    private static String normalizeAnthropicBaseUrl(String baseUrl) {
        String s = baseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("/messages")) {
            s = s.substring(0, s.length() - "/messages".length());
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 清空缓存（配置变更时调用） */
    public void invalidate() {
        cache.clear();
    }

    public void invalidate(String cacheKey) {
        cache.remove(cacheKey);
    }
}
