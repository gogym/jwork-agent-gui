package com.gettyio.gim.agent.domain.dao;

import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.persist.SqlRunner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * conversations 表 DAO
 */
public class ChatConversationDao {

    private static final String COLUMNS =
            "id, session_id, agent_code, role, content, tokens_used, model_code, tool_calls, metadata, created_at";

    private final SqlRunner runner;

    public ChatConversationDao(SqlRunner runner) {
        this.runner = runner;
    }

    public static ChatConversation map(ResultSet rs) throws SQLException {
        ChatConversation c = new ChatConversation();
        c.setId(SqlRunner.getLong(rs, "id"));
        c.setSessionId(rs.getString("session_id"));
        c.setAgentCode(rs.getString("agent_code"));
        c.setRole(rs.getString("role"));
        c.setContent(rs.getString("content"));
        c.setTokensUsed(SqlRunner.getInt(rs, "tokens_used"));
        c.setModelCode(rs.getString("model_code"));
        c.setToolCalls(rs.getString("tool_calls"));
        c.setMetadata(rs.getString("metadata"));
        c.setCreatedAt(SqlRunner.getDateTime(rs, "created_at"));
        return c;
    }

    public long insert(ChatConversation c) {
        if (c.getCreatedAt() == null) {
            c.setCreatedAt(LocalDateTime.now());
        }
        long id = runner.insert(
                "INSERT INTO conversations (session_id, agent_code, role, content, tokens_used, model_code, tool_calls, metadata, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                c.getSessionId(), c.getAgentCode(), c.getRole(), c.getContent(), c.getTokensUsed(),
                c.getModelCode(), c.getToolCalls(), c.getMetadata(), c.getCreatedAt());
        c.setId(id);
        return id;
    }

    public List<ChatConversation> findBySession(String sessionId) {
        return runner.query("SELECT " + COLUMNS + " FROM conversations WHERE session_id=? ORDER BY id ASC",
                ChatConversationDao::map, sessionId);
    }

    public int deleteBySession(String sessionId) {
        return runner.update("DELETE FROM conversations WHERE session_id=?", sessionId);
    }
}
