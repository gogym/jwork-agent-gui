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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlRunner} 事务语义单测：共用连接、异常回滚、嵌套并入外层、事务结束后连接归还。
 * <p>默认每条语句自开连接，多语句写入并不原子；这里盯的就是包进事务之后到底还剩下什么。</p>
 *
 * @author gogym
 */
class SqlRunnerTransactionTest {

    @TempDir
    Path tempDir;

    private SqlRunner runner;

    @BeforeEach
    void setUp() {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(tempDir.resolve("jwork.db"));
        new SqlSchemaInitializer(cp).initialize();
        runner = new SqlRunner(cp);
    }

    private void put(String key, String value) {
        runner.update("INSERT INTO settings (setting_key, setting_value) VALUES (?,?)", key, value);
    }

    private String get(String key) {
        return runner.queryOne("SELECT setting_value FROM settings WHERE setting_key=?",
                rs -> rs.getString(1), key).orElse(null);
    }

    private int rowCount() {
        return runner.queryOne("SELECT COUNT(*) AS n FROM settings", rs -> rs.getInt("n")).orElse(0);
    }

    @Test
    @DisplayName("提交：事务内全部写入在 work 返回后可见")
    void commitsOnSuccess() {
        runner.runInTransaction(() -> {
            put("a", "1");
            put("b", "2");
        });
        assertEquals("1", get("a"));
        assertEquals("2", get("b"));
    }

    @Test
    @DisplayName("回滚：后一条语句失败时，前一条已写的也不留在库里")
    void rollsBackOnFailure() {
        PersistException thrown = assertThrows(PersistException.class, () ->
                runner.runInTransaction(() -> {
                    put("a", "1");
                    put("a", "2"); // 唯一键冲突
                }));
        assertTrue(thrown.getMessage().contains("INSERT"), "异常信息应带上失败的语句");
        assertEquals(0, rowCount(), "已提交的写入必须一起回滚");
    }

    @Test
    @DisplayName("回滚：Error 同样整体回滚（不只 RuntimeException）")
    void rollsBackOnError() {
        assertThrows(StackOverflowError.class, () ->
                runner.runInTransaction(() -> {
                    put("a", "1");
                    throw new StackOverflowError("模拟 OOM 级失败");
                }));
        assertEquals(0, rowCount());
    }

    @Test
    @DisplayName("事务内读得到自己未提交的写入（共用同一个连接）")
    void readsOwnUncommittedWrites() {
        runner.runInTransaction(() -> {
            put("a", "1");
            assertEquals("1", get("a"), "事务内必须看得见本事务的写入，否则成对写入无法自检");
        });
        assertEquals("1", get("a"));
    }

    @Test
    @DisplayName("嵌套并入外层：内层返回不等于落盘，外层回滚时内层一起消失")
    void nestedJoinsOuterTransaction() {
        AtomicInteger innerSeen = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> runner.runInTransaction(() -> {
            put("outer", "1");
            runner.runInTransaction(() -> {
                put("inner", "2");
                innerSeen.incrementAndGet();
            });
            assertEquals(1, innerSeen.get(), "内层不应因「以为已提交」而重跑");
            throw new IllegalStateException("外层失败");
        }));
        assertEquals(0, rowCount(), "内层不能自己提交，否则原子性形同虚设");
    }

    @Test
    @DisplayName("返回值：callInTransaction 原样给出 work 的结果")
    void returnsWorkResult() {
        String value = runner.callInTransaction(() -> {
            put("a", "42");
            return get("a");
        });
        assertEquals("42", value);
    }

    @Test
    @DisplayName("事务边界只包 work：结束即退出，后续语句不再挂在事务上")
    void transactionScopeEndsWithWork() {
        assertFalse(runner.inTransaction());
        runner.runInTransaction(() -> {
            assertTrue(runner.inTransaction());
            put("a", "1");
        });
        assertFalse(runner.inTransaction());
        // 回滚失败也不会把线程卡在事务态：异常路径同样清干净
        assertThrows(IllegalStateException.class, () -> runner.runInTransaction(() -> {
            throw new IllegalStateException("boom");
        }));
        assertFalse(runner.inTransaction());
        put("b", "2");
        assertEquals(2, rowCount());
    }
}
