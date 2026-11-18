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

package com.gettyio.jwork.agent.domain.persist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * 基于 xerial 纯 Java 驱动的 SQLite 连接提供者。
 * <p>每次调用打开一个连接（SQLite 支持多连接 + WAL），DAO 用 try-with-resources 关闭。</p>
 *
 * @author gogym
 */
public class SqliteConnectionProvider implements ConnectionProvider {

    private final String jdbcUrl;

    /**
     * @param dbFile 数据库文件绝对路径，如 ~/.jwork/jwork.db；
     *               传 ":memory:" 使用内存库（仅测试）
     */
    public SqliteConnectionProvider(Path dbFile) {
        this("jdbc:sqlite:" + dbFile.toAbsolutePath());
        ensureParentDir(dbFile);
    }

    public SqliteConnectionProvider(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    private static void ensureParentDir(Path dbFile) {
        try {
            Path parent = dbFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法创建 SQLite 数据目录: " + dbFile, e);
        }
    }

    /** 打开新连接并应用 PRAGMA：外键约束、WAL 日志、5 秒忙等待；由调用方以 try-with-resources 关闭。 */
    @Override
    public Connection getConnection() throws SQLException {
        Properties props = new Properties();
        Connection conn = DriverManager.getConnection(jdbcUrl, props);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA busy_timeout = 5000");
        }
        return conn;
    }
}
