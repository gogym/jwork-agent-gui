package com.gettyio.gim.agent.domain.backup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RestoreApplier} 单测：待恢复快照应用（替换 + 旧库备份）、非法快照拒绝（原库不变）、
 * 无待恢复文件时为空操作。全程真实 SQLite 文件，无外部依赖。
 */
class RestoreApplierTest {

    @TempDir
    Path tempDir;

    /** 建一个带单行数据的库并返回路径。 */
    private Path createDb(Path file, String value) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE t(v TEXT)");
            stmt.execute("INSERT INTO t VALUES ('" + value + "')");
        }
        return file;
    }

    /** 读取单表单值。 */
    private String query(Path db, String sql) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    @DisplayName("存在待恢复快照：替换当前库并先备份旧库，pending 清理")
    void appliesPendingWithBackup() throws Exception {
        Path db = createDb(tempDir.resolve("jwork.db"), "current");
        Path pending = createDb(tempDir.resolve("restore/pending.db"), "restored");
        Path backups = tempDir.resolve("backups");

        assertTrue(RestoreApplier.applyIfPresent(db, pending, backups));

        assertFalse(Files.exists(pending), "应用后 pending 应被清理");
        assertEquals("restored", query(db, "SELECT v FROM t"), "当前库应变为快照内容");
        try (Stream<Path> s = Files.list(backups)) {
            List<Path> files = s.toList();
            assertEquals(1, files.size(), "旧库应留下一份恢复前备份");
            assertEquals("current", query(files.get(0), "SELECT v FROM t"));
        }
    }

    @Test
    @DisplayName("待恢复文件头非法：拒绝应用，原库与 pending 均保留")
    void keepsOriginalWhenPendingCorrupt() throws Exception {
        Path db = createDb(tempDir.resolve("jwork.db"), "current");
        Path pending = tempDir.resolve("restore/pending.db");
        Files.createDirectories(pending.getParent());
        Files.writeString(pending, "not a sqlite file");
        Path backups = tempDir.resolve("backups");

        assertFalse(RestoreApplier.applyIfPresent(db, pending, backups));

        assertEquals("current", query(db, "SELECT v FROM t"), "原库保持不变");
        assertTrue(Files.exists(pending), "非法 pending 不应被删除");
        assertFalse(Files.exists(backups), "未应用时不应产生备份");
    }

    @Test
    @DisplayName("无待恢复文件：空操作返回 false")
    void noopWhenPendingMissing() {
        assertFalse(RestoreApplier.applyIfPresent(
                tempDir.resolve("jwork.db"),
                tempDir.resolve("restore/pending.db"),
                tempDir.resolve("backups")));
    }
}
