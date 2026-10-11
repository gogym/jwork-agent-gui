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

import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.persist.SqlRunner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * sessions 表 DAO
 *
 * @author gogym
 */
public class ChatSessionDao {

    private static final String COLUMNS =
            "id, session_id, agent_code, group_id, title, model_code, message_count, status, pinned, favorite, parent_session_id, compacted_tokens, created_at, updated_at";

    private final SqlRunner runner;

    public ChatSessionDao(SqlRunner runner) {
        this.runner = runner;
    }

    public static ChatSession map(ResultSet rs) throws SQLException {
        ChatSession s = new ChatSession();
        s.setId(SqlRunner.getLong(rs, "id"));
        s.setSessionId(rs.getString("session_id"));
        s.setAgentCode(rs.getString("agent_code"));
        s.setGroupId(rs.getString("group_id"));
        s.setTitle(rs.getString("title"));
        s.setModelCode(rs.getString("model_code"));
        s.setMessageCount(SqlRunner.getInt(rs, "message_count"));
        s.setStatus(rs.getString("status"));
        s.setPinned(SqlRunner.getInt(rs, "pinned"));
        s.setFavorite(SqlRunner.getInt(rs, "favorite"));
        s.setParentSessionId(rs.getString("parent_session_id"));
        s.setCompactedTokens(SqlRunner.getInt(rs, "compacted_tokens"));
        s.setCreatedAt(SqlRunner.getDateTime(rs, "created_at"));
        s.setUpdatedAt(SqlRunner.getDateTime(rs, "updated_at"));
        return s;
    }

    public long insert(ChatSession s) {
        LocalDateTime now = LocalDateTime.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        long id = runner.insert(
                "INSERT INTO sessions (session_id, agent_code, group_id, title, model_code, message_count, status, pinned, favorite, parent_session_id, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                s.getSessionId(), s.getAgentCode(), s.getGroupId(), s.getTitle(), s.getModelCode(),
                s.getMessageCount(), s.getStatus(), s.getPinned() == null ? 0 : s.getPinned(),
                s.getFavorite() == null ? 0 : s.getFavorite(), s.getParentSessionId(), now, now);
        s.setId(id);
        return id;
    }

    public Optional<ChatSession> findBySessionId(String sessionId) {
        return runner.queryOne("SELECT " + COLUMNS + " FROM sessions WHERE session_id=?",
                ChatSessionDao::map, sessionId);
    }

    public List<ChatSession> findByAgent(String agentCode) {
        return runner.query("SELECT " + COLUMNS + " FROM sessions WHERE agent_code=? ORDER BY pinned DESC, updated_at DESC",
                ChatSessionDao::map, agentCode);
    }

    public List<ChatSession> findAll() {
        return runner.query("SELECT " + COLUMNS + " FROM sessions ORDER BY pinned DESC, updated_at DESC", ChatSessionDao::map);
    }

    public int updateTitle(String sessionId, String title) {
        return runner.update("UPDATE sessions SET title=?, updated_at=? WHERE session_id=?",
                title, LocalDateTime.now(), sessionId);
    }

    /** 手改标题：不碰 updated_at，重命名不该把一条老会话顶到列表最前 */
    public int renameTitle(String sessionId, String title) {
        return runner.update("UPDATE sessions SET title=? WHERE session_id=?", title, sessionId);
    }

    public int incrementMessageCount(String sessionId, int delta) {
        return runner.update("UPDATE sessions SET message_count=message_count+?, updated_at=? WHERE session_id=?",
                delta, LocalDateTime.now(), sessionId);
    }

    public int deleteBySessionId(String sessionId) {
        return runner.update("DELETE FROM sessions WHERE session_id=?", sessionId);
    }

    /** 引用该智能体的会话数（智能体删除前的引用检查） */
    public int countByAgent(String agentCode) {
        return runner.queryOne("SELECT COUNT(*) AS n FROM sessions WHERE agent_code=?",
                rs -> rs.getInt("n"), agentCode).orElse(0);
    }

    /**
     * 移动会话到分组：只改 {@code group_id}，不碰 {@code updated_at}。
     * <p>与重命名同理：把会话挪进/移出分组不该打乱按最近更新排序的列表。{@code groupId} 传 null 即移出分组。</p>
     */
    public int updateGroup(String sessionId, String groupId) {
        return runner.update("UPDATE sessions SET group_id=? WHERE session_id=?", groupId, sessionId);
    }

    /** 删除分组时解绑：把该组下所有会话退回未分组（{@code group_id=NULL}），不删会话本身。 */
    public int clearGroup(String groupId) {
        return runner.update("UPDATE sessions SET group_id=NULL WHERE group_id=?", groupId);
    }

    /**
     * 置顶/取消置顶：只改 {@code pinned}，不碰 {@code updated_at}。
     * <p>与重命名同理：置顶不该刷新活跃时间把会话顶到同组最前；排序靠 {@code pinned DESC} 完成。</p>
     */
    public int updatePinned(String sessionId, boolean pinned) {
        return runner.update("UPDATE sessions SET pinned=? WHERE session_id=?", pinned ? 1 : 0, sessionId);
    }

    /** 收藏/取消收藏：只改 {@code favorite}，不影响排序。 */
    public int updateFavorite(String sessionId, boolean favorite) {
        return runner.update("UPDATE sessions SET favorite=? WHERE session_id=?", favorite ? 1 : 0, sessionId);
    }

    /**
     * 记录最近一次手动压缩后的上下文估算；{@code tokens} 传 null 即清除（新一轮真实用量已覆盖）。
     * <p>与重命名/分组同理不碰 {@code updated_at}：压缩属后台维护，不该刷新会话活跃排序。</p>
     */
    public int updateCompactedTokens(String sessionId, Integer tokens) {
        return runner.update("UPDATE sessions SET compacted_tokens=? WHERE session_id=?", tokens, sessionId);
    }
}
