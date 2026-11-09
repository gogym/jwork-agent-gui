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

package com.gettyio.gim.agent.core.chat;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.persist.ConnectionProvider;
import com.gettyio.gim.agent.domain.persist.PersistException;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 跨语句写入的原子性单测：消息与 {@code message_count}、分支会话的会话头与消息副本，
 * 任一步失败都必须整体回滚。
 * <p>故障压在 JDBC 连接层（{@link #failingOn}）而不是 mock DAO：成对写入能不能同生共死，
 * 取决于两条语句是否真的落在同一个事务连接上，mock 出来的「调用顺序」证明不了这件事。</p>
 *
 * @author gogym
 */
class ChatServiceAtomicityTest {

    @TempDir
    Path tempDir;

    /** SQL 里含该子串时 {@code prepareStatement} 直接失败；null 表示不注入故障。 */
    private final AtomicReference<String> failMarker = new AtomicReference<>();

    private GimDatabase db;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        db = new GimDatabase(failingOn(new SqliteConnectionProvider(tempDir.resolve("gim.db")), failMarker));
        db.initSchema();
        chatService = new ChatService(null, db, new GimPaths(tempDir.resolve("home")), null);
    }

    private int messageCount(String sessionId) {
        return db.sessionDao().findBySessionId(sessionId).map(ChatSession::getMessageCount).orElse(-1);
    }

    private int totalConversationRows() {
        return db.getRunner().queryOne("SELECT COUNT(*) AS n FROM conversations", rs -> rs.getInt("n")).orElse(-1);
    }

    @Test
    @DisplayName("删消息失败于计数回退：消息仍在，计数也没被单方面改过")
    void deleteMessageRollsBackCounterPair() {
        String sid = "s-atomic-delete";
        chatService.insertLocalExchange(sid, "gim-agent", "问", "答");
        Long assistantId = db.conversationDao().findLastAssistantId(sid);
        assertNotNull(assistantId);

        failMarker.set("UPDATE sessions");
        assertThrows(PersistException.class, () -> chatService.deleteMessage(sid, assistantId));
        failMarker.set(null);

        assertEquals(2, chatService.history(sid).size(), "删除已生效、计数没回退就是写歪了账");
        assertEquals(2, messageCount(sid));

        chatService.deleteMessage(sid, assistantId);
        assertEquals(1, chatService.history(sid).size());
        assertEquals(1, messageCount(sid), "计数按实际删除行数回退");
    }

    @Test
    @DisplayName("本地合成的一问一答：第二条语句失败时第一条也不留下")
    void localExchangeIsAllOrNothing() {
        String sid = "s-atomic-local";

        failMarker.set("UPDATE sessions");
        assertThrows(PersistException.class,
                () -> chatService.insertLocalExchange(sid, "gim-agent", "问", "答"));
        failMarker.set(null);

        assertEquals(0, totalConversationRows(), "半途失败不该留下「有提问、无回应」的残局");
    }

    @Test
    @DisplayName("分支中途失败：既不留下有头无身的会话，也不留下无主的副本消息")
    void branchRollsBackWholly() {
        String src = "s-branch-src";
        chatService.insertLocalExchange(src, "gim-agent", "问", "答");
        int sessionsBefore = db.sessionDao().findAll().size();

        failMarker.set("UPDATE sessions");
        assertThrows(PersistException.class, () -> chatService.branchSession(src));
        failMarker.set(null);

        assertEquals(sessionsBefore, db.sessionDao().findAll().size(), "分支会话头必须随消息一起回滚");
        assertEquals(2, totalConversationRows(), "副本消息一条都不该残留");
    }

    @Test
    @DisplayName("分支截止：只拷到指定消息（含），计数与实拷条数一致")
    void branchCopiesPrefixThroughCutoff() {
        String src = "s-branch-cutoff";
        chatService.insertLocalExchange(src, "gim-agent", "问", "答");
        Long firstUserRowId = db.conversationDao().findBySession(src).get(0).getId();

        String newId = chatService.forkSession(src, firstUserRowId);

        assertEquals(1, chatService.history(newId).size(), "截止线之后的消息不该被复制");
        assertEquals(1, messageCount(newId));
        assertEquals("问", chatService.history(newId).get(0).getContent());
    }

    // ===================== 故障注入 =====================

    /** 包装连接提供者：SQL 命中 {@code marker} 当前值时，{@code prepareStatement} 抛 SQLException。 */
    private static ConnectionProvider failingOn(ConnectionProvider delegate, AtomicReference<String> marker) {
        return new ConnectionProvider() {
            @Override
            public Connection getConnection() throws SQLException {
                Connection target = delegate.getConnection();
                return (Connection) Proxy.newProxyInstance(ChatServiceAtomicityTest.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                            if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0
                                    && args[0] instanceof String sql) {
                                String hit = marker.get();
                                if (hit != null && sql.contains(hit)) {
                                    throw new SQLException("注入失败: " + sql);
                                }
                            }
                            try {
                                return method.invoke(target, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause() == null ? e : e.getCause();
                            }
                        });
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }
}
