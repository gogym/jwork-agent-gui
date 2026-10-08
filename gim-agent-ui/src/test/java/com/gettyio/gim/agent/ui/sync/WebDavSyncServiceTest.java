package com.gettyio.gim.agent.ui.sync;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.backup.DbBackupService;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import com.gettyio.gim.agent.domain.persist.SqlSchemaInitializer;
import com.gettyio.gim.agent.domain.security.AesGcmSecretCipher;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebDavSyncService} 单测：配置读写（密码加密往返、URL 归一化）与
 * 同步上传（内嵌 HttpServer 模拟 WebDAV 的 PUT/PROPFIND），全部无外部依赖。
 */
class WebDavSyncServiceTest {

    @TempDir
    Path tempDir;

    private GimDatabase db;
    private GimPaths paths;
    private WebDavSyncService service;
    private HttpServer server;
    /** 模拟远端收到的 PUT：路径 + 请求体 */
    private final List<String[]> uploaded = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(tempDir.resolve("gim.db"));
        new SqlSchemaInitializer(cp).initialize();
        db = new GimDatabase(cp);
        paths = new GimPaths(tempDir);
        service = new WebDavSyncService(db, new AesGcmSecretCipher(tempDir.resolve("secret.key")),
                paths, new DbBackupService(db, paths.backupsDir()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dav/", exchange -> {
            String method = exchange.getRequestMethod();
            if ("PUT".equals(method)) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                synchronized (uploaded) {
                    uploaded.add(new String[]{exchange.getRequestURI().getPath(), new String(body, StandardCharsets.ISO_8859_1)});
                }
                exchange.sendResponseHeaders(201, -1);
            } else if ("PROPFIND".equals(method)) {
                byte[] xml = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                        + "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>/dav/</D:href>"
                        + "<D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>"
                        + "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/xml");
                exchange.sendResponseHeaders(207, xml.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(xml);
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String davUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/dav/";
    }

    @Test
    @DisplayName("save 归一化 URL 尾部斜杠，密码加密存储且可解密回读")
    void saveAndReadBack() {
        assertFalse(service.isConfigured());

        service.save(davUrl().substring(0, davUrl().length() - 1), "alice", "s3cret", 12);
        assertEquals(davUrl(), service.url());
        assertEquals("alice", service.username());
        assertEquals("s3cret", service.passwordPlain());
        assertNotEquals("s3cret",
                db.settingDao().getValue(WebDavSyncService.KEY_PASSWORD).orElseThrow());
        assertEquals(12, service.intervalHours());
        assertTrue(service.isConfigured());

        // 无尾部斜杠自动补齐；空密码表示清除
        service.save(davUrl(), "bob", "", 0);
        assertEquals(davUrl(), service.url());
        assertEquals("", service.passwordPlain());
        assertEquals(0, service.intervalHours());
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
        GimDatabase empty = new GimDatabase(raw);
        assertTrue(new DbBackupService(empty, paths.backupsDir()).backupNow().isEmpty());
    }

    @Test
    @DisplayName("syncNow 上传快照与 workspace 压缩包并记录时间；周期内 syncIfDue 不再触发")
    void syncNowUploads() throws IOException {
        // workspace 预置一个文件，压缩包应包含其内容
        Files.createDirectories(paths.workspaceDir());
        Files.writeString(paths.workspaceDir().resolve("note.md"), "hello sync", StandardCharsets.UTF_8);

        service.save(davUrl(), "alice", "s3cret", 24);
        assertTrue(service.lastSyncAt().isEmpty());

        Path snapshot = service.syncNow();
        assertTrue(Files.exists(snapshot));

        synchronized (uploaded) {
            assertEquals(2, uploaded.size());
            assertEquals("/dav/" + snapshot.getFileName(), uploaded.get(0)[0]);
            assertTrue(uploaded.get(0)[1].startsWith("SQLite format 3"), "第一个 PUT 应为 SQLite 快照");
            assertEquals("/dav/jwork-workspace-latest.zip", uploaded.get(1)[0]);
            assertTrue(uploaded.get(1)[1].startsWith("PK"), "第二个 PUT 应为 zip 压缩包");
        }
        assertTrue(service.lastSyncAt().isPresent());
        // 刚同步过：24h 周期内不应再触发
        assertTrue(service.syncIfDue().isEmpty());
    }
}
