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

package com.gettyio.gim.agent.domain.persist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlSchemaInitializer} 单测：真实 schema.sql 解析、老库补列升级、幂等、约束削弱。
 * <p>这里测的是「升级」而不是「建库」：用户机器上的库多半是历史版本建的，
 * {@code CREATE TABLE IF NOT EXISTS} 对它们一行代码都不执行，新版本加的列必须靠补列出来。
 * 补不出来不会在启动时报错，而是聊天中途 {@code no such column} 打断整轮生成——所以每条补列
 * 路径都要拿真实 SQLite 文件跑一遍。</p>
 *
 * @author gogym
 */
class SqlSchemaInitializerTest {

    @TempDir
    Path dir;

    private static String shippedSchema() throws Exception {
        try (InputStream in = SqlSchemaInitializerTest.class.getClassLoader()
                .getResourceAsStream("db/schema.sql")) {
            assertNotNull(in, "db/schema.sql 必须在 classpath 上");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path dbFile(String name) {
        return dir.resolve(name);
    }

    private Connection open(Path db) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
    }

    private static boolean hasColumn(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasTable(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='"
                     + table + "'")) {
            return rs.next();
        }
    }

    @Test
    @DisplayName("解析 shipped schema.sql：表与列齐全，表级约束不被当成列")
    void parsesShippedSchema() throws Exception {
        Map<String, Map<String, String>> tables =
                SqlSchemaInitializer.parseTables(SqlSchemaInitializer.stripComments(shippedSchema()));

        assertTrue(tables.containsKey("sessions"), "sessions 要解析出来");
        assertTrue(tables.get("sessions").containsKey("compacted_tokens"), "压缩基准列要被认出来");
        assertTrue(tables.get("conversations").containsKey("last_input_tokens"));
        assertTrue(tables.get("knowledge_chunks").containsKey("embedding"));

        // tool_permission_rules 结尾是表级 UNIQUE (tool_name, rule_content)：它不是列
        assertEquals(List.of("id", "tool_name", "rule_content", "decision", "created_at", "expires_at"),
                new ArrayList<>(tables.get("tool_permission_rules").keySet()));
        assertFalse(tables.containsKey("unique"));
        for (Map<String, String> columns : tables.values()) {
            for (String definition : columns.values()) {
                assertFalse(definition.contains("(") && definition.trim().startsWith("("),
                        "列定义不该以括号开头（说明切分错位）: " + definition);
            }
        }
    }

    @Test
    @DisplayName("注释里的括号与引号不影响切分")
    void commentsDoNotConfuseParsing() {
        String script = """
                -- 老规矩（括号也别乱）
                CREATE TABLE IF NOT EXISTS t (
                    -- 说明 (' 引号 -- 不该切')
                    id INTEGER PRIMARY KEY,
                    -- 带分号; 的注释
                    name TEXT
                );
                """;
        Map<String, Map<String, String>> tables =
                SqlSchemaInitializer.parseTables(SqlSchemaInitializer.stripComments(script));
        assertEquals(List.of("id", "name"), new ArrayList<>(tables.get("t").keySet()));
    }

