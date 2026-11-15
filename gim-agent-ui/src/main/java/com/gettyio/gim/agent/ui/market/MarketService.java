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

package com.gettyio.gim.agent.ui.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gettyio.gim.agent.core.net.AddressPolicy;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.McpServerConfig;
import com.gettyio.gim.agent.domain.entity.SkillPackage;
import com.gettyio.gim.agent.domain.enums.McpTransportType;
import com.gettyio.gim.agent.tools.skill.SkillService;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.config.McpAdminService;
import com.gettyio.gim.agent.ui.update.UpdateChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;

/**
 * 在线市场服务：从 gim-server-ai 拉取技能 / MCP 市场索引，下载技能包并校验后安装。
 *
 * <p><b>技能市场接口契约</b>（服务端实现见 gim-server-ai / gim-server-admin 市场管理模块）：</p>
 * <pre>
 * GET {base}/ai/market/skills?keyword=可选
 * → {"code":"200","msg":"ok","data":{"items":[{
 *       "skillCode","skillName","description","version","author",
 *       "tags":[],"downloadUrl","fileHash","fileSize","updatedAt","downloadCount","homepage"}]}}
 * </pre>
 *
 * <p><b>MCP 市场</b>直连公共 MCP 目录（默认官方 MCP Registry {@code /v0/servers}），把社区标准
 * 条目（{@code remotes} / {@code packages}）翻译为配置模板，不再依赖 gim-server-ai；源地址可用
 * 系统属性 {@code -Dgim.market.mcp.registry=<baseUrl>} 覆盖为自建/兼容服务。</p>
 *
 * <p>技能基地址复用更新服务的 gim-server-ai 地址（{@code 设置 &gt; 更新} 同一配置项）；
 * 技能包下载沿用 {@link UpdateChecker} 的校验口径：fileHash 64 位十六进制按 SHA-256、32 位按 MD5，
 * 其余长度无法识别则跳过校验。安装链路完全复用 {@link SkillService#importFromMarket(Path)}。</p>
 *
 * @author gogym
 */
public class MarketService {

    private static final Logger log = LoggerFactory.getLogger(MarketService.class);

    private static final String SKILLS_PATH = "/ai/market/skills";
    /** 服务端统一成功码（com.gettyio.common.enums.ResultCode.SUCCESS）。 */
    private static final String CODE_SUCCESS = "200";

    /** MCP 市场公共目录默认源：官方 MCP Registry 服务器列表接口，可用系统属性覆盖。 */
    private static final String REGISTRY_DEFAULT_URL = "https://registry.modelcontextprotocol.io/v0/servers";
    private static final int REGISTRY_PAGE_SIZE = 50;
    private static final int REGISTRY_MAX_PAGES = 5;

    /** MCP 市场源地址设置键（留空回退系统属性/内置默认）。 */
    public static final String KEY_REGISTRY_URL = "app.market.mcp.registryUrl";

    /** 常见 MCP 运行依赖的内置获取指引（依赖检测引导用）。 */
    private static final Map<String, String> DEPENDENCY_HOMEPAGES = Map.ofEntries(
            Map.entry("npx", "https://nodejs.org/"),
            Map.entry("node", "https://nodejs.org/"),
            Map.entry("uvx", "https://docs.astral.sh/uv/getting-started/installation/"),
            Map.entry("uv", "https://docs.astral.sh/uv/getting-started/installation/"),
            Map.entry("docker", "https://www.docker.com/products/docker-desktop/"),
            Map.entry("python", "https://www.python.org/downloads/"),
            Map.entry("python3", "https://www.python.org/downloads/"),
            Map.entry("pip", "https://pip.pypa.io/en/stable/installation/"));

    private final UpdateChecker updateChecker;
    private final SkillService skillService;
    private final McpAdminService mcpAdminService;
    private final GimDatabase db;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** 依赖探测结果缓存：进程生命周期内可执行文件位置变化极少，探测一次即可。 */
    private final Map<String, Boolean> dependencyCache = new ConcurrentHashMap<>();

