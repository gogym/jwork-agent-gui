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

package com.gettyio.gim.agent.core.knowledge;

import com.gettyio.gim.agent.domain.util.HttpMedia;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容 {@code POST /embeddings} 实现：同协议覆盖 OpenAI、DashScope compatible-mode
 * （{@code text-embedding-*} 系列）与本地推理服务（LM Studio / Ollama openai 档）。
 * <p>{@code baseUrl} 填到协议根（如 {@code https://dashscope.aliyuncs.com/compatible-mode/v1}），
 * 本类内部补 {@code /embeddings} 路径。批量输入按每请求 16 条分批，避免超长请求被网关拒收。</p>
 *
 * @author gogym
 */
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 单请求批量上限。 */
    private static final int BATCH = 16;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final String endpoint;
    private final String apiKey;
    private final String model;

    /** 规范化 Base URL（去尾部斜杠、补 {@code /embeddings}）；{@code apiKey} 为空时请求不带鉴权头（本地服务场景）。 */
    public OpenAiCompatibleEmbeddingClient(String baseUrl, String apiKey, String model) {
        String root = baseUrl == null ? "" : baseUrl.trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        this.endpoint = root + "/embeddings";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    /** 批量向量化：按 {@value #BATCH} 条/批分块请求并汇总；空输入返回空数组，批失败抛 {@link IllegalStateException}。 */
    @Override
    public float[][] embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new float[0][];
        }
        float[][] out = new float[texts.size()][];
        for (int from = 0; from < texts.size(); from += BATCH) {
            List<String> batch = texts.subList(from, Math.min(from + BATCH, texts.size()));
            float[][] vectors = embedBatch(batch);
            for (int i = 0; i < batch.size(); i++) {
                out[from + i] = vectors[i];
            }
        }
        return out;
    }

    /** 配置身份：协议端点 + 模型名；端点或模型变更都会使既有索引指纹失配而触发重建。 */
    @Override
    public String cacheKey() {
        return endpoint + "#" + model;
    }

    private float[][] embedBatch(List<String> batch) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            ArrayNode input = body.putArray("input");
            batch.forEach(input::add);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header(HttpMedia.CONTENT_TYPE, HttpMedia.APPLICATION_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
            if (!apiKey.isEmpty()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("embedding 服务返回 HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode data = MAPPER.readTree(resp.body()).path("data");
            if (!data.isArray() || data.size() != batch.size()) {
                throw new IllegalStateException("embedding 返回条数不符：期望 " + batch.size() + "，实得 " + data.size());
            }
            List<float[]> out = new ArrayList<>(batch.size());
            for (JsonNode item : data) {
                JsonNode vec = item.path("embedding");
                float[] v = new float[vec.size()];
                for (int i = 0; i < v.length; i++) {
                    v[i] = (float) vec.get(i).asDouble();
                }
                out.add(v);
            }
            return out.toArray(new float[0][]);
        } catch (IOException e) {
            throw new IllegalStateException("embedding 请求失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding 请求被中断", e);
        }
    }
}
