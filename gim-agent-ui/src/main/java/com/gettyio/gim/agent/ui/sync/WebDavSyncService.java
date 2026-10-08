package com.gettyio.gim.agent.ui.sync;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.backup.DbBackupService;
import com.gettyio.gim.agent.domain.security.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;

/**
 * WebDAV 云同步：把 {@link DbBackupService} 的 VACUUM INTO 快照与 workspace 打包压缩包
 * 上传到用户配置的 WebDAV 目录（坚果云/Nextcloud/InfiniCloud 等均兼容）。
 * <p>配置存 settings 表：地址/账号明文、密码经 {@link SecretCipher} 加密；自动同步按
 * 小时周期（0 或空表示仅手动），由应用就绪后的守护线程巡检到期触发。所有远端路径
 * {@code <base>/<文件名>} 平铺，不创建子目录结构。</p>
 */
@Component
@Lazy(false) // 全局 lazy-init 下保持 eager：@EventListener(ApplicationReadyEvent) 启动自动同步巡检
public class WebDavSyncService {

    private static final Logger log = LoggerFactory.getLogger(WebDavSyncService.class);

    /** WebDAV 服务地址（目录）设置键 */
    public static final String KEY_URL = "sync.webdav.url";
    /** 账号设置键 */
    public static final String KEY_USERNAME = "sync.webdav.username";
    /** 密码设置键（SecretCipher 加密存储） */
    public static final String KEY_PASSWORD = "sync.webdav.password";
    /** 自动同步周期（小时）设置键：0 或空表示仅手动 */
    public static final String KEY_INTERVAL_HOURS = "sync.webdav.intervalHours";
    /** 最近同步时间设置键 */
    public static final String KEY_LAST_SYNC = "sync.webdav.lastSyncAt";

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** workspace 压缩包在远端的固定名（覆盖式上传，只保留最新） */
    private static final String WORKSPACE_ZIP_NAME = "jwork-workspace-latest.zip";
    /** 自动同步巡检周期：每 30 分钟看一次是否到期 */
    private static final long CHECK_MINUTES = 30;

    private final GimDatabase db;
    private final SecretCipher cipher;
    private final GimPaths gimPaths;
    private final DbBackupService backupService;
    /** 自动同步巡检线程（守护，应用退出即终止） */
    private ScheduledExecutorService autoSyncExecutor;

    @Autowired
    public WebDavSyncService(GimDatabase db, SecretCipher cipher, GimPaths gimPaths,
                             DbBackupService backupService) {
        this.db = db;
        this.cipher = cipher;
        this.gimPaths = gimPaths;
        this.backupService = backupService;
    }

    // ---- 配置读写 ----

    /** 是否已配置服务地址（自动/手动同步的前置条件）。 */
    public boolean isConfigured() {
        return !url().isBlank();
    }

    public String url() {
        return db.settingDao().getValue(KEY_URL).orElse("").trim();
    }

    public String username() {
        return db.settingDao().getValue(KEY_USERNAME).orElse("").trim();
    }

    /** 解密后的密码；未配置返回空串（解密失败视为未配置并告警，不阻断界面读取）。 */
    public String passwordPlain() {
        String stored = db.settingDao().getValue(KEY_PASSWORD).orElse("");
        if (stored.isBlank()) {
            return "";
        }
        try {
            return cipher.decrypt(stored);
        } catch (Exception e) {
            log.warn("WebDAV 密码解密失败，按未配置处理", e);
            return "";
        }
    }

