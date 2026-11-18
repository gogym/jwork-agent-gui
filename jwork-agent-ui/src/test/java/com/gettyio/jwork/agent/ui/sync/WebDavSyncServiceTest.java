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

package com.gettyio.jwork.agent.ui.sync;

import com.gettyio.jwork.agent.core.JworkPaths;
import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.backup.DbBackupService;
import com.gettyio.jwork.agent.domain.backup.SqliteSnapshotValidator;
import com.gettyio.jwork.agent.domain.persist.SqliteConnectionProvider;
import com.gettyio.jwork.agent.domain.persist.SqlSchemaInitializer;
import com.gettyio.jwork.agent.domain.security.AesGcmSecretCipher;
import com.gettyio.jwork.agent.domain.security.SecretStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebDavSyncServiceTest} 单测：配置读写（密码/口令加密往返、URL 归一化）、同步上传
 * （载荷一律口令加密、远端快照滚动清理、缺口令即拒绝）、云端恢复下载（校验后落 pending、
 * 待恢复项可见可撤销）。全部基于内嵌 HttpServer 模拟 WebDAV（PUT/PROPFIND/DELETE/GET），无外部依赖。
 *
 * @author gogym
 */
class WebDavSyncServiceTest {

    @TempDir
    Path tempDir;

