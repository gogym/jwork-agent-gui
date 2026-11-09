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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 读取 classpath:db/schema.sql 并执行，把库结构对齐到该文件。
 *
 * <p>{@code schema.sql} 是库结构的唯一权威定义。它只做 {@code CREATE ... IF NOT EXISTS}，
 * 因此对<b>全新库</b>够用，对<b>老库</b>却什么都不做：表一旦已存在，SQLite 就跳过整条建表语句，
 * 新版本加在表里的列永远不会出现——用户不会在启动时看到报错，而是在聊天中途被一句
 * {@code [SQLITE_ERROR] no such column: compacted_tokens} 打断整轮生成。</p>
 *
 * <p>这里的做法是「以 schema.sql 为准做一次结构对齐」，启动即完成，无需用户操作：</p>
 * <ol>
 *   <li>先跑建表语句（新表建出来，老表原样留着）；</li>
 *   <li>逐表用 {@code PRAGMA table_info} 比对实际列，缺的列补 {@code ALTER TABLE ADD COLUMN}
 *       ——幂等：列已存在就不动，所以重复启动没有副作用；</li>
 *   <li>再跑其余语句（索引等）——必须排在补列之后，否则老库里「索引建在还不存在的列上」会直接报错；</li>
 *   <li>写 {@code PRAGMA user_version} 记下结构版本，并把「库里有、schema 里没有」的差集记日志：
 *       那通常意味着这份库来自更高版本的程序或备份，本程序读不懂它的结构，早发现比运行中撞出来好。</li>
 * </ol>
 *
 * <p>补列只做结构上的最小改动：{@code ADD COLUMN} 带不上 {@code UNIQUE}/{@code PRIMARY KEY}/
 * {@code REFERENCES}，非空列没有默认值时也只能先放开非空约束（SQLite 不允许给非空表加无默认值的
 * NOT NULL 列）。这类削弱会 WARN——真需要约束的列请在 schema.sql 里改表并配一条数据迁移。</p>
 *
 * @author gogym
 */
