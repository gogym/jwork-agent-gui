package com.gettyio.gim.agent.ui.sync;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.backup.DbBackupService;
import com.gettyio.gim.agent.domain.security.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;

/**
 * WebDAV 云同步：把 {@link DbBackupService} 的 VACUUM INTO 快照与 workspace 打包压缩包
 * 上传到用户配置的 WebDAV 目录（坚果云/Nextcloud/InfiniCloud 等均兼容），并支持从远端
 * 挑选快照下载恢复（下载校验后落为 pending 文件，重启时由
 * {@link com.gettyio.gim.agent.domain.backup.RestoreApplier} 替换当前库生效）。
 * <p>配置存 settings 表：地址/账号明文，密码与同步口令经 {@link SecretCipher} 加密；
 * 自动同步按小时周期（0 或空表示仅手动），由应用就绪后的守护线程巡检到期触发；
 * 远端快照滚动保留最近 {@value #REMOTE_SNAPSHOT_KEEP} 份；配置了同步口令时，上传前用
 * {@link SyncPayloadCipher} 对载荷加密（文件名加 {@value #ENC_SUFFIX} 后缀）。所有远端
 * 路径 {@code <base>/<文件名>} 平铺，不创建子目录结构。</p>
 */
public class WebDavSyncService {

    private static final Logger log = LoggerFactory.getLogger(WebDavSyncService.class);

    /** WebDAV 服务地址（目录）设置键 */
    public static final String KEY_URL = "sync.webdav.url";
    /** 账号设置键 */
    public static final String KEY_USERNAME = "sync.webdav.username";
    /** 密码设置键（SecretCipher 加密存储） */
    public static final String KEY_PASSWORD = "sync.webdav.password";
    /** 同步加密口令设置键（SecretCipher 加密存储；空表示明文上传） */
    public static final String KEY_PASSPHRASE = "sync.webdav.passphrase";
    /** 自动同步周期（小时）设置键：0 或空表示仅手动 */
    public static final String KEY_INTERVAL_HOURS = "sync.webdav.intervalHours";
    /** 最近同步时间设置键 */
    public static final String KEY_LAST_SYNC = "sync.webdav.lastSyncAt";

    /** 加密载荷文件名后缀：界面据此识别加密快照 */
    public static final String ENC_SUFFIX = ".enc";

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** workspace 压缩包在远端的固定名（覆盖式上传，只保留最新一份） */
    private static final String WORKSPACE_ZIP_NAME = "jwork-workspace-latest.zip";
    /** 数据库快照文件名前后缀（与 DbBackupService 命名一致），用于远端识别与滚动清理 */
    private static final String SNAPSHOT_PREFIX = "jwork-";
    private static final String SNAPSHOT_SUFFIX = ".db";
    /** 远端数据库快照滚动保留份数 */
    static final int REMOTE_SNAPSHOT_KEEP = 7;
    /** 上传阶段最大尝试次数（首次 + 重试） */
    static final int UPLOAD_ATTEMPTS = 3;
    /** 上传重试基础间隔（毫秒），第 n 次重试等待 base x 3^(n-1) */
    private static final long RETRY_BASE_DELAY_MS = 3_000;
    /** 自动同步巡检周期：每 30 分钟看一次是否到期 */
    private static final long CHECK_MINUTES = 30;