    private JworkDatabase db;
    private JworkPaths paths;
    private WebDavSyncService service;
    private HttpServer server;
    /** 模拟远端存储：路径 → 内容 */
    private final Map<String, byte[]> remote = new ConcurrentHashMap<>();
    /** 模拟远端 PUT 顺序（路径），用于断言上传次序 */
    private final List<String> putOrder = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(tempDir.resolve("jwork.db"));
        new SqlSchemaInitializer(cp).initialize();
        db = new JworkDatabase(cp);
        paths = new JworkPaths(tempDir);
        Path keyFile = tempDir.resolve("secret.key");
        service = new WebDavSyncService(db, new SecretStore(new AesGcmSecretCipher(keyFile), keyFile),
                paths, new DbBackupService(db, paths.backupsDir()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dav/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            switch (method) {
                case "PUT" -> {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    remote.put(path, body);
                    synchronized (putOrder) {
                        putOrder.add(path);
                    }
                    exchange.sendResponseHeaders(201, -1);
                }
                case "DELETE" -> {
                    remote.remove(path);
                    exchange.sendResponseHeaders(204, -1);
                }
                case "GET" -> {
                    byte[] body = remote.get(path);
                    if (body == null) {
                        exchange.sendResponseHeaders(404, -1);
                    } else {
                        exchange.sendResponseHeaders(200, body.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(body);
                        }
                    }
                }
                case "PROPFIND" -> {
                    byte[] xml = propfindXml().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/xml");
                    exchange.sendResponseHeaders(207, xml.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(xml);
                    }
                }
                default -> exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** 按模拟远端现状生成 multistatus：根目录 + 全部文件条目。 */
    private String propfindXml() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                .append("<D:multistatus xmlns:D=\"DAV:\">")
                .append("<D:response><D:href>/dav/</D:href><D:propstat><D:prop>")
                .append("<D:resourcetype><D:collection/></D:resourcetype>")
                .append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
        for (Map.Entry<String, byte[]> e : remote.entrySet()) {
            sb.append("<D:response><D:href>").append(e.getKey()).append("</D:href>")
                    .append("<D:propstat><D:prop><D:resourcetype/>")
                    .append("<D:getcontentlength>").append(e.getValue().length).append("</D:getcontentlength>")
                    .append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
        }
        return sb.append("</D:multistatus>").toString();
    }

    private String davUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/dav/";
    }

    @Test
    @DisplayName("save 归一化 URL 尾部斜杠，密码与口令加密存储且可解密回读")
    void saveAndReadBack() {
        assertFalse(service.isConfigured());

        service.save(davUrl().substring(0, davUrl().length() - 1), "alice", "s3cret", "pass123", 12);
        assertEquals(davUrl(), service.url());
        assertEquals("alice", service.username());
        assertEquals("s3cret", service.passwordPlain());
        assertEquals("pass123", service.passphrasePlain());
        assertNotEquals("s3cret",
                db.settingDao().getValue(WebDavSyncService.KEY_PASSWORD).orElseThrow());
        assertNotEquals("pass123",
                db.settingDao().getValue(WebDavSyncService.KEY_PASSPHRASE).orElseThrow());
        assertEquals(12, service.intervalHours());
        assertTrue(service.isConfigured());

        // 无尾部斜杠自动补齐；密码/口令留空表示沿用已存值，不是清除
        service.save(davUrl(), "bob", "", "", 0);
        assertEquals(davUrl(), service.url());
        assertEquals("bob", service.username());
        assertEquals("s3cret", service.passwordPlain(), "留空不该抹掉已存密码");
        assertEquals("pass123", service.passphrasePlain(), "留空不该抹掉已存口令");
        assertEquals(0, service.intervalHours());

        // 撤销认证必须显式表达
        service.clearPassword();
        service.clearPassphrase();
        assertEquals("", service.passwordPlain());
        assertEquals("", service.passphrasePlain());
        assertEquals(SecretStore.State.EMPTY, service.passwordState());
    }

    @Test
    @DisplayName("解不开的密文不回明文也不回密文：状态报 UNREADABLE")
    void undecryptableSecretIsNotLeakedAsCiphertext() {
        service.save(davUrl(), "alice", "s3cret", "pass123", 6);
        // 换机器/换 Windows 用户后本机解不开的历史密文
        db.settingDao().upsert(WebDavSyncService.KEY_PASSWORD, "dpapi:YW5vdGhlci1tYWNoaW5lLWJsb2I=", "脏数据");

        assertEquals("", service.passwordPlain(), "绝不能把密文当明文交出去");
        assertEquals(SecretStore.State.UNREADABLE, service.passwordState(), "界面要能区分「未配置」与「解不开」");
    }

    @Test
    @DisplayName("同步口令解不开时拒绝同步：不静默降级成明文上传")
    void syncRefusesWhenPassphraseUndecryptable() {
        service.save(davUrl(), "alice", "s3cret", "pass123", 6);
        db.settingDao().upsert(WebDavSyncService.KEY_PASSPHRASE, "dpapi:c29tZW90aGVyLXJvbGw=", "脏数据");

        IOException e = assertThrows(IOException.class, service::syncNow);
        assertTrue(e.getMessage().contains("口令"), "失败原因要指向日令无法解密: " + e.getMessage());
        assertTrue(remote.isEmpty(), "解不开口令时一个字节都不该上传");
    }

    @Test
    @DisplayName("未配置地址时 syncNow 抛 IOException")
    void syncWithoutConfigThrows() {
        assertThrows(IOException.class, service::syncNow);
    }

    @Test
    @DisplayName("无表数据库 backupNow 返回空（syncNow 据此拒绝同步，防内存库/未初始化）")
    void syncEmptyDbSkips() {
        // 全新 sqlite 文件：无任何用户表（hasTables 为 false）
        SqliteConnectionProvider raw = new SqliteConnectionProvider(tempDir.resolve("raw.db"));
        JworkDatabase empty = new JworkDatabase(raw);
        assertTrue(new DbBackupService(empty, paths.backupsDir()).backupNow().isEmpty());
    }

    @Test
    @DisplayName("上传载荷一律加密：快照先传、压缩包后传，周期内 syncIfDue 不再触发")
    void syncUploadsEncryptedPayloadsInOrder() throws IOException {
        Files.createDirectories(paths.workspaceDir());
        Files.writeString(paths.workspaceDir().resolve("note.md"), "hello sync", StandardCharsets.UTF_8);

        service.save(davUrl(), "alice", "s3cret", "pass123", 24);
        assertTrue(service.lastSyncAt().isEmpty());

        Path snapshot = service.syncNow();
        assertTrue(Files.exists(snapshot));

        synchronized (putOrder) {
            assertEquals(2, putOrder.size());
            assertEquals("/dav/" + snapshot.getFileName() + WebDavSyncService.ENC_SUFFIX, putOrder.get(0));
            assertEquals("/dav/jwork-workspace-latest.zip" + WebDavSyncService.ENC_SUFFIX, putOrder.get(1));
        }
        byte[] dbBody = remote.get("/dav/" + snapshot.getFileName() + WebDavSyncService.ENC_SUFFIX);
        assertTrue(new String(dbBody, 0, SyncPayloadCipher.MAGIC.length, StandardCharsets.ISO_8859_1)
                .equals(new String(SyncPayloadCipher.MAGIC, StandardCharsets.ISO_8859_1)), "远端快照应为加密容器而非明文库");
        // 加密载荷解回明文才是 SQLite 头：往返跑得通，恢复侧才有的聊
        Path cipher = Files.createTempFile("t-cipher-", ".db.enc");
        Path plain = Files.createTempFile("t-plain-", ".db");
        Files.write(cipher, dbBody);
        SyncPayloadCipher.decrypt(cipher, plain, "pass123");
        assertTrue(SqliteSnapshotValidator.hasValidHeader(plain), "解密后应为有效 SQLite 快照");
        assertTrue(service.lastSyncAt().isPresent());
        // 刚同步过：24h 周期内不应再触发
        assertTrue(service.syncIfDue().isEmpty());
    }

    @Test
    @DisplayName("配置同步口令后上传加密载荷（.enc 文件名 + JWORKSYNC1 魔数）")
    void syncNowUploadsEncrypted() throws IOException {
        Files.createDirectories(paths.workspaceDir());
        Files.writeString(paths.workspaceDir().resolve("note.md"), "secret sync", StandardCharsets.UTF_8);

        service.save(davUrl(), "alice", "s3cret", "pass123", 0);
        Path snapshot = service.syncNow();

        String dbRemoteName = "/dav/" + snapshot.getFileName() + WebDavSyncService.ENC_SUFFIX;
        byte[] dbBody = remote.get(dbRemoteName);
        assertTrue(dbBody != null && dbBody.length > 0, "应上传加密后的数据库快照");
        assertTrue(new String(dbBody, 0, SyncPayloadCipher.MAGIC.length, StandardCharsets.ISO_8859_1)
                .equals(new String(SyncPayloadCipher.MAGIC, StandardCharsets.ISO_8859_1)), "载荷应为 JWORKSYNC1 容器");
        assertTrue(remote.containsKey("/dav/jwork-workspace-latest.zip" + WebDavSyncService.ENC_SUFFIX));
    }

    @Test
    @DisplayName("远端快照滚动清理：上传后仅保留最新 7 份")
    void purgeKeepsLatestSnapshots() throws IOException {
        // 预置 12 份历史快照（时间戳均早于本次同步）
        for (int i = 1; i <= 12; i++) {
            remote.put("/dav/jwork-202601" + String.format("%02d", i) + "-000000.db", new byte[]{1, 2, 3});
        }
        service.save(davUrl(), "alice", "s3cret", "pass123", 0);
        service.syncNow();

        List<WebDavSyncService.RemoteSnapshot> snapshots = service.listRemoteSnapshots();
        assertEquals(WebDavSyncService.REMOTE_SNAPSHOT_KEEP, snapshots.size(), "远端应只保留滚动份数");
        assertTrue(remote.containsKey("/dav/jwork-20260101-000000.db") == false, "最旧快照应被删除");
        assertTrue(remote.containsKey("/dav/jwork-20260112-000000.db"), "较新快照应保留");
    }

    @Test
    @DisplayName("从云端恢复：解密校验后落 pending 与来源信息，展示名可读")
    void restoreFromRemoteStagesPending() throws IOException {
        service.save(davUrl(), "alice", "s3cret", "pass123", 0);
        Path snapshot = service.syncNow();

        List<WebDavSyncService.RemoteSnapshot> snapshots = service.listRemoteSnapshots();
        assertEquals(1, snapshots.size());
        assertEquals(snapshot.getFileName() + WebDavSyncService.ENC_SUFFIX, snapshots.get(0).name());
        assertNotEquals(snapshots.get(0).name(), snapshots.get(0).display(), "展示名应为可读时间");

        Path pending = service.restoreFromRemote(snapshots.get(0).name());
        assertEquals(paths.pendingRestoreFile(), pending);
        assertTrue(Files.exists(pending));
        assertTrue(SqliteSnapshotValidator.hasValidHeader(pending), "pending 应为有效数据库快照");
        assertTrue(Files.exists(paths.pendingRestoreInfoFile()), "来源信息应与 pending 成对落下");
    }

    @Test
    @DisplayName("加密快照恢复：口令往返解密后落 pending；口令缺失时报错")
    void restoreEncryptedRoundTrip() throws IOException {
        service.save(davUrl(), "alice", "s3cret", "pass123", 0);
        Path snapshot = service.syncNow();
        String remoteName = snapshot.getFileName() + WebDavSyncService.ENC_SUFFIX;

        Path pending = service.restoreFromRemote(remoteName);
        assertTrue(Files.exists(pending));
        // 清掉待恢复文件与口令后再试：无口令应拒绝加密快照（留空是「不修改」，撤销须走显式清除）
        Files.deleteIfExists(pending);
        service.clearPassphrase();
        IOException e = assertThrows(IOException.class, () -> service.restoreFromRemote(remoteName));
        assertTrue(e.getMessage().contains("口令"));
        assertFalse(Files.exists(pending), "失败时不应留下 pending 文件");
    }

    @Test
    @DisplayName("未设置加密口令即拒绝同步：一个字节都不上传")
    void syncRefusedWithoutPassphrase() throws IOException {
        Files.createDirectories(paths.workspaceDir());
        Files.writeString(paths.workspaceDir().resolve("note.md"), "hello", StandardCharsets.UTF_8);
        service.save(davUrl(), "alice", "s3cret", "", 0);

        IOException e = assertThrows(IOException.class, service::syncNow);
        assertTrue(e.getMessage().contains("口令"), "失败原因要指向口令未设置: " + e.getMessage());
        assertTrue(remote.isEmpty(), "缺口令时不该有任何上传（含明文快照）");
        assertTrue(putOrder.isEmpty());
    }

    @Test
    @DisplayName("缺口令的自动同步按「尚未配置」跳过，补齐口令后照常触发")
    void autoSyncSkippedUntilPassphraseSet() throws IOException {
        service.save(davUrl(), "alice", "s3cret", "", 24);
        assertFalse(service.isSyncReady());
        assertTrue(service.syncIfDue().isEmpty());
        assertTrue(remote.isEmpty());

        service.savePassphrase("pass123");
        assertTrue(service.isSyncReady());
        assertTrue(service.syncIfDue().isPresent());
    }

    @Test
    @DisplayName("待恢复项可见可撤销：pending 与来源信息同生共死")
    void pendingRestoreIsVisibleAndCancellable() throws IOException {
        service.save(davUrl(), "alice", "s3cret", "pass123", 0);
        Path snapshot = service.syncNow();
        String remoteName = snapshot.getFileName() + WebDavSyncService.ENC_SUFFIX;
        assertTrue(service.pendingRestore().isEmpty(), "还没下载就不该有待恢复");

        service.restoreFromRemote(remoteName);
        WebDavSyncService.PendingRestore staged = service.pendingRestore().orElseThrow();
        assertEquals(remoteName, staged.source());
        assertTrue(staged.at().isAfter(Instant.now().minusSeconds(60)), "下载时间应为刚才");

        service.cancelPendingRestore();
        assertTrue(service.pendingRestore().isEmpty());
        assertFalse(Files.exists(paths.pendingRestoreFile()));
        assertFalse(Files.exists(paths.pendingRestoreInfoFile()));
    }

    @Test
    @DisplayName("远端快照尾部截断：完整性校验拦住，不排进恢复队列")
    void truncatedRemoteSnapshotIsRejected() throws IOException {
        // 明文书写的远端名：上传侧已强制加密，但历史遗留的明文快照仍须能下载，坏的则拦得住
        Path snapshot = new DbBackupService(db, paths.backupsDir()).backupNow().orElseThrow();
        byte[] bytes = Files.readAllBytes(snapshot);
        assertTrue(bytes.length > 64, "样本快照太小，构造不出截断用例");
        remote.put("/dav/jwork-20260101-000000.db", Arrays.copyOf(bytes, bytes.length / 2));
        service.save(davUrl(), "alice", "s3cret", "pass123", 0);

        IOException e = assertThrows(IOException.class,
                () -> service.restoreFromRemote("jwork-20260101-000000.db"));
        assertTrue(e.getMessage().contains("完整性"), "失败原因要指向完整性校验: " + e.getMessage());
        assertFalse(Files.exists(paths.pendingRestoreFile()), "坏快照不该排进恢复队列");
        assertFalse(Files.exists(paths.pendingRestoreInfoFile()));
    }
}