    public MarketService(UpdateChecker updateChecker, SkillService skillService, McpAdminService mcpAdminService,
                         GimDatabase db) {
        this.updateChecker = updateChecker;
        this.skillService = skillService;
        this.mcpAdminService = mcpAdminService;
        this.db = db;
    }

    // ===================== 数据模型 =====================

    /** 市场技能条目（服务端索引一行）。 */
    public record MarketSkill(String skillCode, String skillName, String description, String version,
                              String author, List<String> tags, String downloadUrl, String fileHash,
                              long fileSize, String updatedAt, long downloadCount, String homepage) {
    }

    /** MCP 条目声明的工具（静态预览用，真实工具清单以连接结果为准）。 */
    public record MarketMcpTool(String name, String description) {
    }

    /** 市场 MCP 条目（服务端索引一行，本质是预填配置模板）。 */
    public record MarketMcp(String serverCode, String serverName, String description, String transportType,
                            String command, List<String> args, Map<String, String> env, String baseUrl,
                            Map<String, String> headers, List<MarketMcpTool> tools, List<String> requires,
                            List<String> tags, boolean official, String homepage,
                            String version, String repoUrl, String updatedAt) {
    }

    // ===================== 配置 =====================

    /** 市场基地址（与更新服务共用 gim-server-ai 配置）。 */
    public String serverBaseUrl() {
        return updateChecker.getServerBaseUrl();
    }

    public boolean isServerConfigured() {
        return updateChecker.isServerConfigured();
    }

    // ===================== 索引拉取 =====================