    @Test
    @DisplayName("老库升级：新版本加的列启动时补齐，旧行数据不丢，新表建出来")
    void legacyDatabaseGetsMissingColumns() throws Exception {
        Path db = dbFile("legacy.db");
        try (Connection conn = open(db); Statement st = conn.createStatement()) {
            // 历史版本的 sessions：没有 group_id / pinned / parent_session_id / compacted_tokens
            st.execute("CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "session_id TEXT NOT NULL UNIQUE, agent_code TEXT, title TEXT, "
                    + "created_at TEXT, updated_at TEXT)");
            st.execute("INSERT INTO sessions (session_id, agent_code, title) VALUES ('s-1','a','旧标题')");
            st.execute("CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "session_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT, created_at TEXT)");
        }

        new SqlSchemaInitializer(new SqliteConnectionProvider(db)).initialize();

        try (Connection conn = open(db)) {
            assertTrue(hasColumn(conn, "sessions", "compacted_tokens"), "压缩基准列应被补出");
            assertTrue(hasColumn(conn, "sessions", "group_id"), "分组列应被补出");
            assertTrue(hasColumn(conn, "sessions", "pinned"), "置顶列应被补出");
            assertTrue(hasColumn(conn, "conversations", "last_input_tokens"), "上下文体量列应被补出");
            assertTrue(hasTable(conn, "session_groups"), "整张新表由建表语句负责");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT title, compacted_tokens, pinned FROM sessions "
                         + "WHERE session_id='s-1'")) {
                assertTrue(rs.next(), "老行必须还在");
                assertEquals("旧标题", rs.getString("title"));
                assertNull(rs.getObject("compacted_tokens"), "补出的列对历史行为空");
                assertEquals(0, rs.getInt("pinned"), "DEFAULT 0 要生效");
            }
        }

        // 幂等：再跑一次不报错、不改数据
        new SqlSchemaInitializer(new SqliteConnectionProvider(db)).initialize();
        try (Connection conn = open(db);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sessions")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("结构版本落进 PRAGMA user_version，供诊断与备份恢复判版本")
    void stampsSchemaVersion() throws Exception {
        Path db = dbFile("versioned.db");
        new SqlSchemaInitializer(new SqliteConnectionProvider(db)).initialize();
        try (Connection conn = open(db);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            assertTrue(rs.next());
            assertEquals(SqlSchemaInitializer.SCHEMA_VERSION, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("库里有多出来的表/列：只记日志不炸，数据原样保留")
    void unknownStructuresAreNotFatal() throws Exception {
        Path db = dbFile("extra.db");
        try (Connection conn = open(db); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE notes_from_newer_version (id INTEGER PRIMARY KEY, body TEXT)");
            st.execute("CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "session_id TEXT NOT NULL UNIQUE, future_flag INTEGER, created_at TEXT)");
        }

        new SqlSchemaInitializer(new SqliteConnectionProvider(db)).initialize();

        try (Connection conn = open(db)) {
            assertTrue(hasTable(conn, "notes_from_newer_version"), "本程序不认识的东西不该被删掉");
            assertTrue(hasColumn(conn, "sessions", "future_flag"), "多余的列保持原样");
            assertTrue(hasColumn(conn, "sessions", "compacted_tokens"), "缺的列照样补齐");
        }
    }

    @Test
    @DisplayName("补列削弱加不上的约束：UNIQUE/主键/外键去掉，无默认值的 NOT NULL 放开")
    void addColumnWeakensUnsupportedConstraints() {
        assertEquals("ALTER TABLE \"t\" ADD COLUMN \"c\" TEXT",
                SqlSchemaInitializer.addColumnDdl("t", "c", "TEXT UNIQUE"));
        assertEquals("ALTER TABLE \"t\" ADD COLUMN \"id\" INTEGER",
                SqlSchemaInitializer.addColumnDdl("t", "id", "INTEGER PRIMARY KEY AUTOINCREMENT"));
        assertEquals("ALTER TABLE \"t\" ADD COLUMN \"c\" TEXT",
                SqlSchemaInitializer.addColumnDdl("t", "c", "TEXT NOT NULL"));
        assertEquals("ALTER TABLE \"t\" ADD COLUMN \"c\" INTEGER NOT NULL DEFAULT 0",
                SqlSchemaInitializer.addColumnDdl("t", "c", "INTEGER NOT NULL DEFAULT 0"));
        // 无定义（schema 里列名后什么都没有）：SQLite 允许无类型列，照原样补
        assertEquals("ALTER TABLE \"t\" ADD COLUMN \"c\"",
                SqlSchemaInitializer.addColumnDdl("t", "c", null));
    }
}
