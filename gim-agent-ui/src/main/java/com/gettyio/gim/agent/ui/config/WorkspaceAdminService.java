package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.WorkspaceGroup;

import java.util.List;
import java.util.UUID;

/**
 * 工作区分组管理：分组表增删改查。删除分组时先解绑其下会话（会话不随之删除）。
 */
public class WorkspaceAdminService {

    private final GimDatabase db;

    public WorkspaceAdminService(GimDatabase db) {
        this.db = db;
    }

    public List<WorkspaceGroup> list() {
        return db.workspaceDao().findAll();
    }

    public WorkspaceGroup create(String name, String folderPath, String icon, String color) {
        WorkspaceGroup w = new WorkspaceGroup();
        w.setWorkspaceId(UUID.randomUUID().toString());
        w.setName(name);
        w.setFolderPath(folderPath);
        w.setIcon(icon);
        w.setColor(color);
        db.workspaceDao().insert(w);
        return w;
    }

    public void update(String workspaceId, String name, String folderPath, String icon, String color) {
        db.workspaceDao().findByWorkspaceId(workspaceId).ifPresent(w -> {
            w.setName(name);
            w.setFolderPath(folderPath);
            w.setIcon(icon);
            w.setColor(color);
            db.workspaceDao().update(w);
        });
    }

    /** 删除分组：其下会话解绑为未分组，保留会话本身 */
    public void delete(String workspaceId) {
        db.sessionDao().unassignWorkspace(workspaceId);
        db.workspaceDao().deleteByWorkspaceId(workspaceId);
    }
}
