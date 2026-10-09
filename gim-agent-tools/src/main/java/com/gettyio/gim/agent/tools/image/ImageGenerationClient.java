package com.gettyio.gim.agent.tools.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * OpenAI 兼容图像生成客户端：{@code POST {baseUrl}/images/generations}，按 {@code b64_json}
 * 优先、{@code url} 兜底两种响应形态取回图片字节。
 * <p>配置（地址/模型/Key）由装配方注入一个 {@link Supplier}（通常从加密的 {@code settings} 读取并解密），
 * 运行时实时取用——用户在设置页改配置无需重启即生效。未配置时抛带明确指引的
 * {@link IllegalStateException}，由调用方（工具/UI）转述给用户，而不是暴露技术栈细节。</p>
 */
public class ImageGenerationClient {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 生成与下载的超时：生图普遍较慢，给足 120s。 */
    private static final Duration GENERATE_TIMEOUT = Duration.ofSeconds(120);

    /** 当前生效配置：地址/模型空视为未配置；Key 可空（兼容本地免鉴权服务）。 */
    public record Config(String baseUrl, String model, String apiKey) {
    }

    /** 提供实时配置（每次调用现取，改配即时生效）；由装配方注入，通常带解密逻辑。 */
    private final Supplier<Config> configSupplier;

    public ImageGenerationClient(Supplier<Config> configSupplier) {
        this.configSupplier = configSupplier;
    }

    /**
     * 生成一张图片，返回图片字节（服务端原格式，多为 PNG）。
     *
     * @throws IllegalStateException 未配置或服务报错（信息可直接面向用户展示）
     */
    public byte[] generate(String prompt) {
        Config cfg = currentConfig();
        if (cfg == null || cfg.baseUrl() == null || cfg.baseUrl().isBlank()
                || cfg.model() == null || cfg.model().isBlank()) {
            throw new IllegalStateException("图像生成未配置：请在「设置 > 通用 > 图像生成」填写服务地址与模型后重试。");
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", cfg.model().trim());
            body.put("prompt", prompt);
            body.put("n", 1);
            body.put("response_format", "b64_json");
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint(cfg.baseUrl())))
                    .timeout(GENERATE_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
            if (cfg.apiKey() != null && !cfg.apiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + cfg.apiKey().trim());
            }
            HttpResponse<String> resp = HTTP.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                log.warn("图像生成非 2xx: status={}", resp.statusCode());
                throw new IllegalStateException("图像生成服务返回 HTTP " + resp.statusCode() + serverMessage(resp.body()));
            }
            String b64 = b64Of(resp.body());
            if (b64 != null) {
                return Base64.getDecoder().decode(b64);
            }
            String url = urlOf(resp.body());
            if (url != null) {
                return download(url);
            }
            throw new IllegalStateException("图像生成服务未返回图片数据" + serverMessage(resp.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("图像生成被中断。");
        } catch (IOException e) {
            throw new IllegalStateException("图像生成请求失败：" + e.getMessage(), e);
        }
    }

    /** 把生成结果落盘到 {@code dir}（{@code gim-<时间戳>-<随机>.png}），返回文件路径；工具与聊天页入口共用。 */
    public static Path save(Path dir, byte[] bytes) throws IOException {
        Files.createDirectories(dir);
        String name = "gim-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".png";
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    /** 拼接生成端点：容忍 baseUrl 带或不带尾斜杠。 */
    static String endpoint(String baseUrl) {
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/images/generations";
    }

    /** 从响应 JSON 提取第一张图的 base64 数据；无则返回 {@code null}。 */
    static String b64Of(String json) {
        JsonNode node = firstImage(json);
        String s = node == null ? "" : node.path("b64_json").asText("");
        return s.isBlank() ? null : s;
    }

    /** 从响应 JSON 提取第一张图的 URL；无则返回 {@code null}。 */
    static String urlOf(String json) {
        JsonNode node = firstImage(json);
        String s = node == null ? "" : node.path("url").asText("");
        return s.isBlank() ? null : s;
    }

    private static JsonNode firstImage(String json) {
        try {
            JsonNode data = MAPPER.readTree(json).path("data");
            return data.isArray() && !data.isEmpty() ? data.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 服务端错误说明（{@code error.message}）拼串；无则空串，帮助用户定位配额/Key 问题。 */
    private static String serverMessage(String body) {
        try {
            String msg = MAPPER.readTree(body).path("error").path("message").asText("");
            return msg.isBlank() ? "" : "（" + msg + "）";
        } catch (Exception e) {
            return "";
        }
    }

    private byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(GENERATE_TIMEOUT)
                .GET()
                .build();
        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("下载生成图片失败：HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private Config currentConfig() {
        try {
            return configSupplier.get();
        } catch (RuntimeException e) {
            log.warn("读取图像生成配置失败", e);
            return null;
        }
    }
}
