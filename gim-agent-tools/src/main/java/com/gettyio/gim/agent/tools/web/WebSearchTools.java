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
import com.gettyio.gim.agent.core.net.AddressPolicy;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
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

    /**
     * {@code web_fetch} 专用客户端：不自动跟随重定向。
     * <p>透明跳转会让地址策略只看得见第一跳，「公网入口 302 到 169.254.169.254」就是这么绕过去的；
     * 抓取循环因此自己逐跳校验（见 {@link #fetchFollowing}）。搜索提供方用的是固定端点，继续走 {@link #HTTP}。</p>
     */
    private static final HttpClient FETCH = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** 提供方编码：Tavily——AI 原生搜索、海外主流，需 API Key。 */
    public static final String PROVIDER_TAVILY = "tavily";
    /** 提供方编码：博查——国内合规（数据不出境），需 API Key。 */
    public static final String PROVIDER_BOCHA = "bocha";
    /** 提供方编码：SearXNG——自建元搜索实例，免 Key，需配置实例地址。 */
    public static final String PROVIDER_SEARXNG = "searxng";

    private static final String TAVILY_ENDPOINT = "https://api.tavily.com/search";
    private static final String BOCHA_ENDPOINT = "https://api.bochaai.com/v1/web-search";
    /** web_fetch 默认返回上限（字符）：与框架内置 web_fetch 同口径，够读完一篇长文。 */
    private static final int DEFAULT_FETCH_CHARS = 20_000;
    /** web_fetch 硬上限（字符）：再长就该用 prompt 选段，而不是整页灌进上下文。 */
    private static final int MAX_FETCH_CHARS = 100_000;
    /** 单次抓取最多读多少字节：防住把几十兆的下载文件读进内存。 */
    private static final int MAX_PAGE_BYTES = 4_000_000;
    /** 自己跟随的重定向跳数上限：再多的跳转链一般是撞球，不是在给用户读网页。 */
    private static final int MAX_REDIRECT_HOPS = 5;
    /** 单条搜索结果的摘要截断上限（字符）。 */
    private static final int SNIPPET_CHARS = 400;

    /** 提供当前提供方编码（空白/异常回退 Tavily）；由装配方注入。 */
    private final Supplier<String> providerSupplier;
    /** 提供明文 API Key（空白表示未配置）；由装配方注入，通常带解密逻辑。 */
    private final Supplier<String> apiKeySupplier;
    /** 提供自定义实例地址（当前仅 SearXNG 使用；空白表示未配置）；由装配方注入。 */
    private final Supplier<String> baseUrlSupplier;
    /**
     * 地址判定入口，默认 {@link AddressPolicy#refusal(String)}。
     * <p>留成实例字段只为可测：单测的本地测试服务器本身就落在回环段，逐跳校验要覆盖就必须能换桩。
     * 生产装配不传第 4 个参数，走的仍是全局策略与设置里的开关。</p>
     */
    private final Function<String, Optional<String>> addressPolicy;

    public WebSearchTools(Supplier<String> providerSupplier, Supplier<String> apiKeySupplier,
                          Supplier<String> baseUrlSupplier) {
        this(providerSupplier, apiKeySupplier, baseUrlSupplier, AddressPolicy::refusal);
    }

    WebSearchTools(Supplier<String> providerSupplier, Supplier<String> apiKeySupplier,
                   Supplier<String> baseUrlSupplier, Function<String, Optional<String>> addressPolicy) {
        this.providerSupplier = providerSupplier;
        this.apiKeySupplier = apiKeySupplier;
        this.baseUrlSupplier = baseUrlSupplier;
        this.addressPolicy = addressPolicy;
    }

    /** 工具入口（{@code web_search}）：校验入参后按当前提供方分派搜索，返回标题/URL/摘要清单；失败信息以文本返回。 */
    @Tool(name = "web_search", readOnly = true,
            description = "联网搜索实时信息（新闻、事实、价格、文档、最新版本等模型知识截止后的内容）。"
                    + "返回若干条结果，每条含标题、URL 与摘要。需要精确出处时再对某条 URL 调 web_fetch 读正文。")
    public String webSearch(
            @ToolParam(name = "query", description = "搜索关键词或问题（尽量具体）") String query,
            @ToolParam(name = "max_results", required = false,
                    description = "返回结果条数，1-10，默认 5") Integer maxResults) {
        if (query == null || query.isBlank()) {
            return "query 不能为空。";
        }
        int n = clamp(maxResults == null ? 5 : maxResults, 1, 10);
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

    /** 工具入口（{@code web_fetch}）：抓页面 → 抽正文 → 按 prompt 选段或按上限截断；失败信息以文本返回。 */
    @Tool(name = "web_fetch", readOnly = true,
            description = "抓取 HTTP(S) 网页并返回正文：已做正文抽取（去掉导航、侧栏、评论、相关推荐，"
                    + "保留标题与段落，标题行以 # 标记），并给出重定向后的最终地址。"
                    + "想知道页面里的某些信息时传 prompt，工具只回最相关的几段；不传则按 max_chars 从正文开头截断。")
    public String webFetch(
            @ToolParam(name = "url", description = "要抓取的网页地址（http/https）") String url,
            @ToolParam(name = "prompt", required = false,
                    description = "想从页面里得到什么（如「退款订单的统计口径」）；给了就只回相关段落")
                    String prompt,
            @ToolParam(name = "max_chars", required = false,
                    description = "最多返回多少字符（默认 20000，上限 100000）") Integer maxChars) {
        if (url == null || url.isBlank()) {
            return "url 不能为空。";
        }
        String target = url.trim();
        String refusal = addressPolicy.apply(target).orElse(null);
        if (refusal != null) {
            log.warn("web_fetch 地址被拒绝: {} — {}", target, refusal);
            return "抓取被拒绝：" + refusal;
        }
        int limit = maxChars != null && maxChars > 0
                ? Math.min(maxChars, MAX_FETCH_CHARS) : DEFAULT_FETCH_CHARS;
        try {
            return fetchFollowing(target, prompt, limit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "抓取被中断。";
        } catch (Exception e) {
            log.warn("web_fetch 异常: {}", target, e);
            return "抓取失败：" + e.getMessage();
        }
    }

    /**
     * 逐跳抓取：每一跳都先过 {@link AddressPolicy} 再发请求，正文只从最后一跳读。
     * <p>目标地址出自模型（常被搜索结果或网页正文里的链接牵着走），所以既拦第一跳的内网地址，
     * 也拦「公网页面 302 到内网」这种借道；https→http 降级与超出跳数上限都不自动跟随，
     * 如实把 Location 交回模型，让它自己决定要不要再发一次工具调用。</p>
     */
    private String fetchFollowing(String target, String prompt, int limit) throws Exception {
        String current = target;
        for (int hop = 0; ; hop++) {
            HttpResponse<InputStream> resp = FETCH.send(request(current), HttpResponse.BodyHandlers.ofInputStream());
            int status = resp.statusCode();
            String finalUrl = resp.uri().toString();
            if (status >= 400) {
                resp.body().close();
                return "抓取失败：HTTP " + status + "（" + finalUrl + "）";
            }
            if (status < 300) {
                try (InputStream in = resp.body()) {
                    byte[] raw = in.readNBytes(MAX_PAGE_BYTES);
                    String contentType = resp.headers().firstValue("content-type").orElse("");
                    return renderFetch(decode(raw, contentType), contentType, target, finalUrl,
                            prompt, limit, raw.length >= MAX_PAGE_BYTES);
                }
            }
            String location = resp.headers().firstValue("location").orElse("");
            resp.body().close();
            if (!isFollowable(status)) {
                return "未跟随的重定向（HTTP " + status + "）：" + (location.isBlank() ? finalUrl : location)
                        + "\n这类状态要显式指定目标，请改用该地址重试。";
            }
            URI next = resolveLocation(current, location);
            if (next == null) {
                return "重定向没有给出可用目标（HTTP " + status + "）：" + finalUrl;
            }
            if (isDowngrade(current, next)) {
                return "未跟随的重定向（HTTP " + status + "）：" + next
                        + "\n不做 https→http 降级，请改用该地址重试。";
            }
            String hopRefusal = addressPolicy.apply(next.toString()).orElse(null);
            if (hopRefusal != null) {
                log.warn("web_fetch 跳转目标被拒绝: {} — {}", next, hopRefusal);
                return "重定向被拒绝：" + hopRefusal;
            }
            if (hop == MAX_REDIRECT_HOPS) {
                return "未跟随的重定向（HTTP " + status + "）：" + next
                        + "\n跳转已超过 " + MAX_REDIRECT_HOPS + " 次，请改用该地址重试。";
            }
            current = next.toString();
        }
    }

    /**
     * 只自动跟随规范的跳转语义。
     * <p>300 是「多个候选，由发起方选」，静默挑一个等于替用户决定；304 属缓存，本就没有正文。</p>
     */
    private static boolean isFollowable(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** 抓取用的 GET 请求（统一 UA 与 Accept，服务端据此返回正文而非图片占位）。 */
    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (Jwork Desktop)")
                .header("Accept", "text/html,application/xhtml+xml,text/plain,application/json")
                .GET()
                .build();
    }

    /** 把 {@code Location} 按当前地址解析成绝对 URI；缺失、畸形或非层级地址返回 {@code null}。 */
    private static URI resolveLocation(String current, String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        try {
            URI resolved = URI.create(current).resolve(location.strip());
            return resolved.isAbsolute() && resolved.getHost() != null ? resolved : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** https 页面跳回 http：降级由用户（或模型显式再调一次）决定，不静默跟随。 */
    private static boolean isDowngrade(String current, URI next) {
        return current.regionMatches(true, 0, "https://", 0, 8) && "http".equalsIgnoreCase(next.getScheme());
    }

    /**
     * 渲染抓取结果：HTML 走正文抽取，纯文本/JSON 原样给出，其余类型明确说明读不了。
     * 头部固定给出标题与最终地址（重定向时连原地址一起），正文按 prompt 选段或按上限截断。
     */
    private static String renderFetch(String body, String contentType, String target, String finalUrl,
                                      String prompt, int limit, boolean byteCapped) {
        String text;
        String title = "";
        if (isHtml(contentType, body)) {
            HtmlArticleExtractor.Article article = HtmlArticleExtractor.extract(body);
            title = article.title();
            text = article.text();
        } else if (contentType.contains("text") || contentType.contains("json")
                || contentType.contains("xml") || contentType.isBlank()) {
            text = body.strip();
        } else {
            return "该地址不是网页正文（Content-Type: " + contentType + "）：" + finalUrl
                    + "\n需要保存这类文件时，用 execute 下载后再按文件类型处理。";
        }
        if (text.isBlank()) {
            return "页面没有可抽取的正文（可能是纯脚本渲染，或站点返回了空内容）：" + finalUrl;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【网页正文】").append(title.isBlank() ? finalUrl : title).append('\n');
        sb.append("地址：").append(finalUrl);
        if (!finalUrl.equals(target)) {
            sb.append("（从 ").append(target).append(" 跳转）");
        }
        if (byteCapped) {
            sb.append("（响应已按 ").append(MAX_PAGE_BYTES / 1024).append("KB 截断）");
        }
        sb.append('\n');
        if (prompt != null && !prompt.isBlank()) {
            PassageSelector.Selection picked = PassageSelector.select(text, prompt, limit);
            if (picked.matched() > 0) {
                sb.append("\n与「").append(prompt.strip()).append("」相关的段落（正文 ")
                        .append(picked.total()).append(" 段，取回 ").append(picked.matched())
                        .append(" 段，按原文顺序）：\n").append(picked.text());
                return sb.toString();
            }
            sb.append("\n（正文里没有与该 prompt 直接相关的段落，以下按开头给出。）\n");
        }
        sb.append('\n');
        if (text.length() > limit) {
            sb.append(text, 0, limit).append("\n…（正文共 ").append(text.length())
                    .append(" 字符，已截断到 ").append(limit)
                    .append("；想看特定内容请带 prompt，或调大 max_chars）");
        } else {
            sb.append(text);
        }
        return sb.toString();
    }

    /** 是否按 HTML 处理：Content-Type 说了算，没有头时看正文开头像不像标记语言。 */
    private static boolean isHtml(String contentType, String body) {
        if (contentType.contains("html")) {
            return true;
        }
        if (!contentType.isBlank() && !contentType.contains("text")) {
            return false;
        }
        String head = body.stripLeading();
        return head.regionMatches(true, 0, "<", 0, 1) || head.regionMatches(true, 0, "<!doctype", 0, 9);
    }

    /**
     * 按页面真实编码解码：Content-Type 的 {@code charset} 优先，其次 HTML 里的 meta charset，
     * 都不认识才用 UTF-8——GBK/GB2312 的国内站点不少，硬按 UTF-8 读会整页乱码。
     */
    private static String decode(byte[] raw, String contentType) {
        Charset charset = charsetOf(contentTypeParam(contentType, "charset"), raw);
        return new String(raw, charset);
    }

    private static Charset charsetOf(String fromHeader, byte[] raw) {
        Charset direct = supported(fromHeader);
        if (direct != null) {
            return direct;
        }
        String head = new String(raw, 0, Math.min(raw.length, 2048), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        int at = head.indexOf("charset=");
        if (at >= 0) {
            Charset meta = supported(valueUpToDelimiters(head.substring(at + "charset=".length())));
            if (meta != null) {
                return meta;
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** 取响应头里的参数值：{@code text/html; charset=utf-8} 里的 charset。 */
    private static String contentTypeParam(String header, String name) {
        return header == null || header.isBlank() ? null : parameterOf(header, name);
    }

    private static String parameterOf(String header, String name) {
        String lower = header.toLowerCase(Locale.ROOT);
        int at = lower.indexOf(name + "=");
        if (at < 0) {
            return null;
        }
        return valueUpToDelimiters(header.substring(at + name.length() + 1));
    }

    private static String valueUpToDelimiters(String raw) {
        String v = raw.strip();
        int cut = v.length();
        for (char stop : new char[] {';', ' ', '"', '\'', '>', '\t', '\n', '\r', '/'}) {
            int at = v.indexOf(stop);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        return v.substring(0, cut);
    }

    private static Charset supported(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Charset.forName(name.trim());
        } catch (RuntimeException e) {
            return null;
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

    /** 收进 [lo, hi] 区间。 */
    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