    /**
     * 拉取技能市场索引。
     *
     * @param keyword 搜索词，空则全量
     * @throws IOException 未配置地址、网络或解析失败
     */
    public List<MarketSkill> fetchSkills(String keyword) throws IOException {
        JsonNode data = get(SKILLS_PATH, keyword);
        List<MarketSkill> out = new ArrayList<>();
        for (JsonNode n : data.path("items")) {
            MarketSkill s = new MarketSkill(
                    n.path("skillCode").asText(""),
                    n.path("skillName").asText(""),
                    n.path("description").asText(""),
                    n.path("version").asText(""),
                    n.path("author").asText(""),
                    textList(n.path("tags")),
                    n.path("downloadUrl").asText(""),
                    n.path("fileHash").asText(""),
                    n.path("fileSize").asLong(-1),
                    n.path("updatedAt").asText(""),
                    n.path("downloadCount").asLong(0),
                    n.path("homepage").asText(""));
            if (!s.skillCode().isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    /** 一页 MCP 目录结果：翻译去重后的条目 + 下一页游标（空表示到底）。 */
    public record McpPage(List<MarketMcp> items, String nextCursor) {
    }

    /**
     * 拉取 MCP 市场索引（全量，最多 {@code REGISTRY_MAX_PAGES} 页）：逐页调用
     * {@link #fetchMcpPage} 并按服务器名去重累积。用于一次性取全表的场景；
     * 交互式加载优先用 {@link #fetchMcpPage} 分页。
     */
    public List<MarketMcp> fetchMcp(String keyword) throws IOException {
        Map<String, MarketMcp> acc = new LinkedHashMap<>();
        String cursor = "";
        for (int page = 0; page < REGISTRY_MAX_PAGES; page++) {
            McpPage p = fetchMcpPage(keyword, cursor);
            for (MarketMcp m : p.items()) {
                acc.putIfAbsent(m.serverCode(), m);
            }
            cursor = p.nextCursor();
            if (cursor.isBlank()) {
                break;
            }
        }
        List<MarketMcp> out = new ArrayList<>(acc.values());
        out.sort(Comparator.comparing(MarketMcp::serverName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /**
     * 拉取 MCP 目录的一页：直连公共目录（默认官方 MCP Registry），把社区标准条目翻译成本应用的
     * {@link MarketMcp} 配置模板，同名多版本去重保留最新，并回传下一页游标供懒加载。
     *
     * @param keyword 搜索词，非空时透传注册表 {@code search} 参数
     * @param cursor  分页游标，空表示第一页
     * @throws IOException 网络或解析失败
     */
    public McpPage fetchMcpPage(String keyword, String cursor) throws IOException {
        String base = registryBaseUrl();
        StringBuilder url = new StringBuilder(base)
                .append(base.contains("?") ? "&" : "?")
                .append("limit=").append(REGISTRY_PAGE_SIZE);
        if (keyword != null && !keyword.isBlank()) {
            url.append("&search=").append(URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8));
        }
        if (cursor != null && !cursor.isBlank()) {
            url.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }
        JsonNode root = registryGet(url.toString());
        // 服务器名 → 已保留条目：同名仅保留 isLatest 或语义版本最高的一条
        Map<String, RegistryEntry> best = new LinkedHashMap<>();
        for (JsonNode wrapper : root.path("servers")) {
            JsonNode server = wrapper.path("server");
            String name = server.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            MarketMcp mcp = toMarketMcp(wrapper, server);
            if (mcp == null) {
                continue;
            }
            String version = server.path("version").asText("");
            boolean latest = wrapper.path("_meta")
                    .path("io.modelcontextprotocol.registry/official").path("isLatest").asBoolean(false);
            RegistryEntry prev = best.get(name);
            if (prev == null || preferNewer(latest, version, prev.latest(), prev.version())) {
                best.put(name, new RegistryEntry(mcp, version, latest));
            }
        }
        List<MarketMcp> items = new ArrayList<>();
        for (RegistryEntry e : best.values()) {
            items.add(e.mcp());
        }
        items.sort(Comparator.comparing(MarketMcp::serverName, String.CASE_INSENSITIVE_ORDER));
        String next = root.path("metadata").path("nextCursor").asText("");
        return new McpPage(items, next);
    }

    /** 注册表条目：MarketMcp 加去重所需的版本号与 isLatest 标记。 */
    private record RegistryEntry(MarketMcp mcp, String version, boolean latest) {
    }

    /** 同名多版本择优：{@code isLatest} 优先，其次语义版本更高者。 */
    private static boolean preferNewer(boolean newLatest, String newVersion, boolean oldLatest, String oldVersion) {
        if (newLatest != oldLatest) {
            return newLatest;
        }
        return UpdateChecker.compareVersions(newVersion, oldVersion) > 0;
    }

    /** MCP 公共目录生效地址：优先级——设置页配置 &gt; 系统属性 {@code gim.market.mcp.registry} &gt; 内置默认。 */
    public String registryBaseUrl() {
        String base = getRegistryUrlConfigured();
        if (base.isEmpty()) {
            base = System.getProperty("gim.market.mcp.registry", "").trim();
        }
        if (base.isEmpty()) {
            base = REGISTRY_DEFAULT_URL;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /** 内置默认源（官方 MCP Registry），供设置页占位提示。 */
    public String defaultRegistryUrl() {
        return REGISTRY_DEFAULT_URL;
    }

    /** 设置页存储的原始配置值（可能为空）；未配置或无库时返回空串。 */
    public String getRegistryUrlConfigured() {
        return db == null ? "" : db.settingDao().getValue(KEY_REGISTRY_URL).orElse("").trim();
    }

    /** 写入 MCP 市场源地址；留空即回退系统属性/内置默认。 */
    public void setRegistryUrl(String url) {
        if (db != null) {
            db.settingDao().upsert(KEY_REGISTRY_URL, url == null ? "" : url.trim(), "MCP 市场源地址");
        }
    }

    /** GET 注册表接口返回原始 JSON 根（公共目录无本应用的 code/data 信封）。 */
    private JsonNode registryGet(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "Jwork/" + UpdateChecker.appVersion())
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + resp.statusCode());
            }
            return mapper.readTree(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        }
    }

    /**
     * 把注册表 server 节点翻译为配置模板：有 {@code remotes} 走远程（{@code sse}→SSE，其余→HTTP，
     * 取 url 作 baseUrl）；否则取 {@code packages[0]} 生成 STDIO 命令（npm→npx、oci/docker→docker、
     * 其余→uvx）并抽 environmentVariables 作 env 模板。两者皆缺失或不可用时返回 null。
     */
    private static MarketMcp toMarketMcp(JsonNode wrapper, JsonNode server) {
        String name = server.path("name").asText("");
        if (name.isBlank()) {
            return null;
        }
        String title = server.path("title").asText("");
        String serverName = title.isBlank() ? name : title;
        String description = server.path("description").asText("");
        String version = server.path("version").asText("");
        String websiteUrl = server.path("websiteUrl").asText("");
        String repoUrl = server.path("repository").path("url").asText("");
        String updatedAt = wrapper.path("_meta")
                .path("io.modelcontextprotocol.registry/official").path("updatedAt").asText("");
        if (updatedAt.length() >= 10) {
            updatedAt = updatedAt.substring(0, 10);
        }
        // 发布者补充的分类标签（最多 3 个），用于卡片展示与本地搜索
        List<String> tags = new ArrayList<>();
        for (JsonNode c : server.path("_meta")
                .path("io.modelcontextprotocol.registry/publisher-provided").path("categories")) {
            String t = c.asText("");
            if (!t.isBlank() && tags.size() < 3 && !tags.contains(t)) {
                tags.add(t);
            }
        }

        String transportType = "STDIO";
        String command = "";
        List<String> args = new ArrayList<>();
        Map<String, String> env = new LinkedHashMap<>();
        String baseUrl = "";
        List<String> requires = new ArrayList<>();

        JsonNode remotes = server.path("remotes");
        JsonNode packages = server.path("packages");
        if (remotes.isArray() && !remotes.isEmpty()) {
            JsonNode r = remotes.get(0);
            String type = r.path("type").asText("").toLowerCase(Locale.ROOT);
            transportType = type.contains("sse") ? "SSE" : "HTTP";
            baseUrl = r.path("url").asText("");
            if (baseUrl.isBlank()) {
                return null;
            }
        } else if (packages.isArray() && !packages.isEmpty()) {
            JsonNode p = packages.get(0);
            String registryType = p.path("registryType").asText("").toLowerCase(Locale.ROOT);
            // 包名在注册表里是 identifier（旧数据可能用 name）
            String pkgName = p.path("identifier").asText(p.path("name").asText(""));
            String pkgVersion = p.path("version").asText("");
            if (pkgName.isBlank()) {
                return null;
            }
            switch (registryType) {
                case "npm" -> {
                    command = "npx";
                    args.add("-y");
                    args.add(pkgVersion.isBlank() ? pkgName : pkgName + "@" + pkgVersion);
                    requires.add("npx");
                }
                case "oci", "docker" -> {
                    command = "docker";
                    args.add("run");
                    args.add("-i");
                    args.add("--rm");
                    args.add(pkgVersion.isBlank() ? pkgName : pkgName + ":" + pkgVersion);
                    requires.add("docker");
                }
                default -> {
                    command = "uvx";
                    args.add(pkgVersion.isBlank() ? pkgName : pkgName + "==" + pkgVersion);
                    requires.add("uvx");
                }
            }
            for (JsonNode e : p.path("environmentVariables")) {
                String key = e.path("name").asText("");
                if (!key.isBlank()) {
                    env.put(key, e.path("default").asText(""));
                }
            }
        } else {
            return null;
        }

        return new MarketMcp(name, serverName, description, transportType, command, args, env,
                baseUrl, new LinkedHashMap<>(), new ArrayList<>(), requires, tags,
                false, websiteUrl, version, repoUrl, updatedAt);
    }

    // ===================== 技能安装 =====================

    /**
     * 下载市场技能包并安装：临时目录下载 → 校验哈希 → {@link SkillService#importFromMarket(Path)}。
     *
     * @param skill      市场条目（downloadUrl 必填）
     * @param onProgress 下载进度回调（0..1），可为 null
     * @return 落库后的技能记录
     * @throws IOException 下载失败、校验不通过或安装失败
     */
    public SkillPackage installSkill(MarketSkill skill, DoubleConsumer onProgress) throws IOException {
        if (skill == null || skill.downloadUrl() == null || skill.downloadUrl().isBlank()) {
            throw new IOException("市场条目缺少下载地址");
        }
        // 下载地址出自市场接口的响应：被污染的条目把本机指回内网，取回来的字节还要解压成技能
        String refusal = AddressPolicy.refusal(skill.downloadUrl()).orElse(null);
        if (refusal != null) {
            log.warn("市场技能包下载地址被拒绝: {} — {}", skill.downloadUrl(), refusal);
            throw new IOException("技能包下载地址不可用：" + refusal);
        }
        Path tmp = Files.createTempDirectory("jwork-market-");
        try {
            Path zip = tmp.resolve(sanitizeFileName(skill.skillCode()) + ".zip");
            download(skill.downloadUrl(), zip, skill.fileSize(), onProgress);
            verifyChecksum(zip, skill.fileHash());
            return skillService.importFromMarket(zip);
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage() == null ? "安装失败" : e.getMessage(), e);
        } finally {
            deleteRecursively(tmp);
        }
    }

    /** 本地是否已安装该市场技能（按 skillCode 匹配）。 */
    public SkillPackage localInstalled(String skillCode) {
        return skillService.list().stream()
                .filter(s -> s.getSkillCode().equalsIgnoreCase(skillCode))
                .findFirst().orElse(null);
    }

    /** 市场版本是否高于本地已装版本（本地未装或版本为空视为可安装）。 */
    public static boolean hasNewerVersion(String localVersion, String marketVersion) {
        if (marketVersion == null || marketVersion.isBlank()) {
            return false;
        }
        if (localVersion == null || localVersion.isBlank()) {
            return true;
        }
        return UpdateChecker.compareVersions(marketVersion, localVersion) > 0;
    }

    // ===================== MCP 一键添加 =====================

    /** 本地是否已登记该 MCP 服务（按 serverCode 匹配）。 */
    public McpServerConfig localMcp(String serverCode) {
        return mcpAdminService.list().stream()
                .filter(m -> m.getServerCode().equalsIgnoreCase(serverCode))
                .findFirst().orElse(null);
    }

    /**
     * 把市场条目登记为 MCP 服务（保存逻辑复用 {@link McpAdminService#save}，会一并通知
     * 引用它的智能体运行时重建）。已存在同编码时覆盖配置。
     */
    public McpServerConfig addMcp(MarketMcp m) throws IOException {
        McpServerConfig cfg = new McpServerConfig();
        cfg.setServerCode(m.serverCode());
        cfg.setServerName(m.serverName() == null || m.serverName().isBlank() ? m.serverCode() : m.serverName());
        cfg.setTransportType(McpTransportType.fromCode(m.transportType()).name());
        cfg.setCommand(m.command());
        cfg.setArgsJson(mapper.writeValueAsString(m.args()));
        cfg.setEnvJson(mapper.writeValueAsString(m.env()));
        cfg.setBaseUrl(m.baseUrl());
        cfg.setHeadersJson(mapper.writeValueAsString(m.headers()));
        cfg.setTimeout(30);
        cfg.setEnabled(1);
        cfg.setStatus("UNKNOWN");
        mcpAdminService.save(cfg);
        return cfg;
    }

    // ===================== 依赖检测 =====================

    /**
     * 探测 MCP 运行依赖（npx / uvx / docker 等）是否在本机 PATH 中就绪。
     * 结果按依赖名缓存，探测命令为 {@code where}（Windows）/ {@code which}（其余平台）。
     */
    public Map<String, Boolean> probeDependencies(List<String> requires) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (requires == null) {
            return out;
        }
        for (String dep : requires) {
            if (dep == null || dep.isBlank()) {
                continue;
            }
            String key = dep.trim().toLowerCase(Locale.ROOT);
            out.put(key, dependencyCache.computeIfAbsent(key, MarketService::probeOne));
        }
        return out;
    }

    private static boolean probeOne(String name) {
        try {
            String probe = "windows".equals(UpdateChecker.detectPlatform()) ? "where" : "which";
            Process p = new ProcessBuilder(probe, name).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("依赖探测失败: {} ({})", name, e.getMessage());
            return false;
        }
    }

    /** 依赖名 → 获取指引网页（未知依赖返回空串，UI 不显示「获取」入口）。 */
    public static String dependencyHomepage(String dependency) {
        return DEPENDENCY_HOMEPAGES.getOrDefault(dependency == null ? "" : dependency.trim().toLowerCase(Locale.ROOT), "");
    }

    // ===================== HTTP 内部实现 =====================

    /** GET 索引接口并校验业务码，返回 {@code data} 节点。 */
    private JsonNode get(String path, String keyword) throws IOException {
        String base = serverBaseUrl();
        if (base.isEmpty()) {
            throw new IOException(I18n.t("market.notConfigured"));
        }
        String url = base + path + (keyword == null || keyword.isBlank()
                ? "" : "?keyword=" + URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + resp.statusCode());
            }
            JsonNode root = mapper.readTree(resp.body());
            String code = root.path("code").asText("");
            if (!CODE_SUCCESS.equals(code)) {
                throw new IOException(root.path("msg").asText("code=" + code));
            }
            return root.path("data");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        }
    }

