package com.gettyio.gim.agent.ui.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.notify.TrayNotifier;
import com.gettyio.gim.agent.ui.window.GimDialog;
import javafx.application.Platform;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * 更新检查：向 gim-server-ai 查询最新发布版本并与本应用语义化版本比对，支持应用内下载安装。
 * <ul>
 *   <li><b>服务器地址</b>：内置生产默认基地址（编译期常量 {@link #DEFAULT_BASE_URL}），
 *       设置页可覆盖，覆盖值持久化在 AppSetting {@code app.update.serverBaseUrl}（留空即回退内置默认）；
 *       更新检查接口路径固定为 {@code /ai/update/check}（服务端已免鉴权，无需登录态）。</li>
 *   <li><b>当前版本</b>取自 classpath 资源 {@code version.properties}（{@code app.version}）；比较采用
 *       点分段语义化算法，与服务端 {@code VersionUtil} 保持一致。</li>
 *   <li><b>平台/架构</b>：由 {@code os.name}/{@code os.arch} 探测为 windows/macos/linux × x64/arm64。</li>
 *   <li><b>手动检查</b>：{@link #checkLatest()}；命中新版后可 {@link #download} 下载、{@link #install} 拉起安装。</li>
 *   <li><b>静默检查</b>：{@link #maybeCheckOnStartup(javafx.stage.Window)} 在开关开启且当日未查过时后台执行；强更弹模态，否则仅发托盘气泡。</li>
 * </ul>
 */
public class UpdateChecker {

    private static final Logger log = LoggerFactory.getLogger(UpdateChecker.class);

    public static final String KEY_AUTO_CHECK = "app.update.autoCheck";
    private static final String KEY_LAST_CHECK = "app.update.lastCheckDate";
    public static final String KEY_BASE_URL = "app.update.serverBaseUrl";

    /** 应用编码，须与统一管理后台（gim-server-admin）中对应 {@code app_info.app_id} 一致。 */
    private static final String APP_ID = "jwork-desktop";
    private static final String CHECK_PATH = "/ai/update/check";
    /** 服务端统一成功码（com.gettyio.common.enums.ResultCode.SUCCESS）。 */
    private static final String CODE_SUCCESS = "200";
    /** 强制更新下载失败的最大重试次数，超出后兜底为打开下载页/退出。 */
    private static final int MAX_DOWNLOAD_RETRY = 3;

    private final GimDatabase db;
    private final TrayNotifier trayNotifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 版本缓存（静态：version.properties 进程内单次读取，静态/实例入口共享）。 */
    private static volatile String cachedVersion;

    /** 内置生产更新服务基地址（gim-server-ai）：用户未在设置页手动配置时作为默认。 */
    private static final String DEFAULT_BASE_URL = "https://api.gettyio.com";

    public UpdateChecker(GimDatabase db, TrayNotifier trayNotifier) {
        this.db = db;
        this.trayNotifier = trayNotifier;
    }

    /**
     * 一次更新检查结果。
     *
     * @param version     最新版本号
     * @param downloadUrl 安装包下载地址
     * @param fileSize    安装包字节大小（未知为 -1）
     * @param fileHash    MD5 校验值（可为空）
     * @param updateDesc  更新说明（可为空）
     * @param forceUpdate 是否强制更新
     */
    public record UpdateInfo(String version, String downloadUrl, long fileSize,
                             String fileHash, String updateDesc, boolean forceUpdate) {
    }

    // ===================== 配置 =====================

    /** 读取生效的服务器基地址（去除结尾斜杠）：优先用户配置，未配置时回退内置默认；两者皆空返回空串。 */
    public String getServerBaseUrl() {
        String configured = db.settingDao().getValue(KEY_BASE_URL).orElse("").trim();
        if (!configured.isEmpty()) {
            return trimTrailingSlash(configured);
        }
        return trimTrailingSlash(DEFAULT_BASE_URL);
    }

    public void setServerBaseUrl(String url) {
        db.settingDao().upsert(KEY_BASE_URL, url == null ? "" : url.trim(), "gim-server-ai 更新服务地址");
    }

    public boolean isServerConfigured() {
        return !getServerBaseUrl().isEmpty();
    }

    // ===================== 当前版本 =====================

    /** 当前应用版本（归一化，去 v 前缀与 -SNAPSHOT 后缀）；读不到时回退 0.0.0。 */
    public String currentVersion() {
        return appVersion();
    }

    /** 应用版本静态入口：无实例场景（关于对话框等）直接读取；结果进程内缓存。 */
    public static String appVersion() {
        String v = cachedVersion;
        if (v == null) {
            v = loadVersion();
            cachedVersion = v;
        }
        return v;
    }

    private static String loadVersion() {
        Properties p = new Properties();
        try (InputStream in = UpdateChecker.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            log.warn("读取 version.properties 失败", e);
        }
        return normalize(p.getProperty("app.version", "0.0.0"));
    }

    /** 归一化版本串：去空白、去前导 v、截断 {@code -}/{@code +} 预发布后缀。供市场「可更新」检测复用。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "0.0.0";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("v")) {
            s = s.substring(1);
        }
        int cut = s.length();
        for (char sep : new char[]{'-', '+'}) {
            int idx = s.indexOf(sep);
            if (idx >= 0) {
                cut = Math.min(cut, idx);
            }
        }
        s = s.substring(0, cut);
        return s.isEmpty() ? "0.0.0" : s;
    }

    /** 语义化版本点分数值比较：a &gt; b 返回正，缺位补 0，非数字段按 0。供市场「可更新」检测复用。 */
    public static int compareVersions(String a, String b) {
        String[] pa = normalize(a).split("\\.");
        String[] pb = normalize(b).split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int ia = i < pa.length ? parseSegment(pa[i]) : 0;
            int ib = i < pb.length ? parseSegment(pb[i]) : 0;
            if (ia != ib) {
                return Integer.compare(ia, ib);
            }
        }
        return 0;
    }

    private static int parseSegment(String seg) {
        try {
            return Integer.parseInt(seg.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ===================== 平台探测 =====================

    /** 探测当前平台：windows / macos / linux。 */
    public static String detectPlatform() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return "windows";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "macos";
        }
        if (name.contains("nux") || name.contains("nix")) {
            return "linux";
        }
        return "";
    }

    /** 探测当前架构：x64 / arm64；未知返回空串。 */
    public static String detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) {
            return "x64";
        }
        return "";
    }

    // ===================== 手动检查 =====================

    /**
     * 请求服务器并比对当前版本。
     *
     * @return 有新版返回其信息；已是最新返回空
     * @throws IOException 未配置地址、网络或解析失败
     */
    public Optional<UpdateInfo> checkLatest() throws IOException {
        String base = getServerBaseUrl();
        if (base.isEmpty()) {
            throw new IOException(I18n.t("settings.update.noServer"));
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("appId", APP_ID);
            body.put("platform", detectPlatform());
            body.put("arch", detectArch());
            body.put("currentVersion", currentVersion());

            HttpRequest req = HttpRequest.newBuilder(URI.create(base + CHECK_PATH))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + resp.statusCode());
            }
            JsonNode root = mapper.readTree(resp.body());
            String code = root.path("code").asText("");
            if (!CODE_SUCCESS.equals(code)) {
                throw new IOException(root.path("msg").asText("code=" + code));
            }
            JsonNode data = root.path("data");
            if (!data.path("hasUpdate").asBoolean(false)) {
                return Optional.empty();
            }
            String url = data.path("downloadUrl").asText("");
            if (url.isBlank()) {
                throw new IOException("响应缺少 downloadUrl");
            }
            return Optional.of(new UpdateInfo(
                    data.path("version").asText(""),
                    url,
                    data.path("fileSize").asLong(-1),
                    data.path("fileHash").asText(""),
                    data.path("updateDesc").asText(""),
                    data.path("forceUpdate").asBoolean(false)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("检查被中断", e);
        }
    }

    // ===================== 应用内下载与安装 =====================

    /**
     * 下载安装包到临时目录并在提供 fileHash（MD5）时校验。
     *
     * @param info     更新信息
     * @param onProgress 进度回调（0..1），可为 null
     * @return 下载后的本地文件
     * @throws IOException 下载失败或校验不通过
     */
    public Path download(UpdateInfo info, DoubleConsumer onProgress) throws IOException {
        if (info == null || info.downloadUrl() == null || info.downloadUrl().isBlank()) {
            throw new IOException(I18n.t("settings.update.noDownloadUrl"));
        }
        Path tempDir = Files.createTempDirectory("jwork-update-");
        Path target = tempDir.resolve(safeFileName(info.downloadUrl()));
        HttpRequest req = HttpRequest.newBuilder(URI.create(info.downloadUrl()))
                .timeout(Duration.ofMinutes(15))
                .GET()
                .build();
        long total = info.fileSize() > 0 ? info.fileSize() : -1;
        AtomicLong done = new AtomicLong();
        try (InputStream in = http.send(req, HttpResponse.BodyHandlers.ofInputStream()).body();
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
                long acc = done.addAndGet(read);
                if (onProgress != null && total > 0) {
                    onProgress.accept(Math.min(1.0, (double) acc / total));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", e);
        }
        if (onProgress != null) {
            onProgress.accept(1.0);
        }
        verifyChecksum(target, info.fileHash());
        return target;
    }

    /**
     * 校验下载包完整性：按哈希串长度判别算法——64 位十六进制视为 SHA-256（三段式更新的标准校验），
     * 32 位视为 MD5（旧服务端兼容）；其他长度视为无法识别，跳过校验（与空 hash 同口径）。
     * 校验不通过时删除本地文件并抛 {@link IOException}。
     */
    void verifyChecksum(Path file, String hash) throws IOException {
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
            log.warn("无法识别的更新包校验值长度({}), 跳过校验", h.length());
            return;
        }
        if (!h.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(file);
            throw new IOException(I18n.t("settings.update.checksumMismatch"));
        }
    }

    /**
     * 三段式静默安装：Windows 且为 .msi 时走 {@code msiexec /i ... /qn /norestart}（无向导、
     * 装完即更新完成），其余平台/格式退回 {@link #install(Path)} 拉起默认安装流程。
     *
     * @return 成功拉起返回 true
     */
    public boolean installSilently(Path file) {
        if (file == null || !Files.exists(file)) {
            return false;
        }
        if ("windows".equals(detectPlatform())
                && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".msi")) {
            try {
                new ProcessBuilder("msiexec", "/i", file.toAbsolutePath().toString(),
                        "/qn", "/norestart").start();
                log.info("静默安装已启动: {}", file);
                return true;
            } catch (Exception e) {
                log.warn("静默安装失败，回退到常规安装: {}", e.getMessage());
            }
        }
        return install(file);
    }

    /**
     * 用系统默认方式拉起安装（Windows 运行安装程序、macOS 挂载 dmg、Linux 依后缀尽力执行）。
     *
     * @return 成功拉起返回 true
     */
    public boolean install(Path file) {
        if (file == null || !Files.exists(file)) {
            return false;
        }
        try {
            file.toFile().setExecutable(true, false);
            String os = detectPlatform();
            if ("linux".equals(os) && isArchiveLike(file.getFileName().toString())) {
                new ProcessBuilder("xdg-open", file.toString()).inheritIO().start();
                return true;
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file.toFile());
                return true;
            }
        } catch (Exception e) {
            log.warn("拉起安装失败: {}", e.getMessage());
        }
        return false;
    }

    /** 在系统浏览器打开下载地址（下载/安装不可用时的回退）。 */
    public boolean openDownloadPage(UpdateInfo info) {
        String url = (info == null || info.downloadUrl() == null || info.downloadUrl().isBlank()) ? null : info.downloadUrl();
        if (url == null) {
            return false;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Exception e) {
            log.warn("打开更新页面失败: {}", e.getMessage());
        }
        return false;
    }

    // ===================== 静默每日检查 =====================

    public boolean isAutoCheckEnabled() {
        return "true".equalsIgnoreCase(db.settingDao().getValue(KEY_AUTO_CHECK).orElse("false").trim());
    }

    public void setAutoCheckEnabled(boolean enabled) {
        db.settingDao().upsert(KEY_AUTO_CHECK, String.valueOf(enabled), "启动后每日静默检查更新");
    }

    /** 上次检查日期（ISO 文本 yyyy-MM-dd）；从未检查返回空串。静默与手动检查均会更新。 */
    public String lastCheckDate() {
        return db.settingDao().getValue(KEY_LAST_CHECK).orElse("").trim();
    }

    /** 记录本次检查日期为今天：供设置页「上次检查」展示，并用于静默检查当日去重。 */
    public void markCheckedToday() {
        db.settingDao().upsert(KEY_LAST_CHECK, LocalDate.now().toString(), "上次检查日期(ISO)");
    }

    /**
     * 启动后调用：开关开启且当日未检查过，则后台静默检查一次。
     * 命中新版时：强制更新弹不可关闭模态走完「下载→安装→退出」；否则仅发托盘气泡。
     * 无论是否命中新版，都记录当日已检查，避免同日重复请求。
     *
     * @param owner 强制更新模态的宿主窗口（通常为主 Stage）；为空时强制更新退化为气泡提醒
     */
    public void maybeCheckOnStartup(Window owner) {
        if (!isAutoCheckEnabled() || !isServerConfigured()) {
            return;
        }
        LocalDate today = LocalDate.now();
        String last = lastCheckDate();
        if (today.toString().equals(last)) {
            return;
        }
        markCheckedToday();
        Thread t = new Thread(() -> {
            try {
                checkLatest().ifPresent(info -> Platform.runLater(() -> {
                    if (info.forceUpdate() && owner != null) {
                        beginForced(owner, info, null);
                    } else {
                        trayNotifier.notify(I18n.t("update.available.title"),
                                I18n.t("update.available.body", currentVersion(), info.version()));
                    }
                }));
            } catch (Exception e) {
                log.debug("静默检查更新失败: {}", e.getMessage());
            }
        }, "gim-update-check");
        t.setDaemon(true);
        t.start();
    }

    // ===================== 强制更新（温和强制） =====================

    /**
     * 强制更新交互流程：以不可关闭模态依次「立即下载 → 安装 → 退出旧版」，用户无法跳过；
     * 发现新版模态与安装模态均以 {@code updateDesc} 作为副标题展示；
     * 下载失败先提示「重试」（最多 {@link #MAX_DOWNLOAD_RETRY} 次），超限后兜底为「打开下载页 / 退出应用」，避免死循环。
     *
     * <p>必须在 JavaFX 应用线程调用（内部使用 {@code showAndWait}）。</p>
     *
     * @param owner  模态宿主窗口
     * @param info   更新信息（须为 forceUpdate）
     * @param status 状态文本回调（可空，如设置页状态标签）
     */
    public void beginForced(Window owner, UpdateInfo info, Consumer<String> status) {
        GimDialog.chooseForced(owner, I18n.t("settings.update.found.title"), descHeader(info),
                I18n.t("settings.update.forced.body", currentVersion(), info.version()),
                I18n.t("settings.update.downloadNow"));
        forcedDownload(owner, info, status, 0);
    }

    /**
     * 强制流程的下载段：后台下载，完成进安装段；失败时 attempt &lt; 上限给「重试/退出」，
     * 超限则给「打开下载页/退出应用」的兜底（重试耗尽不再提供重试，防止服务器长时间不可达时死循环）。
     */
    private void forcedDownload(Window owner, UpdateInfo info, Consumer<String> status, int attempt) {
        if (status != null) {
            status.accept(I18n.t("settings.update.downloading", 0));
        }
        Thread t = new Thread(() -> {
            Path file;
            try {
                file = download(info, ratio -> {
                    if (status != null) {
                        Platform.runLater(() -> status.accept(I18n.t(
                                "settings.update.downloading", (int) Math.round(ratio * 100))));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    String err = String.valueOf(e.getMessage());
                    if (attempt < MAX_DOWNLOAD_RETRY) {
                        int r = GimDialog.chooseForced(owner, I18n.t("settings.update.downloadFail.title"), null,
                                I18n.t("settings.update.downloadFail.body", err),
                                I18n.t("settings.update.retry"), I18n.t("settings.update.exitNow"));
                        if (r == 0) {
                            forcedDownload(owner, info, status, attempt + 1);
                        } else {
                            Platform.exit();
                        }
                    } else {
                        int r = GimDialog.chooseForced(owner, I18n.t("settings.update.downloadFail.title"), null,
                                I18n.t("settings.update.downloadFail.maxBody", err),
                                I18n.t("settings.update.openPage"), I18n.t("settings.update.exitNow"));
                        if (r == 0) {
                            openDownloadPage(info);
                        }
                        Platform.exit();
                    }
                });
                return;
            }
            Platform.runLater(() -> {
                if (status != null) {
                    status.accept(I18n.t("settings.update.downloadDone"));
                }
                GimDialog.chooseForced(owner, I18n.t("settings.update.install.title"), descHeader(info),
                        I18n.t("settings.update.install.body", file.getFileName().toString()),
                        I18n.t("settings.update.install"));
                // 三段式最后一段：msi 走静默安装（msiexec /qn），其余退回拉起安装器
                boolean ok = installSilently(file);
                Platform.runLater(() -> {
                    GimDialog.chooseForced(owner, I18n.t("settings.update.exit.title"), null,
                            I18n.t(ok ? "settings.update.exit.body" : "settings.update.exit.bodyManual",
                                    file.getFileName().toString()),
                            I18n.t("settings.update.exitNow"));
                    Platform.exit();
                });
            });
        }, "gim-update-forced");
        t.setDaemon(true);
        t.start();
    }

    /** 更新说明作为模态副标题；空白时返回 null（不占行）。 */
    private static String descHeader(UpdateInfo info) {
        String d = info.updateDesc();
        return d == null || d.isBlank() ? null : d.trim();
    }

    // ===================== 内部工具 =====================

    private static String safeFileName(String url) {
        String seg = URI.create(url).getPath();
        int idx = seg.lastIndexOf('/');
        String name = idx >= 0 ? seg.substring(idx + 1) : seg;
        if (name.isBlank()) {
            name = "update-installer";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static boolean isArchiveLike(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".tar.gz") || n.endsWith(".zip") || n.endsWith(".tgz");
    }

    private static String md5Hex(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) > 0) {
                    md.update(buf, 0, read);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("无法计算校验和", e);
        }
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) > 0) {
                    md.update(buf, 0, read);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("无法计算校验和", e);
        }
    }

    private static String trimTrailingSlash(String url) {
        String s = url == null ? "" : url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
