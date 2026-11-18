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

package com.gettyio.jwork.agent.domain.dao;

import com.gettyio.jwork.agent.domain.entity.ChatConversation;
import com.gettyio.jwork.agent.domain.persist.SqlRunner;
import com.gettyio.jwork.agent.domain.persist.SqlSchemaInitializer;
import com.gettyio.jwork.agent.domain.persist.SqliteConnectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TurnDraftDao} 单测：覆盖式检查点、提升所需的列往返、按会话丢弃。
 * <p>草稿是「崩溃前最后几秒的产出」唯一载体，读回的行必须能原样塞进 conversations，
 * 所以这里重点盯的是列往返，而不是 CRUD 形式。</p>
 *
 * @author gogym
 */
class TurnDraftDaoTest {

    @TempDir
    Path tempDir;

    private TurnDraftDao dao;
    private SqlRunner runner;

    @BeforeEach
    void setUp() {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(tempDir.resolve("jwork.db"));
        new SqlSchemaInitializer(cp).initialize();
        runner = new SqlRunner(cp);
        dao = new TurnDraftDao(runner);
    }

    private static ChatConversation draft(String sessionId, String content) {
        ChatConversation c = new ChatConversation();
        c.setSessionId(sessionId);
        c.setAgentCode("jwork-agent");
        c.setContent(content);
        c.setModelCode("gpt-test");
        c.setMetadata("{\"interrupted\":true}");
        c.setCreatedAt(LocalDateTime.now());
        return c;
    }

    @Test
    @DisplayName("同一会话只留最新一条：检查点覆盖写，不堆积历史")
    void saveOverwritesPerSession() {
        dao.save(draft("s1", "第一段"));
        dao.save(draft("s1", "第二段更长"));

        List<ChatConversation> all = dao.findAll();
        assertEquals(1, all.size(), "每会话最多一条草稿");
        assertEquals("第二段更长", all.get(0).getContent());
    }

    @Test
    @DisplayName("读回的行可直接提升：角色固定助手，用量/工具/时间戳列往返")
    void mapsBackAsAssistantRow() {
        ChatConversation c = draft("s1", "半截应答");
        c.setTokensInput(120);
        c.setTokensOutput(30);
        c.setTokensCached(7);
        c.setLastInputTokens(99);
        c.setLlmCalls(2);
        c.setLlmElapsedMillis(1500L);
        c.setToolCalls("[{\"toolName\":\"read_file\"}]");
        dao.save(c);

        ChatConversation read = dao.findAll().get(0);
        assertEquals("assistant", read.getRole(), "草稿按定义是助手轮");
        assertEquals("半截应答", read.getContent());
        assertEquals(Integer.valueOf(120), read.getTokensInput());
        assertEquals(Integer.valueOf(30), read.getTokensOutput());
        assertEquals(Integer.valueOf(7), read.getTokensCached());
        assertEquals(Integer.valueOf(99), read.getLastInputTokens());
        assertEquals(Integer.valueOf(2), read.getLlmCalls());
        assertEquals(Long.valueOf(1500L), read.getLlmElapsedMillis());
        assertEquals("[{\"toolName\":\"read_file\"}]", read.getToolCalls());
        assertEquals("{\"interrupted\":true}", read.getMetadata());
        assertEquals("jwork-agent", read.getAgentCode());
        assertEquals("gpt-test", read.getModelCode());
        assertNull(read.getId(), "草稿没有正式主键，提升时才生成");
        assertTrue(read.getCreatedAt() != null);
    }

    @Test
    @DisplayName("delete 只清指定会话；空会话 id 与空白入参不炸库")
    void deleteIsScopedToSession() {
        dao.save(draft("s1", "a"));
        dao.save(draft("s2", "b"));

        assertEquals(1, dao.delete("s1"));
        dao.save(draft("s3", "c"));
        assertEquals(0, dao.delete(null), "null 会话 id 不该发出 DELETE");
        assertEquals(0, dao.delete(" "));

        List<String> left = dao.findAll().stream().map(ChatConversation::getSessionId).toList();
        assertEquals(List.of("s2", "s3"), left);
    }

    @Test
    @DisplayName("无会话 id 的草稿直接忽略：不能往库里塞一条无处挂靠的半截")
    void saveWithoutSessionIsIgnored() {
        dao.save(draft(null, "x"));
        dao.save(draft("  ", "x"));
        dao.save(null);
        assertTrue(dao.findAll().isEmpty());
    }
}
