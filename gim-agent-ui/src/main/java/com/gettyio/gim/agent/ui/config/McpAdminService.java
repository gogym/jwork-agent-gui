package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.McpServerConfig;
import com.gettyio.gim.agent.domain.enums.McpTransportType;

import java.util.List;

/**
 * MCP 服务配置管理：mcp_servers 表增删改查与启禁。
 * <p>当前仅负责配置的持久化；与 Agent 的运行时连接（AgentScope McpClient）
 * 为后续接入项，此处不谎报连接状态，status 保持库中存储值。</p>
 */
public class McpAdminService {

    private final GimDatabase db;

    public McpAdminService(GimDatabase db) {
        this.db = db;
    }

    public List<McpServerConfig> list() {
        return db.mcpServerDao().findAll();
    }

    public void save(McpServerConfig m) {
        // 归一化：校验传输类型、补默认值
        m.setTransportType(McpTransportType.fromCode(m.getTransportType()).name());
        if (m.getEnabled() == null) {
            m.setEnabled(1);
        }
        if (m.getTimeout() == null) {
            m.setTimeout(30);
        }
        if (m.getStatus() == null || m.getStatus().isBlank()) {
            m.setStatus("UNKNOWN");
        }
        boolean exists = db.mcpServerDao().findByCode(m.getServerCode()).isPresent();
        if (exists) {
            db.mcpServerDao().update(m);
        } else {
            db.mcpServerDao().insert(m);
        }
    }

    public void delete(String serverCode) {
        db.mcpServerDao().deleteByCode(serverCode);
    }

    public void setEnabled(String serverCode, boolean enabled) {
        db.mcpServerDao().findByCode(serverCode).ifPresent(m -> {
            m.setEnabled(enabled ? 1 : 0);
            db.mcpServerDao().update(m);
        });
    }
}
