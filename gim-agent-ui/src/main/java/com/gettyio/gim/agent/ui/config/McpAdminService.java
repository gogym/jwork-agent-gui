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

package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.core.capability.CapabilityCodec;
import com.gettyio.gim.agent.core.capability.McpConnectionProbe;
import com.gettyio.gim.agent.core.capability.McpSecrets;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.McpServerConfig;
import com.gettyio.gim.agent.domain.enums.McpConnectionStatus;
import com.gettyio.gim.agent.domain.enums.McpTransportType;
import com.gettyio.gim.agent.domain.security.SecretCipher;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务配置管理：mcp_servers 表增删改查与启禁。
 * <p>运行时连接由智能体自己声明：{@code agents.mcp_servers_json} 列出的服务会在装配时由
 * AgentScope 真正建连并把工具注册进 Toolkit（见 {@code AgentCapabilityResolver}）；
 * 未被任何智能体声明的服务不连接，因此本服务不代指“已连接”；status 由运行时注册结果与「测试连接」探测共同回写。</p>
 * <p>配置变更要让引用它的智能体运行时重建，否则旧客户端会继续提供旧工具集。</p>
 * <p>环境变量 / 请求头的值经 {@link McpSecrets} 逐个加密入库（与提供商 API Key 同用
 * {@link SecretCipher}）；表单回填与「测试连接」走本服务的解密入口，库中不留明文密钥。</p>
 *
 * @author gogym
 */
public class McpAdminService {

    private final GimDatabase db;
    private final AgentAdminService agentAdminService;
    private final SecretCipher secrets;

    public McpAdminService(GimDatabase db, AgentAdminService agentAdminService, SecretCipher secrets) {
        this.db = db;
        this.agentAdminService = agentAdminService;
        this.secrets = secrets;
    }

    public List<McpServerConfig> list() {
        return db.mcpServerDao().findAll();
    }

    /** 保存（新增或更新）MCP 服务：归一化传输类型与默认值后落库，并通知智能体运行时重建。 */
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
            m.setStatus(McpConnectionStatus.UNKNOWN.name());
        }
        // 密钥值加密入库：入参约定为明文 JSON（表单 / 市场模板）；已密文值不二次加密，重复保存幂等
        m.setEnvJson(encryptMap(m.getEnvJson()));
        m.setHeadersJson(encryptMap(m.getHeadersJson()));
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

    /** 探测结果回写连接状态（「测试连接」用）；状态未变化不发多余 UPDATE。 */
    public void recordStatus(String serverCode, boolean connected) {
        db.mcpServerDao().findByCode(serverCode).ifPresent(m -> {
            String next = McpConnectionStatus.of(connected).name();
            if (!next.equals(m.getStatus())) {
                m.setStatus(next);
                db.mcpServerDao().update(m);
            }
        });
    }

    /** 测试连接恢复后让引用该服务的智能体运行时失效，下轮对话即会以新连接重试。 */
    public void refreshAgentRuntimes(String serverCode) {
        agentAdminService.onMcpServerChanged(serverCode);
    }

    /** 表单回填用的明文环境变量（值就地解密；UI 再以掩码呈现）。 */
    public Map<String, String> envOf(McpServerConfig m) {
        return McpSecrets.readMap(m.getEnvJson(), secrets);
    }

    /** 表单回填用的明文请求头（值就地解密；UI 再以掩码呈现）。 */
    public Map<String, String> headersOf(McpServerConfig m) {
        return McpSecrets.readMap(m.getHeadersJson(), secrets);
    }

    /** 测试连接：带密钥解密器按配置真实建连一次（同步阻塞，调用方应在后台线程执行）。 */
    public McpConnectionProbe.ProbeResult probe(McpServerConfig m) {
        return McpConnectionProbe.probe(m, secrets);
    }

    /** null/空白保持原样（从未配置）；其余按明文映射读入后加密写回。 */
    private String encryptMap(String json) {
        return json == null || json.isBlank() ? json
                : McpSecrets.writeMap(CapabilityCodec.readStringMap(json), secrets);
    }
}
