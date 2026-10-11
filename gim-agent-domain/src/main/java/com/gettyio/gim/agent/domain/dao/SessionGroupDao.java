package com.gettyio.gim.agent.domain.dao;

import com.gettyio.gim.agent.domain.entity.SessionGroup;
import com.gettyio.gim.agent.domain.persist.SqlRunner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * session_groups 表 DAO：会话分组（纯 UI 组织层，归属到某个智能体）。
 */
public class SessionGroupDao {

    private static final String COLUMNS =
            "id, group_id, agent_code, name, created_at, updated_at";

    private final SqlRunner runner;

    public SessionGroupDao(SqlRunner runner) {
        this.runner = runner;
    }

    public static SessionGroup map(ResultSet rs) throws SQLException {
        SessionGroup g = new SessionGroup();
        g.setId(SqlRunner.getLong(rs, "id"));
        g.setGroupId(rs.getString("group_id"));
        g.setAgentCode(rs.getString("agent_code"));
        g.setName(rs.getString("name"));
        g.setCreatedAt(SqlRunner.getDateTime(rs, "created_at"));
        g.setUpdatedAt(SqlRunner.getDateTime(rs, "updated_at"));
        return g;
    }

    public long insert(SessionGroup g) {
        LocalDateTime now = LocalDateTime.now();
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        long id = runner.insert(
                "INSERT INTO session_groups (group_id, agent_code, name, created_at, updated_at) VALUES (?,?,?,?,?)",
                g.getGroupId(), g.getAgentCode(), g.getName(), now, now);
        g.setId(id);
        return id;
    }

    /** 某智能体下的全部分组（按创建顺序稳定显示）。 */
    public List<SessionGroup> findByAgent(String agentCode) {
        return runner.query(
                "SELECT " + COLUMNS + " FROM session_groups WHERE agent_code=? ORDER BY created_at ASC",
                SessionGroupDao::map, agentCode);
    }

    public int rename(String groupId, String name) {
        return runner.update("UPDATE session_groups SET name=?, updated_at=? WHERE group_id=?",
                name, LocalDateTime.now(), groupId);
    }

    public int deleteByGroupId(String groupId) {
        return runner.update("DELETE FROM session_groups WHERE group_id=?", groupId);
    }
}
