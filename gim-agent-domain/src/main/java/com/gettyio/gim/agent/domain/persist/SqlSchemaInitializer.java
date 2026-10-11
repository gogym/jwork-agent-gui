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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.stream.Collectors;

/**
 * 读取 classpath:db/schema.sql 并执行，幂等建表
 * <p>{@code schema.sql} 是库结构的唯一权威定义，只做 CREATE IF NOT EXISTS，不携带任何
 * 历史版本表/列的兼容迁移。</p>
 *
 * @author gogym
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

    /** 加载指定 classpath 资源（分号分段的建表脚本）并逐条执行；资源缺失或执行失败抛 {@link PersistException}。 */
    public void initialize(String classpathResource) {
        String script = loadScript(classpathResource);
        try (Connection conn = connectionProvider.getConnection();
             Statement st = conn.createStatement()) {
            // schema.sql 各语句以分号分隔；无存储过程，直接按语句切分执行
            for (String stmt : splitStatements(script)) {
                st.execute(stmt);
            }
            log.info("SQLite schema 初始化完成（资源 {}）", classpathResource);
        } catch (Exception e) {
            throw new PersistException("初始化 SQLite schema 失败", e);
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
