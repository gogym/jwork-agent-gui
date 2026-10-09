package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.McpServerConfig;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import com.gettyio.gim.agent.domain.persist.SqlSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpAdminService} 「测试连接」支撑方法单测：探测状态回写与智能体运行时刷新委托。
 * 全部落临时 SQLite（与 SecuritySandboxConfigTest 同模式）；agent 表为空时
 * {@link AgentAdminService} 不会触达网关，故网关位可传 null。
 */
class McpAdminServiceTest {

    @TempDir
    Path tempDir;

    private GimDatabase db;
    private McpAdminService service;

    @BeforeEach
    void setUp() throws Exception {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(tempDir.resolve("gim.db"));
        new SqlSchemaInitializer(cp).initialize();
        db = new GimDatabase(cp);
        service = new McpAdminService(db, new AgentAdminService(db, null));
    }

    private McpServerConfig insertRow(String code) {
        McpServerConfig m = new McpServerConfig();
        m.setServerCode(code);
        m.setServerName("探针测试");
        m.setTransportType("STDIO");
        m.setStatus("UNKNOWN");
        db.mcpServerDao().insert(m);
        return m;
    }

    @Test
    @DisplayName("recordStatus：状态回写可持久化回读，同值重复探测不产生多余更新")
    void recordStatusRoundTrips() throws Exception {
        insertRow("m1");

        service.recordStatus("m1", true);
        McpServerConfig afterUp = db.mcpServerDao().findByCode("m1").orElseThrow();
        assertEquals("CONNECTED", afterUp.getStatus());
        LocalDateTime stamp = afterUp.getUpdatedAt();

        // 同值重复探测：状态保持且未触发 UPDATE（updatedAt 不变）
        Thread.sleep(20);
        service.recordStatus("m1", true);
        McpServerConfig afterRepeat = db.mcpServerDao().findByCode("m1").orElseThrow();
        assertEquals("CONNECTED", afterRepeat.getStatus());
        assertEquals(stamp, afterRepeat.getUpdatedAt(), "同值探测不应刷新 updated_at");

        service.recordStatus("m1", false);
        assertEquals("DISCONNECTED", db.mcpServerDao().findByCode("m1").orElseThrow().getStatus());
    }

    @Test
    @DisplayName("recordStatus：未知 serverCode 静默忽略，不抛异常")
    void recordStatusMissingCodeIsNoOp() {
        assertDoesNotThrow(() -> service.recordStatus("m-missing", true));
    }

    @Test
    @DisplayName("refreshAgentRuntimes：无智能体引用时安全空转")
    void refreshAgentRuntimesSafeWithoutReferences() {
        insertRow("m2");
        assertDoesNotThrow(() -> service.refreshAgentRuntimes("m2"));
        assertTrue(db.mcpServerDao().findByCode("m2").isPresent());
    }
}
