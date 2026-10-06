package com.gettyio.gim.agent.ui.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.notify.TrayNotifier;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * 更新检查：比对 GitHub Releases 最新版与本应用语义化版本。
 * <ul>
 *   <li><b>当前版本</b>取自过滤生成的 classpath 资源 {@code version.properties}（{@code app.version}），
 *       与打包脚本同源；比较时归一化去掉前导 {@code v} 与 {@code -SNAPSHOT} 等后缀。</li>
 *   <li><b>手动检查</b>：{@link #checkLatest()} 由设置页按钮调用，阻塞返回是否有新版。</li>
 *   <li><b>静默检查</b>：{@link #maybeCheckOnStartup()} 在开关（AppSetting {@code app.update.autoCheck}，
 *       默认关）开启且当日未查过时后台执行，发现新版仅发托盘气泡，不打断用户。</li>
 * </ul>
 */
@Component
public class UpdateChecker {

    private static final Logger log = LoggerFactory.getLogger(UpdateChecker.class);

    public static final String KEY_AUTO_CHECK = "app.update.autoCheck";
    private static final String KEY_LAST_CHECK = "app.update.lastCheckDate";

    private static final String LATEST_API =
            "https://api.github.com/repos/gogym/gim-agent-gui/releases/latest";
    private static final String RELEASES_PAGE =
            "https://github.com/gogym/gim-agent-gui/releases/latest";

    private final GimDatabase db;
    private final TrayNotifier trayNotifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile String cachedCurrent;

    @Autowired
    public UpdateChecker(GimDatabase db, TrayNotifier trayNotifier) {
        this.db = db;
        this.trayNotifier = trayNotifier;
    }

    /** 一次更新检查结果：最新版本号与其下载页 URL。 */
    public record UpdateInfo(String version, String url) {
    }

    // ===================== 当前版本 =====================

    /** 当前应用版本（归一化，去 v 前缀与 -SNAPSHOT 后缀）；读不到时回退 0.0.0。 */
    public String currentVersion() {
        String v = cachedCurrent;
        if (v == null) {
            v = loadVersion();
            cachedCurrent = v;
        }
        return v;
    }

    private String loadVersion() {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/version.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            log.warn("读取 version.properties 失败", e);
        }
        return normalize(p.getProperty("app.version", "0.0.0"));
    }

    /** 归一化版本串：去空白、去前导 v、截断 {@code -}/{@code +} 预发布后缀。 */
    static String normalize(String raw) {
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

    /** 语义化版本点分数值比较：a &gt; b 返回正，缺位补 0，非数字段按 0。 */
    static int compareVersions(String a, String b) {
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

    // ===================== 手动检查 =====================

    /**
     * 拉取 GitHub 最新版并与当前版本比对。
     *
     * @return 有新版返回其信息；已是最新返回空
     * @throws IOException 网络或解析失败
     */
    public Optional<UpdateInfo> checkLatest() throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(LATEST_API))
                    .timeout(Duration.ofSeconds(12))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + resp.statusCode());
            }
            JsonNode root = mapper.readTree(resp.body());
            String tag = root.path("tag_name").asText("");
            String url = root.path("html_url").asText(RELEASES_PAGE);
            if (tag.isBlank()) {
                throw new IOException("响应缺少 tag_name");
            }
            String latest = normalize(tag);
            if (compareVersions(latest, currentVersion()) > 0) {
                return Optional.of(new UpdateInfo(latest, url.isBlank() ? RELEASES_PAGE : url));
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("检查被中断", e);
        }
    }

    // ===================== 静默每日检查 =====================

    public boolean isAutoCheckEnabled() {
        return "true".equalsIgnoreCase(db.settingDao().getValue(KEY_AUTO_CHECK).orElse("false").trim());
    }

    public void setAutoCheckEnabled(boolean enabled) {
        db.settingDao().upsert(KEY_AUTO_CHECK, String.valueOf(enabled), "启动后每日静默检查更新");
    }

    /**
     * 启动后调用：开关开启且当日未检查过，则后台静默检查一次；发现新版仅发托盘气泡。
     * 无论是否命中新版，都记录当日已检查，避免同日重复请求。
     */
    public void maybeCheckOnStartup() {
        if (!isAutoCheckEnabled()) {
            return;
        }
        LocalDate today = LocalDate.now();
        String last = db.settingDao().getValue(KEY_LAST_CHECK).orElse("").trim();
        if (today.toString().equals(last)) {
            return;
        }
        db.settingDao().upsert(KEY_LAST_CHECK, today.toString(), "上次静默检查日期(ISO)");
        Thread t = new Thread(() -> {
            try {
                checkLatest().ifPresent(info -> Platform.runLater(() ->
                        trayNotifier.notify(I18n.t("update.available.title"),
                                I18n.t("update.available.body", currentVersion(), info.version()))));
            } catch (Exception e) {
                log.debug("静默检查更新失败: {}", e.getMessage());
            }
        }, "gim-update-check");
        t.setDaemon(true);
        t.start();
    }

    // ===================== 打开下载页 =====================

    /** 用系统默认浏览器打开更新下载页；不支持或异常返回 false。 */
    public boolean openDownloadPage(UpdateInfo info) {
        String url = (info == null || info.url() == null || info.url().isBlank()) ? RELEASES_PAGE : info.url();
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
}
