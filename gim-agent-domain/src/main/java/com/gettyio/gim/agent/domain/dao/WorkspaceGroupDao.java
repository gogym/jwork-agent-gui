package com.gettyio.gim.agent.domain.dao;

import com.gettyio.gim.agent.domain.entity.WorkspaceGroup;
import com.gettyio.gim.agent.domain.persist.SqlRunner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * workspaces 表 DAO：工作区分组的增删改查。
 */
public class WorkspaceGroupDao {

    private static final String COLUMNS =
            "id, workspace_id, name, folder_path, icon, color, created_at, updated_at";

    private final SqlRunner runner;

    public WorkspaceGroupDao(SqlRunner runner) {
        this.runner = runner;
    }

    public static WorkspaceGroup map(ResultSet rs) throws SQLException {
        WorkspaceGroup w = new WorkspaceGroup();
        w.setId(SqlRunner.getLong(rs, "id"));
        w.setWorkspaceId(rs.getString("workspace_id"));
        w.setName(rs.getString("name"));
        w.setFolderPath(rs.getString("folder_path"));
        w.setIcon(rs.getString("icon"));
        w.setColor(rs.getString("color"));
        w.setCreatedAt(SqlRunner.getDateTime(rs, "created_at"));
        w.setUpdatedAt(SqlRunner.getDateTime(rs, "updated_at"));
        return w;
    }

    public long insert(WorkspaceGroup w) {
        LocalDateTime now = LocalDateTime.now();
        w.setCreatedAt(now);
        w.setUpdatedAt(now);
        long id = runner.insert(
                "INSERT INTO workspaces (workspace_id, name, folder_path, icon, color, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                w.getWorkspaceId(), w.getName(), w.getFolderPath(), w.getIcon(), w.getColor(), now, now);
        w.setId(id);
        return id;
    }

    public List<WorkspaceGroup> findAll() {
        return runner.query("SELECT " + COLUMNS + " FROM workspaces ORDER BY created_at ASC",
                WorkspaceGroupDao::map);
    }

    public Optional<WorkspaceGroup> findByWorkspaceId(String workspaceId) {
        return runner.queryOne("SELECT " + COLUMNS + " FROM workspaces WHERE workspace_id=?",
                WorkspaceGroupDao::map, workspaceId);
    }

    public int update(WorkspaceGroup w) {
        return runner.update("UPDATE workspaces SET name=?, folder_path=?, icon=?, color=?, updated_at=? "
                        + "WHERE workspace_id=?",
                w.getName(), w.getFolderPath(), w.getIcon(), w.getColor(),
                LocalDateTime.now(), w.getWorkspaceId());
    }

    public int deleteByWorkspaceId(String workspaceId) {
        return runner.update("DELETE FROM workspaces WHERE workspace_id=?", workspaceId);
    }
}
