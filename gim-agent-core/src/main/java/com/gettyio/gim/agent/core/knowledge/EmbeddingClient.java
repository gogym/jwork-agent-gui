package com.gettyio.gim.agent.core.knowledge;

import java.util.List;

/**
 * 嵌入客户端抽象：把一段文本映射为向量（决策点 B：先云 API，ONNX 本地二期）。
 * <p>实现按提供方协议划分——OpenAI 兼容的 {@code /embeddings} 一族（OpenAI / DashScope
 * compatible-mode / LM Studio / Ollama openai 档）共用 {@link OpenAiCompatibleEmbeddingClient}。</p>
 */
public interface EmbeddingClient {

    /** 把一批文本批量嵌入（顺序与输入一致）；实现可内部再分批。 */
    float[][] embedAll(List<String> texts);

    /** 单文本便捷嵌入。 */
    default float[] embed(String text) {
        return embedAll(List.of(text))[0];
    }
}
