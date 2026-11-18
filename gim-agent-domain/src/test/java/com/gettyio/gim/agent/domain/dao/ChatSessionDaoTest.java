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

package com.gettyio.gim.agent.domain.dao;

import com.gettyio.gim.agent.domain.TestDb;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ChatSessionDao} 工具条选择持久化单测：模型/审批档随会话行落盘、读回、清空回跟随默认，
 * 且改下拉不刷新 updated_at（不打乱最近排序）。真实 SQLite（{@code @TempDir} 文件）。
 *
 * @author gogym
 */
class ChatSessionDaoTest {

    @TempDir
    Path tempDir;

    private ChatSessionDao dao;

    @BeforeEach
    void setUp() {
        dao = new ChatSessionDao(TestDb.runnerAt(tempDir.resolve("gim.db")));
    }

    private ChatSession seed(String sessionId) {
        ChatSession s = new ChatSession();
        s.setSessionId(sessionId);
        s.setAgentCode("gim-agent");
        s.setTitle("会话");
        s.setStatus("ACTIVE");
        s.setMessageCount(0);
        dao.insert(s);
        return s;
    }

    @Test
    @DisplayName("工具条选择落会话行：模型与审批档读回一致")
    void toolbarPrefsRoundTrip() {
        seed("s-prefs");

        assertEquals(1, dao.updateToolbarPrefs("s-prefs", "glm-4.6", "auto"));

        ChatSession back = dao.findBySessionId("s-prefs").orElseThrow();
        assertEquals("glm-4.6", back.getModelCode());
        assertEquals("auto", back.getApprovalMode());
    }

    @Test
    @DisplayName("选回跟随默认：两列清 null，恢复时回退全局默认")
    void toolbarPrefsClearToNull() {
        seed("s-clear");
        dao.updateToolbarPrefs("s-clear", "glm-4.6", "readonly");

        dao.updateToolbarPrefs("s-clear", null, null);

        ChatSession back = dao.findBySessionId("s-clear").orElseThrow();
        assertNull(back.getModelCode());
        assertNull(back.getApprovalMode());
    }

    @Test
    @DisplayName("改下拉不碰 updated_at：会话不该被顶到最近排序最前")
    void toolbarPrefsDoNotTouchUpdatedAt() {
        seed("s-order");
        LocalDateTime before = dao.findBySessionId("s-order").orElseThrow().getUpdatedAt();

        dao.updateToolbarPrefs("s-order", "glm-4.6", "auto");

        assertEquals(before, dao.findBySessionId("s-order").orElseThrow().getUpdatedAt());
    }

    @Test
    @DisplayName("insert 携带审批档：分支等直接插行的入口不丢选择")
    void insertCarriesApprovalMode() {
        ChatSession s = new ChatSession();
        s.setSessionId("s-insert");
        s.setAgentCode("gim-agent");
        s.setTitle("会话");
        s.setStatus("ACTIVE");
        s.setModelCode("glm-4.6");
        s.setApprovalMode("readonly");
        dao.insert(s);

        ChatSession back = dao.findBySessionId("s-insert").orElseThrow();
        assertEquals("glm-4.6", back.getModelCode());
        assertEquals("readonly", back.getApprovalMode());
    }
}