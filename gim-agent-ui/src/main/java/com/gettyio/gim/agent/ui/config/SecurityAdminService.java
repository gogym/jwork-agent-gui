package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ToolPermissionRule;

import java.util.List;

/**
 * 工具权限管理：列出/撤销持久化的"总是允许"授权规则。
 * <p>这些规则由 HITL 的 {@code GuardChain.recordDecision} 写入，并在
 * {@code GuardChain.evaluate} 中被读取短路，因此删除即真实撤销对应工具的自动放行。</p>
 */
public class SecurityAdminService {

    private final GimDatabase db;

    public SecurityAdminService(GimDatabase db) {
        this.db = db;
    }

    public List<ToolPermissionRule> list() {
        return db.permissionRuleDao().findAll();
    }

    public void delete(Long id) {
        if (id != null) {
            db.permissionRuleDao().delete(id);
        }
    }

    public void clear() {
        for (ToolPermissionRule r : db.permissionRuleDao().findAll()) {
            db.permissionRuleDao().delete(r.getId());
        }
    }
}
