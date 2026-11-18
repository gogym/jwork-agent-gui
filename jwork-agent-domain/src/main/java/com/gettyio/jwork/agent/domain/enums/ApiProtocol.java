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

package com.gettyio.jwork.agent.domain.enums;

/**
 * 模型提供商 API 协议，决定 AgentScope 侧使用哪个 Model 实现
 *
 * @author gogym
 */
public enum ApiProtocol {

    /** DashScope（通义千问）原生协议 */
    DASHSCOPE,

    /** OpenAI Chat Completions 兼容（OpenAI / DeepSeek / GLM / Kimi / MiniMax / Ollama） */
    OPENAI_COMPATIBLE,

    /** Anthropic Messages 兼容 */
    ANTHROPIC_COMPATIBLE,

    /** Ollama 原生 API */
    OLLAMA;

    /** 按名称解析（忽略大小写、去首尾空白）；{@code null}/空白回退 {@link #OPENAI_COMPATIBLE}，未知值抛 {@link IllegalArgumentException}。 */
    public static ApiProtocol fromCode(String code) {
        if (code == null || code.isBlank()) {
            return OPENAI_COMPATIBLE;
        }
        for (ApiProtocol protocol : values()) {
            if (protocol.name().equalsIgnoreCase(code.trim())) {
                return protocol;
            }
        }
        throw new IllegalArgumentException("不支持的 API 协议: " + code);
    }
}
