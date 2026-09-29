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
 */
public class ChatSessionDao {

    private static final String COLUMNS =
            "id, session_id, agent_code, title, workspace_id, model_code, message_count, status, created_at, updated_at";

    private final SqlRunner runner;

    public ChatSessionDao(SqlRunner runner) {
        this.runner = runner;
    }

    public static ChatSession map(ResultSet rs) throws SQLException {
        ChatSession s = new ChatSession();
        s.setId(SqlRunner.getLong(rs, "id"));
        s.setSessionId(rs.getString("session_id"));
        s.setAgentCode(rs.getString("agent_code"));
        s.setTitle(rs.getString("title"));
        s.setWorkspaceId(rs.getString("workspace_id"));
        s.setModelCode(rs.getString("model_code"));
        s.setMessageCount(SqlRunner.getInt(rs, "message_count"));
        s.setStatus(rs.getString("status"));
        s.setCreatedAt(SqlRunner.getDateTime(rs, "created_at"));
        s.setUpdatedAt(SqlRunner.getDateTime(rs, "updated_at"));
        return s;
    }

    public long insert(ChatSession s) {
        LocalDateTime now = LocalDateTime.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        long id = runner.insert(
                "INSERT INTO sessions (session_id, agent_code, title, workspace_id, model_code, message_count, status, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                s.getSessionId(), s.getAgentCode(), s.getTitle(), s.getWorkspaceId(), s.getModelCode(),
                s.getMessageCount(), s.getStatus(), now, now);
        s.setId(id);
        return id;
    }

    public Optional<ChatSession> findBySessionId(String sessionId) {
        return runner.queryOne("SELECT " + COLUMNS + " FROM sessions WHERE session_id=?",
                ChatSessionDao::map, sessionId);
    }

    public List<ChatSession> findByAgent(String agentCode) {
        return runner.query("SELECT " + COLUMNS + " FROM sessions WHERE agent_code=? ORDER BY updated_at DESC",
                ChatSessionDao::map, agentCode);
    }

    public List<ChatSession> findAll() {
        return runner.query("SELECT " + COLUMNS + " FROM sessions ORDER BY updated_at DESC", ChatSessionDao::map);
    }

    /** 指定工作区分组下的会话（按最近更新排序） */
    public List<ChatSession> findByWorkspace(String workspaceId) {
        return runner.query("SELECT " + COLUMNS + " FROM sessions WHERE workspace_id=? ORDER BY updated_at DESC",
                ChatSessionDao::map, workspaceId);
    }

    public int updateTitle(String sessionId, String title) {
        return runner.update("UPDATE sessions SET title=?, updated_at=? WHERE session_id=?",
                title, LocalDateTime.now(), sessionId);
    }

    public int incrementMessageCount(String sessionId, int delta) {
        return runner.update("UPDATE sessions SET message_count=message_count+?, updated_at=? WHERE session_id=?",
                delta, LocalDateTime.now(), sessionId);
    }

    public int deleteBySessionId(String sessionId) {
        return runner.update("DELETE FROM sessions WHERE session_id=?", sessionId);
    }

    /** 将会话归入/移出工作区分组（workspaceId 传 null 表示移出） */
    public int updateWorkspace(String sessionId, String workspaceId) {
        return runner.update("UPDATE sessions SET workspace_id=? WHERE session_id=?", workspaceId, sessionId);
    }

    /** 删除分组时将其下全部会话解绑（置为未分组） */
    public int unassignWorkspace(String workspaceId) {
        return runner.update("UPDATE sessions SET workspace_id=NULL WHERE workspace_id=?", workspaceId);
    }
}
