package com.gettyio.gim.agent.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * 面向 Agent 的联网工具：{@code web_search}（默认走 Tavily）与 {@code web_fetch}（抓取网页正文）。
 * <p>由 {@code HarnessAgentGateway} 注册进 Toolkit，每个智能体一份实例。</p>
 * <p>API Key 不内置在本类，而是由装配方注入一个 {@link Supplier}（通常从加密的 {@code settings} 读取并解密），
 * 运行时实时取用——用户在设置页改 Key 无需重启即生效。未配置 Key 时，工具返回明确的「未配置」提示而非抛异常，
 * 让模型据实告知用户去配置，而不是把技术栈细节暴露成对话内容。</p>
 */
public class WebSearchTools {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String TAVILY_ENDPOINT = "https://api.tavily.com/search";
    /** 抓取正文的截断上限（字符），避免超长页面撑爆上下文。 */
    private static final int MAX_FETCH_CHARS = 6000;
    /** 单条搜索结果的摘要截断上限（字符）。 */
    private static final int SNIPPET_CHARS = 400;

    /** 提供明文 API Key（空白表示未配置）；由装配方注入，通常带解密逻辑。 */
    private final Supplier<String> apiKeySupplier;

    public WebSearchTools(Supplier<String> apiKeySupplier) {
        this.apiKeySupplier = apiKeySupplier;
    }

    @Tool(name = "web_search", readOnly = true,
            description = "联网搜索实时信息（新闻、事实、价格、文档、最新版本等模型知识截止后的内容）。"
                    + "返回若干条结果，每条含标题、URL 与摘要。需要精确出处时再对某条 URL 调 web_fetch 读正文。")
    public String webSearch(
            @ToolParam(name = "query", description = "搜索关键词或问题（尽量具体）") String query,
            @ToolParam(name = "max_results", required = false,
                    description = "返回结果条数，1-10，默认 5") String maxResults) {
        if (query == null || query.isBlank()) {
            return "query 不能为空。";
        }
        String key = currentKey();
        if (key == null || key.isBlank()) {
            return "联网搜索未配置：请在「设置 > 安全 > 联网」填写搜索服务 API Key 后重试。";
        }
        int n = clamp(parseInt(maxResults, 5), 1, 10);
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("query", query.trim());
            body.put("search_depth", "basic");
            body.put("max_results", n);
            body.put("include_answer", true);
            HttpRequest req = HttpRequest.newBuilder(URI.create(TAVILY_ENDPOINT))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                log.warn("web_search 非 2xx: status={}", resp.statusCode());
                return "搜索失败：服务返回 HTTP " + resp.statusCode()
                        + (resp.statusCode() == 401 || resp.statusCode() == 403 ? "（API Key 无效或额度不足）" : "");
            }
            return formatResults(query.trim(), MAPPER.readTree(resp.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "搜索被中断。";
        } catch (Exception e) {
            log.warn("web_search 异常", e);
            return "搜索失败：" + e.getMessage();
        }
    }

    @Tool(name = "web_fetch", readOnly = true,
            description = "抓取指定网页并返回其正文纯文本（已去标签、截断过长内容）。用于读取搜索结果里某条链接的完整页面。")
    public String webFetch(@ToolParam(name = "url", description = "要抓取的网页地址（http/https）") String url) {
        if (url == null || url.isBlank()) {
            return "url 不能为空。";
        }
        String target = url.trim();
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            return "仅支持 http/https 地址：" + target;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Jwork Desktop)")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                return "抓取失败：HTTP " + resp.statusCode();
            }
            String text = htmlToText(resp.body());
            if (text.isBlank()) {
                return "页面无可抽取的正文（可能是纯脚本渲染或非 HTML 内容）：" + target;
            }
            if (text.length() > MAX_FETCH_CHARS) {
                text = text.substring(0, MAX_FETCH_CHARS) + "…（已截断）";
            }
            return "【网页正文】" + target + "\n" + text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "抓取被中断。";
        } catch (Exception e) {
            log.warn("web_fetch 异常: {}", target, e);
            return "抓取失败：" + e.getMessage();
        }
    }

    // ===================== 内部工具 =====================

    private String currentKey() {
        try {
            return apiKeySupplier.get();
        } catch (RuntimeException e) {
            log.warn("读取联网搜索 API Key 失败", e);
            return null;
        }
    }

    private static String formatResults(String query, JsonNode root) {
        StringBuilder sb = new StringBuilder();
        sb.append("【联网搜索】").append(query).append('\n');
        String answer = root.path("answer").asText("");
        if (!answer.isBlank()) {
            sb.append("摘要：").append(answer.trim()).append("\n\n");
        }
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            sb.append("没有找到相关结果。");
            return sb.toString();
        }
        int i = 1;
        for (JsonNode r : results) {
            sb.append(i++).append(". ").append(r.path("title").asText("(无标题)")).append('\n');
            sb.append("   ").append(r.path("url").asText("")).append('\n');
            String content = r.path("content").asText("");
            if (!content.isBlank()) {
                if (content.length() > SNIPPET_CHARS) {
                    content = content.substring(0, SNIPPET_CHARS) + "…";
                }
                sb.append("   ").append(content.replace("\n", " ").trim()).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** 粗略 HTML→文本：去 script/style，剥标签，折叠空白，解码常见实体。 */
    private static String htmlToText(String html) {
        if (html == null) {
            return "";
        }
        String s = html
                .replaceAll("(?is)<(script|style|noscript|svg|template)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)</(p|div|li|h[1-6]|tr|section|article)>", "\n")
                .replaceAll("(?is)<[^>]+>", " ");
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private static int parseInt(String raw, int def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
