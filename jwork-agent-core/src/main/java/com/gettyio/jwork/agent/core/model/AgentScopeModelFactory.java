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

package com.gettyio.jwork.agent.core.model;

import com.gettyio.jwork.agent.domain.enums.ApiProtocol;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentScope 模型工厂（自 MVP ChatModelFactory 的 provider/protocol 抽象移植）。
 * <p>按 {@link ApiProtocol} 分发到对应 ChatModel Builder，并按 providerCode:modelCode 缓存实例。</p>
 *
 * @author gogym
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

    /** 按 {@link ApiProtocol} 分发构建对应 ChatModel 实例（不查缓存，直接新建；缓存入口见 {@link #getOrCreate}）。 */
    public Model create(ModelSpec spec) {
        log.info("构建模型实例: protocol={}, provider={}, model={}", spec.protocol(), spec.providerCode(), spec.modelName());
        GenerateOptions options = buildOptions(spec);
        return switch (spec.protocol()) {
            case DASHSCOPE -> dashscope(spec, options);
            case OPENAI_COMPATIBLE -> openAi(spec, options);
            case ANTHROPIC_COMPATIBLE -> anthropic(spec, options);
            case OLLAMA -> ollama(spec, options);
        };
    }

    private GenerateOptions buildOptions(ModelSpec spec) {
        GenerateOptions.Builder b = GenerateOptions.builder().modelName(spec.modelName())
                // 预算/额度触顶类 429 不重试：重试不会恢复，只会刷满重试堆栈（对话与压缩同口径）
                .executionConfig(ModelRetryPolicy.executionConfig());
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

    private Model openAi(ModelSpec spec, GenerateOptions options) {
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .stream(spec.stream())
                .generateOptions(options);
        if (spec.baseUrl() != null && !spec.baseUrl().isBlank()) {
            builder.baseUrl(normalizeOpenAiBaseUrl(spec.baseUrl()));
        }
        return builder.build();
    }

    private Model anthropic(ModelSpec spec, GenerateOptions options) {
        AnthropicChatModel.Builder builder = AnthropicChatModel.builder()
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .stream(spec.stream())
                .defaultOptions(options);
        if (spec.baseUrl() != null && !spec.baseUrl().isBlank()) {
            builder.baseUrl(normalizeAnthropicBaseUrl(spec.baseUrl()));
        }
        return builder.build();
    }

    private Model ollama(ModelSpec spec, GenerateOptions options) {
        OllamaChatModel.Builder builder = OllamaChatModel.builder()
                .modelName(spec.modelName())
                .stream(spec.stream())
                .defaultOptions(OllamaOptions.fromGenerateOptions(options));
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
