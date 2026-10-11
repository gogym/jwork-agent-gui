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

package com.gettyio.gim.agent.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 面向 Agent 的联网工具：{@code web_search}（提供方三选一，见 {@link #PROVIDER_TAVILY} 等常量）
 * 与 {@code web_fetch}（抓取网页正文）。
 * <p>由 {@code HarnessAgentGateway} 注册进 Toolkit，每个智能体一份实例。</p>
 * <p>提供方 / API Key / 实例地址均不内置在本类，而是由装配方注入 {@link Supplier}（通常从加密的
 * {@code settings} 读取并解密），运行时实时取用——用户在设置页改配无需重启即生效。未配置时，
 * 工具返回明确的「未配置」提示而非抛异常，让模型据实告知用户去配置，而不是把技术栈细节暴露成对话内容。</p>
 *
 * @author gogym
 */
public class WebSearchTools {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 提供方编码：Tavily——AI 原生搜索、海外主流，需 API Key。 */
    public static final String PROVIDER_TAVILY = "tavily";
    /** 提供方编码：博查——国内合规（数据不出境），需 API Key。 */
    public static final String PROVIDER_BOCHA = "bocha";
    /** 提供方编码：SearXNG——自建元搜索实例，免 Key，需配置实例地址。 */
    public static final String PROVIDER_SEARXNG = "searxng";

    private static final String TAVILY_ENDPOINT = "https://api.tavily.com/search";
    private static final String BOCHA_ENDPOINT = "https://api.bochaai.com/v1/web-search";
    /** 抓取正文的截断上限（字符），避免超长页面撑爆上下文。 */
    private static final int MAX_FETCH_CHARS = 6000;
    /** 单条搜索结果的摘要截断上限（字符）。 */
    private static final int SNIPPET_CHARS = 400;

    /** 提供当前提供方编码（空白/异常回退 Tavily）；由装配方注入。 */
    private final Supplier<String> providerSupplier;
    /** 提供明文 API Key（空白表示未配置）；由装配方注入，通常带解密逻辑。 */
    private final Supplier<String> apiKeySupplier;
    /** 提供自定义实例地址（当前仅 SearXNG 使用；空白表示未配置）；由装配方注入。 */
    private final Supplier<String> baseUrlSupplier;

    public WebSearchTools(Supplier<String> providerSupplier, Supplier<String> apiKeySupplier,
                          Supplier<String> baseUrlSupplier) {
        this.providerSupplier = providerSupplier;
        this.apiKeySupplier = apiKeySupplier;
        this.baseUrlSupplier = baseUrlSupplier;
    }

    /** 工具入口（{@code web_search}）：校验入参后按当前提供方分派搜索，返回标题/URL/摘要清单；失败信息以文本返回。 */
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
        int n = clamp(parseInt(maxResults, 5), 1, 10);
        return switch (currentProvider()) {
            case PROVIDER_BOCHA -> searchBocha(query.trim(), n);
            case PROVIDER_SEARXNG -> searchSearxng(query.trim(), n);
            default -> searchTavily(query.trim(), n);
        };
    }

    /** Tavily 分支：校验 Key 后调官方搜索接口；失败信息以文本返回。 */
    private String searchTavily(String query, int n) {
        String key = currentKey();
        if (key == null || key.isBlank()) {
            return "联网搜索未配置：请在「设置 > 网络 > 联网搜索」填写 Tavily API Key 后重试。";
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("query", query);
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
                log.warn("web_search(tavily) 非 2xx: status={}", resp.statusCode());
                return "搜索失败：服务返回 HTTP " + resp.statusCode()
                        + (resp.statusCode() == 401 || resp.statusCode() == 403 ? "（API Key 无效或额度不足）" : "");
            }
            return formatTavilyResults(query, MAPPER.readTree(resp.body()), n);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "搜索被中断。";
        } catch (Exception e) {
            log.warn("web_search(tavily) 异常", e);
            return "搜索失败：" + e.getMessage();
        }
    }

    /** 博查分支：校验 Key 后调 Web Search API（响应为 Bing 兼容结构）；失败信息以文本返回。 */
    private String searchBocha(String query, int n) {
        String key = currentKey();
        if (key == null || key.isBlank()) {
            return "联网搜索未配置：请在「设置 > 网络 > 联网搜索」填写博查 API Key 后重试。";
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("query", query);
            body.put("count", n);
            body.put("summary", true);
            body.put("freshness", "noLimit");
            HttpRequest req = HttpRequest.newBuilder(URI.create(BOCHA_ENDPOINT))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                log.warn("web_search(bocha) 非 2xx: status={}", resp.statusCode());
                return "搜索失败：服务返回 HTTP " + resp.statusCode()
                        + (resp.statusCode() == 401 || resp.statusCode() == 403 ? "（API Key 无效或额度不足）" : "");
            }
            JsonNode root = MAPPER.readTree(resp.body());
            int code = root.path("code").asInt(200);
            if (code != 200) {
                return "搜索失败：博查返回错误 " + code + "：" + root.path("msg").asText("未知错误");
            }
            return formatBochaResults(query, root, n);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "搜索被中断。";
        } catch (Exception e) {
            log.warn("web_search(bocha) 异常", e);
            return "搜索失败：" + e.getMessage();
        }
    }

    /** SearXNG 分支：校验实例地址后以 JSON 格式查询（免 Key，实例需启用 json 输出）；失败信息以文本返回。 */
    private String searchSearxng(String query, int n) {
        String base = currentBaseUrl();
        if (base.isBlank()) {
            return "联网搜索未配置：请在「设置 > 网络 > 联网搜索」填写 SearXNG 实例地址后重试。";
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return "SearXNG 实例地址需以 http:// 或 https:// 开头：" + base;
        }
        String endpoint = base + "/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&format=json";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Jwork Desktop)")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                log.warn("web_search(searxng) 非 2xx: status={} base={}", resp.statusCode(), base);
                return "搜索失败：实例返回 HTTP " + resp.statusCode()
                        + (resp.statusCode() == 403
                        ? "（实例可能未启用 json 输出：需在 settings.yml 的 search.formats 中加入 json）" : "");
            }
            return formatSearxngResults(query, MAPPER.readTree(resp.body()), n);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "搜索被中断。";
        } catch (Exception e) {
            log.warn("web_search(searxng) 异常: {}", base, e);
            return "搜索失败：" + e.getMessage();
        }
    }

    /** 工具入口（{@code web_fetch}）：仅收 http/https，抓取后抽正文纯文本并按上限截断；失败信息以文本返回。 */
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

    /** 当前提供方编码：空白或读取异常回退 Tavily，未识别的编码由分派侧走默认分支。 */
    private String currentProvider() {
        try {
            String raw = providerSupplier.get();
            if (raw == null) {
                return PROVIDER_TAVILY;
            }
            String norm = raw.trim().toLowerCase(Locale.ROOT);
            return norm.isEmpty() ? PROVIDER_TAVILY : norm;
        } catch (RuntimeException e) {
            log.warn("读取联网搜索提供方失败", e);
            return PROVIDER_TAVILY;
        }
    }

    /** 当前实例地址：去首尾空白与尾部斜杠；空白或读取异常返回空串。 */
    private String currentBaseUrl() {
        try {
            String raw = baseUrlSupplier.get();
            if (raw == null) {
                return "";
            }
            String trimmed = raw.trim();
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed;
        } catch (RuntimeException e) {
            log.warn("读取联网搜索实例地址失败", e);
            return "";
        }
    }

    /** Tavily 结果渲染：answer 摘要 + results 清单（title/url/content）。 */
    private static String formatTavilyResults(String query, JsonNode root, int max) {
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
        appendEntries(sb, results, "title", "url", "content", max);
        return sb.toString().trim();
    }

    /** 博查结果渲染：Bing 兼容结构 data.webPages.value[]（name/url，摘要优先长文 summary、回退 snippet）。 */
    private static String formatBochaResults(String query, JsonNode root, int max) {
        StringBuilder sb = new StringBuilder();
        sb.append("【联网搜索】").append(query).append('\n');
        JsonNode value = root.path("data").path("webPages").path("value");
        if (!value.isArray() || value.isEmpty()) {
            sb.append("没有找到相关结果。");
            return sb.toString();
        }
        int i = 1;
        for (JsonNode r : value) {
            if (i > max) {
                break;
            }
            sb.append(i++).append(". ").append(r.path("name").asText("(无标题)")).append('\n');
            sb.append("   ").append(r.path("url").asText("")).append('\n');
            String content = r.path("summary").asText("");
            if (content.isBlank()) {
                content = r.path("snippet").asText("");
            }
            appendSnippet(sb, content);
        }
        return sb.toString().trim();
    }

    /** SearXNG 结果渲染：answers 首条可作摘要 + results 清单（title/url/content）。 */
    private static String formatSearxngResults(String query, JsonNode root, int max) {
        StringBuilder sb = new StringBuilder();
        sb.append("【联网搜索】").append(query).append('\n');
        JsonNode answers = root.path("answers");
        if (answers.isArray() && !answers.isEmpty()) {
            sb.append("摘要：").append(answers.get(0).asText("").trim()).append("\n\n");
        }
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            sb.append("没有找到相关结果。");
            return sb.toString();
        }
        appendEntries(sb, results, "title", "url", "content", max);
        return sb.toString().trim();
    }

    /** 通用结果清单渲染：按字段名取标题/URL/摘要，最多 max 条。 */
    private static void appendEntries(StringBuilder sb, JsonNode results, String titleField,
                                      String urlField, String contentField, int max) {
        int i = 1;
        for (JsonNode r : results) {
            if (i > max) {
                break;
            }
            sb.append(i++).append(". ").append(r.path(titleField).asText("(无标题)")).append('\n');
            sb.append("   ").append(r.path(urlField).asText("")).append('\n');
            appendSnippet(sb, r.path(contentField).asText(""));
        }
    }

    /** 追加一条缩进摘要行：折叠换行并按上限截断；空白内容跳过。 */
    private static void appendSnippet(StringBuilder sb, String content) {
        if (content.isBlank()) {
            return;
        }
        if (content.length() > SNIPPET_CHARS) {
            content = content.substring(0, SNIPPET_CHARS) + "…";
        }
        sb.append("   ").append(content.replace("\n", " ").trim()).append('\n');
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
