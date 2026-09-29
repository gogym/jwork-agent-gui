package com.gettyio.gim.agent.domain.enums;

/**
 * 模型提供商 API 协议，决定 AgentScope 侧使用哪个 Model 实现
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
