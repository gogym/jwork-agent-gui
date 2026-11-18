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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.function.Supplier;

/**
 * 轻量 SQL 执行器：不引 ORM，桌面单库够用。
 * <p>约定：LocalDateTime 以 ISO-8601 字符串存于 TEXT 列。</p>
 * <p>缺省每条语句自开一个连接（SQLite + WAL 支持多连接），因此<b>多语句操作默认不原子</b>。
 * 需要原子性就用 {@link #runInTransaction}/{@link #callInTransaction}：事务内的语句共用同一个连接，
 * 见类内 {@code txConnection}。</p>
 *
 * @author gogym
 */
public class SqlRunner {

    private static final Logger log = LoggerFactory.getLogger(SqlRunner.class);

    private final ConnectionProvider connectionProvider;

    /**
     * 当前线程的事务连接；非 null 即处于事务中，本类所有语句都复用它且不关闭。
     * <p>绑在线程上而非做成实例字段，是为了让并发线程各走各的连接（SQLite 靠文件锁串行化写入），
     * 同时又不必给每个 DAO 方法加连接参数。</p>
     */
    private final ThreadLocal<Connection> txConnection = new ThreadLocal<>();

    public SqlRunner(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    public ConnectionProvider getConnectionProvider() {
        return connectionProvider;
    }

    /**
     * 以事务执行 work：work 正常返回即提交，抛 {@link RuntimeException} 或 {@link Error} 即整体回滚。
     * <p>嵌套调用并入外层事务、由最外层定夺提交或回滚，所以 DAO 里可以放心自我包一层，
     * 不必先判断调用方是否已经在事务里。</p>
     * <p>事务只在<b>当前线程</b>生效：work 必须同步跑完，中途切到别的线程（Reactor 换调度器等）
     * 执行的语句不属于本事务。</p>
     */
    public <T> T callInTransaction(Supplier<T> work) {
        if (txConnection.get() != null) {
            return work.get();
        }
        Connection conn = null;
        try {
            conn = connectionProvider.getConnection();
            conn.setAutoCommit(false);
            txConnection.set(conn);
            T result = work.get();
            conn.commit();
            return result;
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw new PersistException("事务执行失败", e);
        } catch (RuntimeException | Error e) {
            rollbackQuietly(conn);
            throw e;
        } finally {
            txConnection.remove();
            releaseTransactionConnection(conn);
        }
    }

    /** {@link #callInTransaction(Supplier)} 的无返回值版；名字错开是为了不让 {@code () -> foo()} 形 lambda 产生重载歧义。 */
    public void runInTransaction(Runnable work) {
        callInTransaction(() -> {
            work.run();
            return null;
        });
    }

    /** 当前线程是否正处于事务中（诊断与测试用）。 */
    public boolean inTransaction() {
        return txConnection.get() != null;
    }

    /** 执行 UPDATE/DELETE 等更新语句，返回影响行数；SQL 异常包装为 {@link PersistException}。 */
    public int update(String sql, Object... params) {
        try {
            return withConnection(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    bind(ps, params);
                    return ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            throw new PersistException("执行更新失败: " + sql, e);
        }
    }

    /**
     * 插入并返回自增主键
     */
    public long insert(String sql, Object... params) {
        try {
            return withConnection(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    bind(ps, params);
                    int rows = ps.executeUpdate();
                    if (rows == 0) {
                        throw new PersistException("插入未影响任何行");
                    }
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        return keys.next() ? keys.getLong(1) : -1L;
                    }
                }
            });
        } catch (SQLException e) {
            throw new PersistException("执行插入失败: " + sql, e);
        }
    }

    /** 执行查询并把每行映射为 T；SQL 异常包装为 {@link PersistException}。 */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try {
            return withConnection(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    bind(ps, params);
                    try (ResultSet rs = ps.executeQuery()) {
                        List<T> list = new ArrayList<>();
                        while (rs.next()) {
                            list.add(mapper.map(rs));
                        }
                        return list;
                    }
                }
            });
        } catch (SQLException e) {
            throw new PersistException("执行查询失败: " + sql, e);
        }
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> list = query(sql, mapper, params);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 在既有连接上执行；事务中复用事务连接且不关闭，否则新开一个用毕关闭。 */
    private <T> T withConnection(SqlWork<T> work) throws SQLException {
        Connection inTx = txConnection.get();
        if (inTx != null) {
            return work.apply(inTx);
        }
        try (Connection conn = connectionProvider.getConnection()) {
            return work.apply(conn);
        }
    }

    private interface SqlWork<T> {
        T apply(Connection conn) throws SQLException;
    }

    private void rollbackQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.rollback();
        } catch (SQLException e) {
            // 回滚失败不掩盖原始异常，只留证据：残留的是未提交事务，连接随即关闭，SQLite 会丢弃它
            log.warn("事务回滚失败", e);
        }
    }

    private void releaseTransactionConnection(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.setAutoCommit(true);
            conn.close();
        } catch (SQLException e) {
            log.warn("释放事务连接失败", e);
        }
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
