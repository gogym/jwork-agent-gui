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

package com.gettyio.gim.agent.domain.backup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqliteSnapshotValidator} 单测：头校验与完整性校验各自放行/拦住什么，以及校验后
 * 不在快照旁留下 WAL/SHM 边车（恢复只搬主文件，边车里的页会跟着丢）。真实 SQLite，无外部依赖。
 *
 * @author gogym
 */
class SqliteSnapshotValidatorTest {

    @TempDir
    Path tempDir;

    /** 建一个页数足够多、截断后必然缺页的库。 */
    private Path createDb(Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE t(v TEXT)");
            for (int i = 0; i < 500; i++) {
                stmt.execute("INSERT INTO t VALUES ('row-" + i + "-" + "x".repeat(200) + "')");
            }
        }
        return file;
    }

    @Test
    @DisplayName("有效快照：头校验与完整性校验都通过")
    void acceptsValidSnapshot() throws Exception {
        Path db = createDb(tempDir.resolve("good.db"));

        assertTrue(SqliteSnapshotValidator.hasValidHeader(db));
        assertTrue(SqliteSnapshotValidator.passesIntegrityCheck(db));
    }

    @Test
    @DisplayName("完整性校验只在副本上跑：不给快照留下 -wal/-shm 边车")
    void integrityCheckLeavesNoSidecars() throws Exception {
        Path db = createDb(tempDir.resolve("no-sidecar.db"));

        assertTrue(SqliteSnapshotValidator.passesIntegrityCheck(db));

        assertFalse(Files.exists(db.resolveSibling("no-sidecar.db-wal")), "边车文件会被恢复流程漏掉，等于丢已提交页");
        assertFalse(Files.exists(db.resolveSibling("no-sidecar.db-shm")));
    }

    @Test
    @DisplayName("截断的快照：头校验放行，完整性校验拦住")
    void truncatedSnapshotFailsIntegrityOnly() throws Exception {
        Path db = createDb(tempDir.resolve("truncated.db"));
        byte[] bytes = Files.readAllBytes(db);
        Files.write(db, Arrays.copyOf(bytes, bytes.length / 2));

        assertTrue(SqliteSnapshotValidator.hasValidHeader(db), "魔数在最前面，头校验对截断无能为力");
        assertFalse(SqliteSnapshotValidator.passesIntegrityCheck(db));
    }

    @Test
    @DisplayName("非数据库文件：两个校验都拒绝")
    void nonDatabaseRejected() throws Exception {
        Path junk = tempDir.resolve("junk.db");
        Files.writeString(junk, "not a sqlite file at all");

        assertFalse(SqliteSnapshotValidator.hasValidHeader(junk));
        assertFalse(SqliteSnapshotValidator.passesIntegrityCheck(junk));
    }
}
