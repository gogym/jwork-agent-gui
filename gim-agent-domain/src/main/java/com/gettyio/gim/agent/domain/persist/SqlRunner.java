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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 轻量 SQL 执行器：不引 ORM，桌面单库够用。
 * <p>约定：LocalDateTime 以 ISO-8601 字符串存于 TEXT 列。</p>
 *
 * @author gogym
 */
public class SqlRunner {

    private final ConnectionProvider connectionProvider;

    public SqlRunner(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    public ConnectionProvider getConnectionProvider() {
        return connectionProvider;
    }

    /** 执行 UPDATE/DELETE 等更新语句，返回影响行数；SQL 异常包装为 {@link PersistException}。 */
    public int update(String sql, Object... params) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistException("执行更新失败: " + sql, e);
        }
    }

    /**
     * 插入并返回自增主键
     */
    public long insert(String sql, Object... params) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new PersistException("插入未影响任何行");
            }
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        } catch (SQLException e) {
            throw new PersistException("执行插入失败: " + sql, e);
        }
    }

    /** 执行查询并把每行映射为 T；SQL 异常包装为 {@link PersistException}。 */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapper.map(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new PersistException("执行查询失败: " + sql, e);
        }
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> list = query(sql, mapper, params);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    private void bind(PreparedStatement ps, Object... params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof LocalDateTime ldt) {
                ps.setString(i + 1, ldt.toString());
            } else {
                ps.setObject(i + 1, p);
            }
        }
    }

    // ---- 列读取辅助 ----

    public static String getString(ResultSet rs, String column) throws SQLException {
        return rs.getString(column);
    }

    public static Long getLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    public static Integer getInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    public static Double getDouble(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    /** 读取时间列：优先 ISO-8601 字符串，兼容 epoch 毫秒与 JDBC {@link Timestamp} 存法；空返回 null。 */
    public static LocalDateTime getDateTime(ResultSet rs, String column) throws SQLException {
        String s = rs.getString(column);
        if (s == null || s.isBlank()) {
            // 兼容以 INTEGER 存的 epoch 毫秒
            Long epoch = getLong(rs, column);
            return epoch == null ? null
                    : java.time.Instant.ofEpochMilli(epoch).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        }
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            Timestamp ts = rs.getTimestamp(column);
            return ts == null ? null : ts.toLocalDateTime();
        }
    }
}
