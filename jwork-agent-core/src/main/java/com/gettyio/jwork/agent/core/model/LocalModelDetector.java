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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 本地模型服务探测：识别本机正在运行的 Ollama / LM Studio，并拉取其可用模型清单。
 * <p>探测规则：Ollama 打 {@code GET /api/tags}（默认 11434），LM Studio 打
 * {@code GET /v1/models}（默认 1234）；任一返回合法 JSON 即判定该服务在线。
 * 探测永不抛异常——离线本身就是探测结果之一，由调用方按「未发现」呈现。</p>
 * <p>供模型设置页「检测本地服务」一键接入使用；导入落库由 {@code ProviderAdminService} 负责。</p>
 *
 * @author gogym
 */
public class LocalModelDetector {

    private static final Logger log = LoggerFactory.getLogger(LocalModelDetector.class);

    /** 探测超时：本地回环 1 秒足够；太久会让设置页按钮像卡死。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final String OLLAMA_KIND = "OLLAMA";
    private static final String LMSTUDIO_KIND = "LM_STUDIO";

    private final String ollamaBase;
    private final String lmStudioBase;
    private final ObjectMapper mapper = new ObjectMapper();

    public LocalModelDetector() {
        this("http://localhost:11434", "http://localhost:1234");
    }

    /** 注入基础地址：单测里可用本地 HTTP 服务替换，不依赖真实 11434/1234 端口。 */
    LocalModelDetector(String ollamaBase, String lmStudioBase) {
        this.ollamaBase = ollamaBase;
        this.lmStudioBase = lmStudioBase;
    }

    /** 探测到的本地服务：服务种类、基础地址与模型名清单。 */
    public record LocalService(String kind, String baseUrl, List<String> models) {
    }

    /**
     * 探测本机 Ollama 与 LM Studio：先 Ollama 后 LM Studio，都未发现返回空。
     * 任一探测异常（连接拒绝、超时、响应非 JSON）都按「该服务不在线」收敛。
     */
    public Optional<LocalService> detect() {
        Optional<LocalService> ollama = detectOllama();
        if (ollama.isPresent()) {
            return ollama;
        }
        return detectLmStudio();
    }

    private Optional<LocalService> detectOllama() {
        JsonNode tags = fetchJson(ollamaBase + "/api/tags");
        if (tags == null || !tags.has("models") || !tags.get("models").isArray()) {
            return Optional.empty();
        }
        List<String> models = new ArrayList<>();
        for (JsonNode m : tags.get("models")) {
            if (m.hasNonNull("name")) {
                models.add(m.get("name").asText());
            }
        }
        if (models.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new LocalService(OLLAMA_KIND, ollamaBase, models));
    }

    private Optional<LocalService> detectLmStudio() {
        JsonNode body = fetchJson(lmStudioBase + "/v1/models");
        if (body == null || !body.has("data") || !body.get("data").isArray()) {
            return Optional.empty();
        }
        List<String> models = new ArrayList<>();
        for (JsonNode m : body.get("data")) {
            if (m.hasNonNull("id")) {
                models.add(m.get("id").asText());
            }
        }
        if (models.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new LocalService(LMSTUDIO_KIND, lmStudioBase, models));
    }

    /** GET 一个 JSON 端点并解析；任何异常（超时/连接失败/非 JSON）返回 {@code null}。 */
    private JsonNode fetchJson(String url) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            return mapper.readTree(response.body());
        } catch (Exception e) {
            log.debug("本地模型服务探测未命中: {} ({})", url, e.getClass().getSimpleName());
            return null;
        }
    }
}
