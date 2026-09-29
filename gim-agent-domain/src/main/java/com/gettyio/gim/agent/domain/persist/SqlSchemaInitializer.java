package com.gettyio.gim.agent.domain.persist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

/**
 * 读取 classpath:db/schema.sql 并执行，幂等建表
 */
public class SqlSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SqlSchemaInitializer.class);

    private static final String DEFAULT_RESOURCE = "db/schema.sql";

    private final ConnectionProvider connectionProvider;

    public SqlSchemaInitializer(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    public void initialize() {
        initialize(DEFAULT_RESOURCE);
    }

    public void initialize(String classpathResource) {
        String script = loadScript(classpathResource);
        try (Connection conn = connectionProvider.getConnection();
             Statement st = conn.createStatement()) {
            // schema.sql 各语句以分号分隔；无存储过程，直接按语句切分执行
            for (String stmt : splitStatements(script)) {
                st.execute(stmt);
            }
            migrate(st);
            log.info("SQLite schema 初始化完成（资源 {}）", classpathResource);
        } catch (Exception e) {
            throw new PersistException("初始化 SQLite schema 失败", e);
        }
    }

    /**
     * 增量迁移：为既有数据库补齐后续新增的列（幂等）。
     * <p>CREATE TABLE IF NOT EXISTS 不会给已存在的表加列，故对老库用 ALTER 补齐。</p>
     */
    private void migrate(Statement st) throws SQLException {
        addColumnIfMissing(st, "sessions", "workspace_id", "TEXT");
    }

    private void addColumnIfMissing(Statement st, String table, String column, String type) throws SQLException {
        boolean exists = false;
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("迁移：为表 {} 增加列 {}", table, column);
        }
    }

    private String loadScript(String classpathResource) {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new PersistException("找不到 schema 资源: " + classpathResource);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            if (e instanceof PersistException pe) {
                throw pe;
            }
            throw new PersistException("读取 schema 资源失败: " + classpathResource, e);
        }
    }

    private java.util.List<String> splitStatements(String script) {
        // 去掉注释行后按分号切分
        String cleaned = script.lines()
                .map(line -> line.trim())
                .filter(line -> !line.isEmpty() && !line.startsWith("--"))
                .collect(Collectors.joining("\n"));
        java.util.List<String> statements = new java.util.ArrayList<>();
        for (String part : cleaned.split(";")) {
            String sql = part.trim();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        return statements;
    }
}
