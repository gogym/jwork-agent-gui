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
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatService#forkSession} 分支语义单测：整会话复制、截止到指定消息（含）、
 * 未知主键退化全量复制。真实 SQLite（{@code @TempDir} 文件），消息经 DAO 直插，
 * 不依赖网关与流式管道。
 *
 * @author gogym
 */
class ChatServiceForkTest {

    @TempDir
    Path tempDir;

    private ChatService chatService;
    private GimDatabase db;

    @BeforeEach
    void setUp() {
        db = new GimDatabase(new SqliteConnectionProvider(tempDir.resolve("gim.db")));
        db.initSchema();
        chatService = new ChatService(null, db, new GimPaths(tempDir.resolve("home")), null);
    }

    /** 建源会话并直插 role/content 交替的消息，返回消息主键列表（按插入序）。 */
    private List<Long> seedSession(String sessionId, String... contents) {
        ChatSession s = new ChatSession();
        s.setSessionId(sessionId);
        s.setAgentCode("gim-agent");
        s.setTitle("源会话");
        s.setStatus("ACTIVE");
        s.setMessageCount(0);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        db.sessionDao().insert(s);

        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            ChatConversation m = new ChatConversation();
            m.setSessionId(sessionId);
            m.setAgentCode("gim-agent");
            m.setRole(i % 2 == 0 ? "user" : "assistant");
            m.setContent(contents[i]);
            m.setCreatedAt(LocalDateTime.now());
            db.conversationDao().insert(m);
            ids.add(m.getId());
        }
        return ids;
    }

    @Test
    @DisplayName("截止到指定消息：复制到该条（含）为止，parent_session_id 溯源")
    void forkTruncatesAtMessage() {
        String sid = "s-fork";
        List<Long> ids = seedSession(sid, "问题一", "回答一", "问题二", "回答二");

        String newId = chatService.forkSession(sid, ids.get(2));

        assertNotEquals(sid, newId);
        ChatSession copy = db.sessionDao().findBySessionId(newId).orElse(null);
        assertNotNull(copy);
        assertEquals(sid, copy.getParentSessionId());
        assertTrue(copy.getTitle().endsWith("（分支）"));

        List<ChatConversation> copied = chatService.history(newId);
        assertEquals(3, copied.size(), "应复制到第三条（含）");
        assertEquals("问题二", copied.get(copied.size() - 1).getContent());
        // 源会话原样保留
        assertEquals(4, chatService.history(sid).size());
    }

    @Test
    @DisplayName("非正主键：整会话全量复制（branchSession 等价语义）")
    void forkWithoutIdCopiesAll() {
        String sid = "s-all";
        seedSession(sid, "问题一", "回答一");

        String viaFork = chatService.forkSession(sid, 0L);
        String viaBranch = chatService.branchSession(sid);

        assertEquals(2, chatService.history(viaFork).size());
        assertEquals(2, chatService.history(viaBranch).size());
        assertEquals(sid, db.sessionDao().findBySessionId(viaBranch).orElseThrow().getParentSessionId());
    }

    @Test
    @DisplayName("主键不存在：找不到截止点退化全量复制")
    void forkUnknownIdFallsBackToFullCopy() {
        String sid = "s-unknown";
        seedSession(sid, "问题一", "回答一", "问题二");

        String newId = chatService.forkSession(sid, 999999L);

        assertEquals(3, chatService.history(newId).size());
    }

    @Test
    @DisplayName("message_count 与复制条数一致，空会话分支为 0")
    void forkCountsMessages() {
        String sid = "s-count";
        seedSession(sid, "问题一", "回答一");
        String emptyId = "s-empty";
        seedSession(emptyId);

        String newId = db.sessionDao().findBySessionId(chatService.forkSession(sid, 0L)).orElseThrow().getSessionId();
        assertEquals(Integer.valueOf(2), db.sessionDao().findBySessionId(newId).orElseThrow().getMessageCount());

        String newEmpty = chatService.forkSession(emptyId, 0L);
        assertEquals(Integer.valueOf(0), db.sessionDao().findBySessionId(newEmpty).orElseThrow().getMessageCount());
        assertTrue(chatService.history(newEmpty).isEmpty());
    }
}