    /** SQLite 文件头魔数（前 16 字节），恢复下载时校验 */
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII);

    private final GimDatabase db;
    private final SecretCipher cipher;
    private final GimPaths gimPaths;
    private final DbBackupService backupService;
    /** 自动同步巡检线程（守护，应用退出即终止） */
    private ScheduledExecutorService autoSyncExecutor;
    /** 同步结果监听者（设置页据此刷新「最近同步」与失败提示） */
    private final List<Consumer<SyncResult>> listeners = new CopyOnWriteArrayList<>();

    public WebDavSyncService(GimDatabase db, SecretCipher cipher, GimPaths gimPaths,
                             DbBackupService backupService) {
        this.db = db;
        this.cipher = cipher;
        this.gimPaths = gimPaths;
        this.backupService = backupService;
    }

    /** 远端快照条目：{@code name} 为远端文件名，{@code display} 为界面展示名（解析失败回落文件名）。 */
    public record RemoteSnapshot(String name, String display) {
    }

    /** 同步结果事件：成功与失败都会通知监听者（自动同步失败同时保留日志）。 */
    public record SyncResult(boolean success, String message, Instant at) {
    }

    /** 注册同步结果监听（设置页用；同一面板单例注册一次，不注销）。 */
    public void addSyncListener(Consumer<SyncResult> listener) {
        listeners.add(listener);
    }

    private void notifyListeners(SyncResult result) {
        for (Consumer<SyncResult> listener : listeners) {
            try {
                listener.accept(result);
            } catch (RuntimeException e) {
                log.warn("同步监听回调失败: {}", e.getMessage());
            }
        }
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
        return decryptStored(KEY_PASSWORD);
    }

    /** 解密后的同步加密口令；未配置或解密失败返回空串（空表示明文上传）。 */
    public String passphrasePlain() {
        return decryptStored(KEY_PASSPHRASE);
    }

    private String decryptStored(String key) {
        String stored = db.settingDao().getValue(key).orElse("");
        if (stored.isBlank()) {
            return "";
        }
        try {
            return cipher.decrypt(stored);
        } catch (Exception e) {
            log.warn("WebDAV 加密配置解密失败，按未配置处理: {}", key, e);
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

    /** 保存配置：密码/口令非空则加密入库，空串表示清除；url 统一补尾部斜杠。 */
    public void save(String url, String username, String password, String passphrase, int intervalHours) {
        String u = url == null ? "" : url.trim();
        if (!u.isEmpty() && !u.endsWith("/")) {
            u = u + "/";
        }
        db.settingDao().upsert(KEY_URL, u, "WebDAV 同步服务地址");
        db.settingDao().upsert(KEY_USERNAME, username == null ? "" : username.trim(), "WebDAV 同步账号");
        saveSecret(KEY_PASSWORD, password, "WebDAV 同步密码（加密）");
        saveSecret(KEY_PASSPHRASE, passphrase, "WebDAV 同步加密口令（加密）");
        db.settingDao().upsert(KEY_INTERVAL_HOURS, String.valueOf(Math.max(0, intervalHours)),
                "WebDAV 自动同步周期（小时，0=仅手动）");
    }

    private void saveSecret(String key, String value, String remark) {
        String v = value == null ? "" : value;
        db.settingDao().upsert(key, v.isEmpty() ? "" : cipher.encrypt(v), remark);
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
     * 手动同步：先后完成快照（VACUUM INTO）、workspace 打包与（可选）口令加密，
     * 再把两个载荷流式上传（失败自动重试），成功后滚动清理远端快照并记录同步时间。
     *
     * @return 上传的数据库快照文件（本地路径）
     */
    public synchronized Path syncNow() throws IOException {
        String base = requireConfigured();
        Path zipFile = null;
        Path encDb = null;
        Path encZip = null;
        try {
            Path dbSnapshot = backupService.backupNow()
                    .orElseThrow(() -> new IOException("数据库为空（无表），跳过同步"));
            zipFile = zipWorkspace();

            // 载荷准备：配置了口令则先加密到临时文件，随后统一流式上传
            String passphrase = passphrasePlain();
            boolean encrypted = !passphrase.isEmpty();
            Path dbSource = dbSnapshot;
            Path zipSource = zipFile;
            String dbRemoteName = dbSnapshot.getFileName().toString();
            String zipRemoteName = WORKSPACE_ZIP_NAME;
            if (encrypted) {
                encDb = Files.createTempFile("jwork-sync-", ".db.enc");
                SyncPayloadCipher.encrypt(dbSnapshot, encDb, passphrase);
                encZip = Files.createTempFile("jwork-sync-", ".zip.enc");
                SyncPayloadCipher.encrypt(zipFile, encZip, passphrase);
                dbSource = encDb;
                zipSource = encZip;
                dbRemoteName += ENC_SUFFIX;
                zipRemoteName += ENC_SUFFIX;
            }

            uploadWithRetry(base, dbRemoteName, dbSource, zipRemoteName, zipSource);
            purgeRemoteSnapshots(base);

            db.settingDao().upsert(KEY_LAST_SYNC, Instant.now().toString(), "WebDAV 最近同步时间");
            log.info("WebDAV 同步完成: {}", dbRemoteName);
            notifyListeners(new SyncResult(true, dbRemoteName, Instant.now()));
            return dbSnapshot;
        } catch (IOException | RuntimeException e) {
            notifyListeners(new SyncResult(false, String.valueOf(e.getMessage()), Instant.now()));
            throw e;
        } finally {
            deleteQuietly(zipFile);
            deleteQuietly(encDb);
            deleteQuietly(encZip);
        }
    }

    /** 上传两个载荷（流式），失败按递增间隔重试最多 {@link #UPLOAD_ATTEMPTS} 次。 */
    private void uploadWithRetry(String base, String dbName, Path dbSrc,
                                 String zipName, Path zipSrc) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= UPLOAD_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                sleepQuietly(RETRY_BASE_DELAY_MS * (long) Math.pow(3, attempt - 2));
            }
            Sardine sardine = begin();
            try {
                try (InputStream in = Files.newInputStream(dbSrc)) {
                    sardine.put(join(base, dbName), in);
                }
                try (InputStream in = Files.newInputStream(zipSrc)) {
                    sardine.put(join(base, zipName), in);
                }
                return;
            } catch (IOException e) {
                last = e;
                log.warn("WebDAV 上传失败（第 {}/{} 次）: {}", attempt, UPLOAD_ATTEMPTS, e.getMessage());
            } finally {
                sardine.shutdown();
            }
        }
        throw new IOException("上传失败（已重试 " + UPLOAD_ATTEMPTS + " 次）: " + last.getMessage(), last);
    }

    /** 远端快照滚动清理：按文件名（时间戳）降序保留最近 {@link #REMOTE_SNAPSHOT_KEEP} 份，其余删除。 */
    private void purgeRemoteSnapshots(String base) {
        try {
            List<String> names = listRemoteSnapshotNames(base);
            if (names.size() <= REMOTE_SNAPSHOT_KEEP) {
                return;
            }
            Sardine sardine = begin();
            try {
                for (String stale : names.subList(REMOTE_SNAPSHOT_KEEP, names.size())) {
                    sardine.delete(join(base, stale));
                    log.info("删除远端过期快照: {}", stale);
                }
            } finally {
                sardine.shutdown();
            }
        } catch (Exception e) {
            // 清理失败不影响本次同步结果
            log.warn("远端快照滚动清理失败: {}", e.getMessage());
        }
    }

    /**
     * 列出远端快照（最新在前），供「从云端恢复」选择。
     *
     * @throws IOException 未配置或列目录失败
     */
    public synchronized List<RemoteSnapshot> listRemoteSnapshots() throws IOException {
        String base = requireConfigured();
        return listRemoteSnapshotNames(base).stream()
                .map(name -> new RemoteSnapshot(name, displayName(name)))
                .collect(Collectors.toList());
    }

    /**
     * 「从云端恢复」第二步：下载所选快照（加密则用当前同步口令解密），校验 SQLite 头后
     * 写入待恢复文件；重启应用时由 RestoreApplier 原子替换生效。
     *
     * @return 待恢复文件（pending）路径
     */
    public synchronized Path restoreFromRemote(String remoteName) throws IOException {
        String base = requireConfigured();
        if (remoteName == null || !isSnapshotName(remoteName)) {
            throw new IOException("非法的快照文件名: " + remoteName);
        }
        Path downloaded = Files.createTempFile("jwork-restore-", ".tmp");
        Path plain = downloaded;
        try {
            Sardine sardine = begin();
            try (InputStream in = sardine.get(join(base, remoteName))) {
                Files.copy(in, downloaded, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                sardine.shutdown();
            }
            if (remoteName.endsWith(ENC_SUFFIX)) {
                String passphrase = passphrasePlain();
                if (passphrase.isEmpty()) {
                    throw new IOException("该快照已加密，请先配置同步口令");
                }
                plain = Files.createTempFile("jwork-restore-", ".plain");
                SyncPayloadCipher.decrypt(downloaded, plain, passphrase);
            }
            if (!hasSqliteHeader(plain)) {
                throw new IOException("下载内容不是有效的数据库快照");
            }
            Path pending = gimPaths.pendingRestoreFile();
            Files.createDirectories(pending.getParent());
            Files.copy(plain, pending, StandardCopyOption.REPLACE_EXISTING);
            log.info("云端快照已就绪，重启应用后完成恢复: {}", remoteName);
            return pending;
        } finally {
            deleteQuietly(downloaded);
            if (plain != downloaded) {
                deleteQuietly(plain);
            }
        }
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
        // 代理：JVM 默认 ProxySelector 实时读系统属性，随全局代理设置即时生效
        ProxySelector proxy = ProxySelector.getDefault();
        return user.isEmpty() && pwd.isEmpty()
                ? SardineFactory.begin(null, null, proxy)
                : SardineFactory.begin(user, pwd, proxy);
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

    /** 远端快照文件名单列表（最新在前）：过滤 {@code jwork-*.db[.enc]} 并跳过目录自身。 */
    private List<String> listRemoteSnapshotNames(String base) throws IOException {
        Sardine sardine = begin();
        try {
            return sardine.list(base).stream()
                    .map(DavResource::getName)
                    .map(WebDavSyncService::fileNameOf)
                    .filter(WebDavSyncService::isSnapshotName)
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
        } finally {
            sardine.shutdown();
        }
    }

    /** 从列表接口返回的完整路径中取文件名。 */
    private static String fileNameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** 是否为数据库快照名（时间戳字典序即时间序），含加密后缀判定。 */
    private static boolean isSnapshotName(String name) {
        String plain = name.endsWith(ENC_SUFFIX)
                ? name.substring(0, name.length() - ENC_SUFFIX.length())
                : name;
        return plain.startsWith(SNAPSHOT_PREFIX)
                && plain.endsWith(SNAPSHOT_SUFFIX)
                && plain.length() > SNAPSHOT_PREFIX.length() + SNAPSHOT_SUFFIX.length();
    }

    /** 快照展示名：文件名时间戳格式化为可读时间，解析失败回落文件名原样。 */
    private static String displayName(String remoteName) {
        String plain = remoteName.endsWith(ENC_SUFFIX)
                ? remoteName.substring(0, remoteName.length() - ENC_SUFFIX.length())
                : remoteName;
        String ts = plain.substring(SNAPSHOT_PREFIX.length(), plain.length() - SNAPSHOT_SUFFIX.length());
        try {
            return LocalDateTime.parse(ts, TS_FORMAT).format(DISPLAY_FORMAT);
        } catch (RuntimeException e) {
            return remoteName;
        }
    }

    /** 前 16 字节是否为 SQLite 文件头。 */
    private static boolean hasSqliteHeader(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] header = in.readNBytes(SQLITE_MAGIC.length);
            if (header.length != SQLITE_MAGIC.length) {
                return false;
            }
            for (int i = 0; i < SQLITE_MAGIC.length; i++) {
                if (header[i] != SQLITE_MAGIC[i]) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("清理临时文件失败: {}", path, e);
        }
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