    /** 下载到本地文件并按总量上报进度；总量未知（-1）时不上报中间进度。 */
    private void download(String url, Path target, long size, DoubleConsumer onProgress) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        AtomicLong done = new AtomicLong();
        try (InputStream in = http.send(req, HttpResponse.BodyHandlers.ofInputStream()).body();
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
                long acc = done.addAndGet(read);
                if (onProgress != null && size > 0) {
                    onProgress.accept(Math.min(1.0, (double) acc / size));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", e);
        }
        if (onProgress != null) {
            onProgress.accept(1.0);
        }
    }

    /**
     * 校验下载包完整性：按哈希串长度判别算法——64 位十六进制按 SHA-256、32 位按 MD5、
     * 其他长度跳过校验（与 {@link UpdateChecker} 同口径）。校验不通过删文件并抛异常。
     */
    private static void verifyChecksum(Path file, String hash) throws IOException {
        String h = hash == null ? "" : hash.trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty()) {
            return;
        }
        String actual = switch (h.length()) {
            case 64 -> sha256Hex(file);
            case 32 -> md5Hex(file);
            default -> "";
        };
        if (actual.isEmpty()) {
            log.warn("无法识别的市场包校验值长度({}), 跳过校验", h.length());
            return;
        }
        if (!h.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(file);
            throw new IOException(I18n.t("settings.update.checksumMismatch"));
        }
    }

    private static String sha256Hex(Path file) throws IOException {
        return digestHex(file, "SHA-256");
    }

    private static String md5Hex(Path file) throws IOException {
        return digestHex(file, "MD5");
    }

    private static String digestHex(Path file, String algorithm) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) > 0) {
                    md.update(buf, 0, read);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("无法计算校验和", e);
        }
    }

    private static List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) {
                String t = n.asText("");
                if (!t.isBlank()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static String sanitizeFileName(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_-]", "-");
        return s.isBlank() ? "skill" : s;
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.debug("临时目录清理失败: {}", e.getMessage());
        }
    }
}
