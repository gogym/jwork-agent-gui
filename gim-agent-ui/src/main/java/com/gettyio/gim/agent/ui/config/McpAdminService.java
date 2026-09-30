package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.McpServerConfig;
import com.gettyio.gim.agent.domain.enums.McpTransportType;

import java.util.List;

/**
 * MCP 服务配置管理：mcp_servers 表增删改查与启禁。
 * <p>运行时连接由智能体自己声明：{@code agents.mcp_servers_json} 列出的服务会在装配时由
 * AgentScope 真正建连并把工具注册进 Toolkit（见 {@code AgentCapabilityResolver}）；
 * 未被任何智能体声明的服务不连接，因此本服务不代指“已连接”，status 由注册结果回写。</p>
 * <p>配置变更要让引用它的智能体运行时重建，否则旧客户端会继续提供旧工具集。</p>
 */
public class McpAdminService {

    private final GimDatabase db;
    private final AgentAdminService agentAdminService;

    public McpAdminService(GimDatabase db, AgentAdminService agentAdminService) {
        this.db = db;
        this.agentAdminService = agentAdminService;
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
        agentAdminService.onMcpServerChanged(m.getServerCode());
    }

    public void delete(String serverCode) {
        db.mcpServerDao().deleteByCode(serverCode);
        agentAdminService.onMcpServerChanged(serverCode);
    }

    public void setEnabled(String serverCode, boolean enabled) {
        db.mcpServerDao().findByCode(serverCode).ifPresent(m -> {
            m.setEnabled(enabled ? 1 : 0);
            db.mcpServerDao().update(m);
            agentAdminService.onMcpServerChanged(serverCode);
        });
    }
}
