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

package com.gettyio.gim.agent.ui.sync;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.backup.DbBackupService;
import com.gettyio.gim.agent.domain.backup.SqliteSnapshotValidator;
import com.gettyio.gim.agent.domain.security.SecretStore;
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
 * <p>配置存 settings 表：地址/账号明文，密码与同步口令经 {@link SecretStore} 加密；
 * 自动同步按小时周期（0 或空表示仅手动），由应用就绪后的守护线程巡检到期触发；
 * 远端快照滚动保留最近 {@value #REMOTE_SNAPSHOT_KEEP} 份。上传载荷一律先用
 * {@link SyncPayloadCipher} 以同步口令加密（文件名加 {@value #ENC_SUFFIX} 后缀）：快照里是
 * 全量对话记录与加密后的密钥，未设口令时同步直接拒绝并提示去设置页填写，不提供明文上传；
 * 历史遗留的明文远端快照仍可正常下载恢复。所有远端路径 {@code <base>/<文件名>} 平铺，
 * 不创建子目录结构。</p>
 *
 * @author gogym
 */
public class WebDavSyncService {

    private static final Logger log = LoggerFactory.getLogger(WebDavSyncService.class);

    /** WebDAV 服务地址（目录）设置键 */
    public static final String KEY_URL = "sync.webdav.url";
    /** 账号设置键 */
    public static final String KEY_USERNAME = "sync.webdav.username";
    /** 密码设置键（SecretStore 加密存储） */
    public static final String KEY_PASSWORD = "sync.webdav.password";
    /** 同步加密口令设置键（SecretStore 加密存储；未设置时同步被拒绝，不再有明文上传这条分支） */
    public static final String KEY_PASSPHRASE = "sync.webdav.passphrase";
    /** 自动同步周期（小时）设置键：0 或空表示仅手动 */
    public static final String KEY_INTERVAL_HOURS = "sync.webdav.intervalHours";
    /** 最近同步时间设置键 */
    public static final String KEY_LAST_SYNC = "sync.webdav.lastSyncAt";

    /** settings.remark 文案：改动需保持与既有入库行一致，避免每次保存重写备注。 */
    private static final String REMARK_PASSWORD = "WebDAV 同步密码（加密）";
    private static final String REMARK_PASSPHRASE = "WebDAV 同步加密口令（加密）";

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

    private final GimDatabase db;
    private final SecretStore secrets;
    private final GimPaths gimPaths;
    private final DbBackupService backupService;
    /** 自动同步巡检线程（守护；停机时由 {@link #stopAutoSync()} 关闭，线程本身 volatile 保证跨线程可见） */
    private volatile ScheduledExecutorService autoSyncExecutor;
    /** 同步结果监听者（设置页据此刷新「最近同步」与失败提示） */
    private final List<Consumer<SyncResult>> listeners = new CopyOnWriteArrayList<>();

    public WebDavSyncService(GimDatabase db, SecretStore secrets, GimPaths gimPaths,
                             DbBackupService backupService) {
        this.db = db;
        this.secrets = secrets;
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

    /** 是否已配置服务地址（列目录与下载恢复的前置条件）。 */
    public boolean isConfigured() {
        return !url().isBlank();
    }

    /**
     * 是否满足上传条件：地址已配且设了可解开的加密口令。缺口令时自动同步据此跳过本轮
     * （属「尚未配置」而非「同步失败」，不该每 30 分钟刷一条告警），手动同步据此明确拒绝。
     */
    public boolean isSyncReady() {
        return isConfigured() && !passphrasePlain().isEmpty();
    }

    public String url() {
        return db.settingDao().getValue(KEY_URL).orElse("").trim();
    }

    public String username() {
        return db.settingDao().getValue(KEY_USERNAME).orElse("").trim();
    }

    /** 解密后的密码；未配置或本机解不开返回空串（解不开记 ERROR，且同步入口会明确拒绝，不带空密码硬连）。 */
    public String passwordPlain() {
        return secrets.open(stored(KEY_PASSWORD), "WebDAV 密码");
    }

    /** 解密后的同步加密口令；未配置或本机解不开返回空串（空即「尚不可同步」，而不是「明文上传」）。 */
    public String passphrasePlain() {
        return secrets.open(stored(KEY_PASSPHRASE), "WebDAV 同步口令");
    }

    /** 密码项存储状态：设置页据此区分「未配置」与「已配置但本机解不开」。 */
    public SecretStore.State passwordState() {
        return secrets.state(stored(KEY_PASSWORD));
    }

    /** 同步口令项存储状态。 */
    public SecretStore.State passphraseState() {
        return secrets.state(stored(KEY_PASSPHRASE));
    }

    private String stored(String key) {
        return db.settingDao().getValue(key).orElse("");
    }

    /** 自动同步周期（小时）；未配置或脏值返回 0（0=仅手动）。 */
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

    /**
     * 保存配置：url 统一补尾部斜杠；密码/口令非空才加密覆盖。
     * <p><b>留空表示沿用已存值，不是清除</b>：口令框不回显明文，若把空串当清除，一次「解密暂时
     * 失败 + 失焦」就会永久抹掉用户凭据。撤销走 {@link #clearPassword()} / {@link #clearPassphrase()}。</p>
     */
    public void save(String url, String username, String password, String passphrase, int intervalHours) {
        String u = url == null ? "" : url.trim();
        if (!u.isEmpty() && !u.endsWith("/")) {
            u = u + "/";
        }
        db.settingDao().upsert(KEY_URL, u, "WebDAV 同步服务地址");
        db.settingDao().upsert(KEY_USERNAME, username == null ? "" : username.trim(), "WebDAV 同步账号");
        saveSecret(KEY_PASSWORD, password, REMARK_PASSWORD);
        saveSecret(KEY_PASSPHRASE, passphrase, REMARK_PASSPHRASE);
        db.settingDao().upsert(KEY_INTERVAL_HOURS, String.valueOf(Math.max(0, intervalHours)),
                "WebDAV 自动同步周期（小时，0=仅手动）");
    }

    private void saveSecret(String key, String value, String remark) {
        if (value == null || value.isBlank()) {
            return;
        }
        db.settingDao().upsert(key, secrets.seal(value), remark);
    }

    /** 单独保存密码（加密入库）；空白不落库，即「不修改」。 */
    public void savePassword(String plain) {
        saveSecret(KEY_PASSWORD, plain, REMARK_PASSWORD);
    }

    /** 单独保存同步口令（加密入库）；空白不落库，即「不修改」。 */
    public void savePassphrase(String plain) {
        saveSecret(KEY_PASSPHRASE, plain, REMARK_PASSPHRASE);
    }

    /** 显式清除已存 WebDAV 密码（界面上的「清除」按钮，而非留空保存）。 */
    public void clearPassword() {
        db.settingDao().upsert(KEY_PASSWORD, "", REMARK_PASSWORD);
    }

    /** 显式清除已存同步口令；清除后上传改为明文载荷。 */
    public void clearPassphrase() {
        db.settingDao().upsert(KEY_PASSPHRASE, "", REMARK_PASSPHRASE);
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
     * 手动同步：先校验口令、再依次完成快照（VACUUM INTO）、workspace 打包与载荷加密，
     * 然后把两个密文流式上传（失败自动重试），成功后滚动清理远端快照并记录同步时间。
     *
     * @return 上传的数据库快照文件（本地明文快照路径，便于就地查看）
     * @throws IOException 未配置地址或<b>未设置加密口令</b>（明文上传已不再允许）
     */
    public synchronized Path syncNow() throws IOException {
        String base = requireConfigured();
        // 口令为空即「没配过」（解不开的情况 requireConfigured 已拦）。这条分支以前走明文上传：
        // 快照里是全量对话记录与加密后的密钥，等于把用户的数据原样推上第三方网盘。
        // 先拒再干活，免得白做一次 VACUUM 与整包 workspace 压缩。
        String passphrase = passphrasePlain();
        if (passphrase.isEmpty()) {
            throw new IOException("未设置同步加密口令：快照包含全部对话记录，不允许明文上传，请在设置页填写口令");
        }
        Path zipFile = null;
        Path encDb = null;
        Path encZip = null;
        try {
            Path dbSnapshot = backupService.backupNow()
                    .orElseThrow(() -> new IOException("数据库为空（无表），跳过同步"));
            zipFile = zipWorkspace();

            // 载荷加密到临时文件，随后统一流式上传；远端名加 .enc 后缀供界面识别
            encDb = Files.createTempFile("jwork-sync-", ".db.enc");
            SyncPayloadCipher.encrypt(dbSnapshot, encDb, passphrase);
            encZip = Files.createTempFile("jwork-sync-", ".zip.enc");
            SyncPayloadCipher.encrypt(zipFile, encZip, passphrase);
            String dbRemoteName = dbSnapshot.getFileName() + ENC_SUFFIX;
            String zipRemoteName = WORKSPACE_ZIP_NAME + ENC_SUFFIX;

            uploadWithRetry(base, dbRemoteName, encDb, zipRemoteName, encZip);
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
     * 「从云端恢复」第二步：下载所选快照（加密则用当前同步口令解密），头校验 + 完整性校验都
     * 通过后写入待恢复文件与来源信息；重启应用时由 RestoreApplier 原子替换生效。
     * <p>待恢复文件与来源信息作为一对写入：任一失败即两个都清掉，绝不留「下次启动悄悄换库」
     * 而界面看不出原因的状态。</p>
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
            if (!SqliteSnapshotValidator.hasValidHeader(plain)) {
                throw new IOException("下载内容不是有效的数据库快照");
            }
            // 截断的下载能骗过头校验：16 字节的魔数在文件最前面，正文页却已在传输中丢了一截，
            // 换进现网就是打开报「database disk image is malformed」。这里挡住比重启后救库便宜得多。
            if (!SqliteSnapshotValidator.passesIntegrityCheck(plain)) {
                throw new IOException("快照未通过完整性校验（integrity_check），内容已损坏，请重新同步后再试");
            }
            stagePendingRestore(remoteName, plain);
            log.info("云端快照已就绪，重启应用后完成恢复: {}", remoteName);
            return gimPaths.pendingRestoreFile();
        } finally {
            deleteQuietly(downloaded);
            if (plain != downloaded) {
                deleteQuietly(plain);
            }
        }
    }

    /** 落待恢复快照与来源信息；半途失败撤掉这一对，不留下「下次启动会悄悄换库」的悬空状态。 */
    private void stagePendingRestore(String remoteName, Path plain) throws IOException {
        Path pending = gimPaths.pendingRestoreFile();
        Path info = gimPaths.pendingRestoreInfoFile();
        Files.createDirectories(pending.getParent());
        try {
            Files.writeString(info, remoteName + "\t" + Instant.now(), StandardCharsets.UTF_8);
            Files.copy(plain, pending, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(info);
            deleteQuietly(pending);
            throw e;
        }
    }

    /** 待恢复的云端快照：{@code source} 为远端文件名，{@code at} 为下载时间。 */
    public record PendingRestore(String source, Instant at) {
    }

    /**
     * 当前是否有排队到下次启动的恢复：{@code restore/pending.db} 存在即是。
     * <p>设置页据此把「下次启动会换成哪一份快照」摆到明面上并给出撤销入口——这个状态一旦落下
     * 就跨重启存活，用户很容易在下一次启动前把它忘掉。</p>
     */
    public Optional<PendingRestore> pendingRestore() {
        Path pending = gimPaths.pendingRestoreFile();
        if (!Files.isRegularFile(pending)) {
            return Optional.empty();
        }
        Instant at = null;
        String source = "";
        try {
            String raw = Files.readString(gimPaths.pendingRestoreInfoFile(), StandardCharsets.UTF_8).trim();
            int tab = raw.indexOf('\t');
            if (tab > 0) {
                source = raw.substring(0, tab);
                at = Instant.parse(raw.substring(tab + 1).trim());
            }
        } catch (Exception e) {
            log.warn("待恢复快照来源信息不可读，改用文件时间: {}", e.getMessage());
        }
        if (at == null) {
            try {
                at = Files.getLastModifiedTime(pending).toInstant();
            } catch (IOException e) {
                at = Instant.EPOCH;
            }
        }
        return Optional.of(new PendingRestore(source, at));
    }

    /** 撤销待恢复的快照：删掉 pending 与其来源信息，下次启动保持当前库不变。 */
    public synchronized void cancelPendingRestore() throws IOException {
        Path pending = gimPaths.pendingRestoreFile();
        if (!Files.isRegularFile(pending)) {
            return;
        }
        Files.delete(pending);
        Files.deleteIfExists(gimPaths.pendingRestoreInfoFile());
        log.info("已撤销待恢复的云端快照: {}", pending);
    }

    /** 距上次同步超过配置周期且已配置则自动同步一次；返回本次上传的快照（未触发为空）。 */
    public synchronized Optional<Path> syncIfDue() throws IOException {
        int hours = intervalHours();
        if (hours <= 0 || !isSyncReady()) {
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

    /**
     * 停机：关掉巡检线程，避免退出流程走到一半又起一轮快照上传。
     * <p>刻意不加锁：正在进行的那次同步占着本实例的 monitor 可能长达数十秒，
     * 而这里是在 FX 线程的退出路径上调用，等它等于把退出挂住。巡检线程是守护线程，
     * {@code shutdownNow} 只负责不再排新任务。</p>
     */
    public void stopAutoSync() {
        ScheduledExecutorService executor = autoSyncExecutor;
        autoSyncExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
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
        // 口令解不开时 passphrasePlain 返回空串，与「没配过」在界面上是两件事：这里明确报错，
        // 别让它退化成「未设置口令」这种让人以为从没配过加密的说法
        if (passphraseState() == SecretStore.State.UNREADABLE) {
            throw new IOException("同步口令无法在本机解密（换过机器或 Windows 账户），请在设置页重新录入后再同步");
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