    public int intervalHours() {
        try {
            return db.settingDao().getValue(KEY_INTERVAL_HOURS)
                    .filter(s -> !s.isBlank())
                    .map(Integer::parseInt)
                    .orElse(0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 保存配置：密码非空则加密入库，空串表示清除；url 统一补尾部斜杠。 */
    public void save(String url, String username, String password, int intervalHours) {
        String u = url == null ? "" : url.trim();
        if (!u.isEmpty() && !u.endsWith("/")) {
            u = u + "/";
        }
        db.settingDao().upsert(KEY_URL, u, "WebDAV 同步服务地址");
        db.settingDao().upsert(KEY_USERNAME, username == null ? "" : username.trim(), "WebDAV 同步账号");
        String pwd = password == null ? "" : password;
        db.settingDao().upsert(KEY_PASSWORD,
                pwd.isEmpty() ? "" : cipher.encrypt(pwd), "WebDAV 同步密码（加密）");
        db.settingDao().upsert(KEY_INTERVAL_HOURS, String.valueOf(Math.max(0, intervalHours)),
                "WebDAV 自动同步周期（小时，0=仅手动）");
    }

    // ---- 同步动作 ----

    /** 测试连通性：对服务地址发一次 PROPFIND 列目录。失败抛 IOException 交调用方展示。 */
    public void testConnection() throws IOException {
        requireConfigured();
        Sardine sardine = begin();
        try {
            sardine.list(url());
        } finally {
            sardine.shutdown();
        }
    }

    /**
     * 手动同步：先 VACUUM INTO 生成新快照，上传快照与 workspace 压缩包（覆盖式），记录最近同步时间。
     *
     * @return 上传的数据库快照文件（本地路径）
     */
    public synchronized Path syncNow() throws IOException {
        String base = requireConfigured();
        Path dbSnapshot = backupService.backupNow()
                .orElseThrow(() -> new IOException("数据库为空（无表），跳过同步"));
        Sardine sardine = begin();
        try {
            sardine.put(join(base, dbSnapshot.getFileName().toString()), Files.readAllBytes(dbSnapshot));
            Path zip = zipWorkspace();
            try {
                sardine.put(join(base, WORKSPACE_ZIP_NAME), Files.readAllBytes(zip));
            } finally {
                Files.deleteIfExists(zip);
            }
        } finally {
            sardine.shutdown();
        }
        db.settingDao().upsert(KEY_LAST_SYNC, Instant.now().toString(), "WebDAV 最近同步时间");
        log.info("WebDAV 同步完成: {}", dbSnapshot.getFileName());
        return dbSnapshot;
    }

    /** 距上次同步超过配置周期且已配置则自动同步一次；返回本次上传的快照（未触发为空）。 */
    public synchronized Optional<Path> syncIfDue() throws IOException {
        int hours = intervalHours();
        if (hours <= 0 || !isConfigured()) {
            return Optional.empty();
        }
        Optional<Instant> last = lastSyncAt();
        if (last.isPresent()
                && java.time.Duration.between(last.get(), Instant.now()).toHours() < hours) {
            return Optional.empty();
        }
        return Optional.of(syncNow());
    }

    /** 最近同步时间：解析设置里的 ISO instant。 */
    public Optional<Instant> lastSyncAt() {
        return db.settingDao().getValue(KEY_LAST_SYNC).flatMap(raw -> {
            try {
                return Optional.of(Instant.parse(raw));
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    // ---- 自动同步巡检 ----

    /** 应用就绪后启动守护巡检线程：每 30 分钟看一次是否到期（启动时先看一次）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void startAutoSync() {
        if (autoSyncExecutor != null) {
            return;
        }
        autoSyncExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "webdav-auto-sync");
            t.setDaemon(true);
            return t;
        });
        autoSyncExecutor.scheduleWithFixedDelay(this::syncIfDueQuietly, 1, CHECK_MINUTES, TimeUnit.MINUTES);
    }

    /** 巡检体：任何异常只记日志，绝不向上抛（守护线程崩了就不再自动同步）。 */
    private void syncIfDueQuietly() {
        try {
            syncIfDue().ifPresent(p -> log.info("WebDAV 自动同步完成: {}", p.getFileName()));
        } catch (Exception e) {
            log.warn("WebDAV 自动同步失败: {}", e.getMessage());
        }
    }

    // ---- 内部工具 ----

    private Sardine begin() {
        String user = username();
        String pwd = passwordPlain();
        return user.isEmpty() && pwd.isEmpty()
                ? SardineFactory.begin()
                : SardineFactory.begin(user, pwd);
    }

    private String requireConfigured() throws IOException {
        String base = url();
        if (base.isEmpty()) {
            throw new IOException("未配置 WebDAV 服务地址");
        }
        return base;
    }

    /** 平铺拼接远端路径：base 已保证尾部斜杠。 */
    private static String join(String base, String name) {
        return base + name;
    }

    /** 把 workspace 目录递归压缩到临时文件（远端固定名覆盖上传，只保留最新一份）。 */
    private Path zipWorkspace() throws IOException {
        Path ws = gimPaths.workspaceDir();
        Path zip = Files.createTempFile("jwork-ws-", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            if (Files.isDirectory(ws)) {
                try (Stream<Path> walk = Files.walk(ws)) {
                    List<Path> files = walk.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(Path::toString))
                            .collect(java.util.stream.Collectors.toList());
                    for (Path f : files) {
                        out.putNextEntry(new ZipEntry(ws.relativize(f).toString().replace('\\', '/')));
                        Files.copy(f, out);
                        out.closeEntry();
                    }
                }
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(zip);
            } catch (IOException ignored) {
                // 清理失败不影响主流程
            }
            throw new UncheckedIOException("打包 workspace 失败", e);
        }
        return zip;
    }
}
