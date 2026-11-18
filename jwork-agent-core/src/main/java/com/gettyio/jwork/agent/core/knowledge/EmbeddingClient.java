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

package com.gettyio.jwork.agent.core.knowledge;

import java.util.List;

/**
 * 嵌入客户端抽象：把一段文本映射为向量（决策点 B：先云 API，ONNX 本地二期）。
 * <p>实现按提供方协议划分——OpenAI 兼容的 {@code /embeddings} 一族（OpenAI / DashScope
 * compatible-mode / LM Studio / Ollama openai 档）共用 {@link OpenAiCompatibleEmbeddingClient}。</p>
 *
 * @author gogym
 */
public interface EmbeddingClient {

    /** 把一批文本批量嵌入（顺序与输入一致）；实现可内部再分批。 */
    float[][] embedAll(List<String> texts);

    /** 单文本便捷嵌入。 */
    default float[] embed(String text) {
        return embedAll(List.of(text))[0];
    }

    /** 索引指纹用的配置身份：同一内容换过嵌入配置（如模型）也需重建，默认按实现类区分。 */
    default String cacheKey() {
        return getClass().getName();
    }
}