public class SqlSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SqlSchemaInitializer.class);

    private static final String DEFAULT_RESOURCE = "db/schema.sql";

    /**
     * 结构版本，写入 {@code PRAGMA user_version}。
     * <p>改 schema.sql 的表或列时同步 +1：补列本身按 schema.sql 现算、幂等，不依赖这个数；
     * 它的作用是留下证据——出问题时能判断这份库是被哪个版本建过、是不是比本程序还新。</p>
     */
    public static final int SCHEMA_VERSION = 3;

    /** 建表语句头：{@code CREATE TABLE [IF NOT EXISTS] <名字> (} */
    private static final Pattern CREATE_TABLE_HEAD = Pattern.compile(
            "(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([`\"]?[A-Za-z_][\\w$]*[`\"]?)\\s*\\(");

    /** 表级约束的开头关键字：这些不是列名，别当成列去补 */
    private static final Set<String> TABLE_CONSTRAINT_KEYWORDS = Set.of(
            "primary", "unique", "check", "foreign", "constraint");

    private final ConnectionProvider connectionProvider;

    public SqlSchemaInitializer(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    public void initialize() {
        initialize(DEFAULT_RESOURCE);
    }

    /**
     * 加载 classpath 建表脚本并把库结构对齐到它：建表 → 补列 → 建索引 → 落版本戳。
     * <p>资源缺失、补列失败都抛 {@link PersistException}：结构对不齐时后续每次 DAO 调用都会失败，
     * 让它死在启动日志里比死在对话中间有用。</p>
     */
    public void initialize(String classpathResource) {
        String script = stripComments(loadScript(classpathResource));
        List<String> statements = splitStatements(script);
        Map<String, Map<String, String>> expected = parseTables(script);
        try (Connection conn = connectionProvider.getConnection()) {
            runMatching(conn, statements, true);
            int added = reconcileColumns(conn, expected);
            runMatching(conn, statements, false);
            int stored = stampVersion(conn);
            reportDrift(conn, expected);
            if (stored > SCHEMA_VERSION) {
                log.warn("库结构版本高于本程序: user_version={}, 本程序={}（多半是恢复了更高版本的备份）",
                        stored, SCHEMA_VERSION);
            }
            log.info("SQLite schema 对齐完成（资源 {}, 版本 {}, 补列 {} 个）",
                    classpathResource, SCHEMA_VERSION, added);
        } catch (PersistException e) {
            throw e;
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

    /** 按分号切分语句（注释已先去）。 */
    private List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        for (String part : script.split(";")) {
            String sql = part.trim();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        return statements;
    }

    /**
     * 执行建表语句或非建表语句。
     * <p>拆两趟是补列能生效的前提：索引等语句引用了刚补出来的列，只能在补列之后跑。</p>
     */
    private void runMatching(Connection conn, List<String> statements, boolean createTables) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String sql : statements) {
                boolean isCreateTable = sql.regionMatches(true, 0, "CREATE TABLE", 0, "CREATE TABLE".length());
                if (isCreateTable == createTables) {
                    st.execute(sql);
                }
            }
        }
    }

    /**
     * 逐表比对实际列与 schema.sql 声明的列，缺的用 {@code ALTER TABLE ADD COLUMN} 补上。
     *
     * @return 本次补出的列数（老库升级时 &gt; 0，稳态下恒为 0）
     */
    private int reconcileColumns(Connection conn, Map<String, Map<String, String>> expected) throws SQLException {
        int added = 0;
        for (Map.Entry<String, Map<String, String>> table : expected.entrySet()) {
            String name = table.getKey();
            if (!exists(conn, name)) {
                // 表都不存在：建表语句会整体新建，无需逐列补
                continue;
            }
            Set<String> present = columnsOf(conn, name);
            for (Map.Entry<String, String> column : table.getValue().entrySet()) {
                if (present.contains(column.getKey().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String ddl = addColumnDdl(name, column.getKey(), column.getValue());
                try (Statement st = conn.createStatement()) {
                    st.execute(ddl);
                } catch (SQLException e) {
                    throw new PersistException("补齐历史库缺失列失败: " + name + "." + column.getKey(), e);
                }
                added++;
                log.info("补齐历史库缺失列: {}.{}（定义 {}）", name, column.getKey(),
                        column.getValue().isBlank() ? "无" : column.getValue());
            }
        }
        return added;
    }

    /**
     * 生成补列语句，并把 {@code ADD COLUMN} 加不出来的约束削掉。
     * <p>包内可见便于单测：削弱是安全边界（{@code UNIQUE}/{@code NOT NULL} 静默消失），
     * 削了什么、为什么削必须钉得住。</p>
     */
    static String addColumnDdl(String table, String column, String definition) {
        String def = definition == null ? "" : definition.trim();
        String upper = def.toUpperCase(Locale.ROOT);
        if (upper.contains("UNIQUE") || upper.contains("PRIMARY KEY") || upper.contains("REFERENCES")
                || upper.contains("CHECK")) {
            def = def.replaceAll("(?i)\\b(unique|primary\\s+key|references|check)\\b[^,]*", "").trim();
            upper = def.toUpperCase(Locale.ROOT);
            log.warn("补列只能削弱约束: {}.{} 原定义带唯一/主键/外键/检查约束，已按普通列添加", table, column);
        }
        if (upper.contains("NOT NULL") && !upper.contains("DEFAULT")) {
            // SQLite 不允许给非空表加无默认值的 NOT NULL 列；先放开非空，由业务层兜住
            def = def.replaceAll("(?i)\\bnot\\s+null\\b", "").trim();
            log.warn("补列无法携带 NOT NULL（无默认值）: {}.{} 已按可空列添加", table, column);
        }
        return "ALTER TABLE " + quote(table) + " ADD COLUMN " + quote(column) + (def.isEmpty() ? "" : " " + def);
    }

    /** 落结构版本戳，返回库里原先记录的版本（0=没有戳，多半是版本机制之前建的库）。 */
    private int stampVersion(Connection conn) throws SQLException {
        int stored = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            if (rs.next()) {
                stored = rs.getInt(1);
            }
        }
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA user_version = " + SCHEMA_VERSION);
        }
        return stored;
    }

    /** 把「库里有、schema 里没有」的表和列写进日志：这些结构本程序不认识，读写可能出问题。 */
    private void reportDrift(Connection conn, Map<String, Map<String, String>> expected) throws SQLException {
        List<String> unknownTables = new ArrayList<>();
        List<String> unknownColumns = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' "
                     + "AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) {
                String table = rs.getString(1);
                if (!expected.containsKey(table)) {
                    unknownTables.add(table);
                    continue;
                }
                for (String column : columnsOf(conn, table)) {
                    if (!expected.get(table).containsKey(column)) {
                        unknownColumns.add(table + "." + column);
                    }
                }
            }
        }
        if (!unknownTables.isEmpty()) {
            log.warn("库中存在本程序不认识的表（可能来自更高版本或备份恢复）: {}", unknownTables);
        }
        if (!unknownColumns.isEmpty()) {
            log.warn("库中存在本程序不认识的列（本程序不会写入，值将保持原样）: {}", unknownColumns);
        }
    }

    private boolean exists(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 表的实际列名集合（小写，SQLite 列名大小写不敏感）。 */
    private Set<String> columnsOf(Connection conn, String table) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + quote(table) + ")")) {
            while (rs.next()) {
                out.add(rs.getString("name").toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * 从脚本解析出「表 → 列 → 列定义」。
     * <p>括号按深度配对（列定义里可以带 {@code DECIMAL(10,2)} 之类的括号），逗号只在深度 0 处分项，
     * 表级约束（{@code PRIMARY KEY (a,b)}／{@code UNIQUE (...)}）整条跳过——它们不是列。</p>
     */
    static Map<String, Map<String, String>> parseTables(String script) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        Matcher head = CREATE_TABLE_HEAD.matcher(script);
        while (head.find()) {
            String table = unquote(head.group(1));
            int bodyStart = head.end();
            int bodyEnd = matchingParen(script, bodyStart - 1);
            if (bodyEnd < 0) {
                log.warn("建表语句括号不配对，跳过结构对齐: table={}", table);
                continue;
            }
            Map<String, String> columns = new LinkedHashMap<>();
            for (String item : splitTopLevel(script.substring(bodyStart, bodyEnd))) {
                String trimmed = item.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String rawName = firstToken(trimmed);
                if (rawName == null) {
                    continue;
                }
                String name = unquote(rawName);
                if (TABLE_CONSTRAINT_KEYWORDS.contains(name)) {
                    continue;
                }
                columns.put(name, trimmed.substring(rawName.length()).trim());
            }
            out.put(table, columns);
        }
        return out;
    }

    /** 去掉行内/整行 {@code --} 注释（引号内的 {@code --} 不算注释）。 */
    static String stripComments(String script) {
        StringBuilder out = new StringBuilder(script.length());
        for (String line : script.split("\n", -1)) {
            boolean inSingle = false;
            int cut = -1;
            for (int i = 0; i < line.length() - 1; i++) {
                char c = line.charAt(i);
                if (c == '\'') {
                    inSingle = !inSingle;
                } else if (!inSingle && c == '-' && line.charAt(i + 1) == '-') {
                    cut = i;
                    break;
                }
            }
            out.append(cut < 0 ? line : line.substring(0, cut)).append('\n');
        }
        return out.toString();
    }

    /** 从 {@code openIndex} 处的左括号找配对的右括号下标；找不到返回 -1。 */
    private static int matchingParen(String text, int openIndex) {
        int depth = 0;
        boolean inSingle = false;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                inSingle = !inSingle;
                continue;
            }
            if (inSingle) {
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 按深度 0 的逗号切分列定义。 */
    private static List<String> splitTopLevel(String body) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        boolean inSingle = false;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') {
                inSingle = !inSingle;
            } else if (!inSingle && c == '(') {
                depth++;
            } else if (!inSingle && c == ')') {
                depth--;
            } else if (!inSingle && c == ',' && depth == 0) {
                items.add(body.substring(start, i));
                start = i + 1;
            }
        }
        items.add(body.substring(start));
        return items;
    }

    /** 一段列定义里的首个 token（列名），去掉反引号/双引号。 */
    private static String firstToken(String item) {
        int end = 0;
        while (end < item.length() && !Character.isWhitespace(item.charAt(end))
                && item.charAt(end) != '(') {
            end++;
        }
        return end == 0 ? null : unquote(item.substring(0, end));
    }

    private static String unquote(String identifier) {
        return identifier.replace("`", "").replace("\"", "").trim().toLowerCase(Locale.ROOT);
    }

    /** 标识符加双引号（SQLite 标准），内部双引号按 SQL 规则成对转义。 */
    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
