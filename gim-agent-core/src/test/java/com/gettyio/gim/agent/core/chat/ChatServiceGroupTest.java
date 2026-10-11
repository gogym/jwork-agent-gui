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
import com.gettyio.gim.agent.domain.entity.SessionGroup;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatService} 会话分组往返单测：建组→移动→删组，验证 {@code sessions.group_id} 的绑解与「删组不删会话」。
 * <p>真实 SQLite（{@code @TempDir} 文件），无网关依赖，分组方法不触及流式路径。</p>
 *
 * @author gogym
 */
class ChatServiceGroupTest {

    @TempDir
    Path tempDir;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        GimDatabase db = new GimDatabase(new SqliteConnectionProvider(tempDir.resolve("gim.db")));
        db.initSchema();
        chatService = new ChatService(null, db, new GimPaths(tempDir.resolve("home")), null);
    }

    private ChatSession session(String sessionId) {
        Optional<ChatSession> hit = chatService.listSessions().stream()
                .filter(s -> s.getSessionId().equals(sessionId))
                .findFirst();
        return hit.orElse(null);
    }

    @Test
    @DisplayName("建组→移动：会话 group_id 落到分组，分组按智能体列出")
    void createGroupAndMove() {
        String agent = "gim-agent";
        String groupId = chatService.createGroup(agent, "项目A");
        assertNotNull(groupId);

        String sid = chatService.createSession(agent);
        assertNull(session(sid).getGroupId(), "新会话默认未分组");

        chatService.moveSessionToGroup(sid, groupId);
        assertEquals(groupId, session(sid).getGroupId());

        List<SessionGroup> groups = chatService.listGroups(agent);
        assertEquals(1, groups.size());
        assertEquals("项目A", groups.get(0).getName());
    }

    @Test
    @DisplayName("移出分组：group_id 归 null；空名回退默认「新分组」")
    void removeAndDefaultName() {
        String agent = "gim-agent";
        String groupId = chatService.createGroup(agent, "   ");
        List<SessionGroup> groups = chatService.listGroups(agent);
        assertEquals(1, groups.size());
        assertEquals("新分组", groups.get(0).getName(), "空名回退默认");

        String sid = chatService.createSession(agent);
        chatService.moveSessionToGroup(sid, groupId);
        chatService.moveSessionToGroup(sid, null);
        assertNull(session(sid).getGroupId());
    }

    @Test
    @DisplayName("删组：组内会话退回未分组且会话不被删除")
    void deleteGroupUngroupsSessions() {
        String agent = "gim-agent";
        String groupId = chatService.createGroup(agent, "临时");
        String sid = chatService.createSession(agent);
        chatService.moveSessionToGroup(sid, groupId);

        chatService.deleteGroup(groupId);

        assertTrue(chatService.listGroups(agent).isEmpty(), "分组定义已删");
        ChatSession s = session(sid);
        assertNotNull(s, "删组不应删会话");
        assertNull(s.getGroupId(), "组内会话退回未分组");
    }
}
