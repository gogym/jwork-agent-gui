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

package com.gettyio.gim.agent.core.capability;

import com.gettyio.gim.agent.core.chat.HarnessAgentGateway;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.entity.McpServerConfig;
import com.gettyio.gim.agent.domain.entity.SkillPackage;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.security.SecretCipher;
import io.agentscope.harness.agent.tools.McpServerRegistrationResult;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 按智能体定义解析它「能用什么」，并把结论注入运行体：
 * <ul>
 *   <li><b>技能</b> —— {@code skills_json} 是技能编码白名单，注入为技能名白名单
 *       （AgentScope 按 SKILL.md frontmatter 的 {@code name} 过滤，故编码与库中技能名一并下发）；</li>
 *   <li><b>MCP</b> —— {@code mcp_servers_json} 是服务编码白名单，翻译成 {@link ToolsConfig} 的
 *       {@code mcpServers}，装配时由 AgentScope 真正建连并把工具注册进 Toolkit。</li>
 * </ul>
 * <p><b>技能三态</b>：{@code skills_json} 为 null/空白 = 从未声明，技能仓库里的都可见；
 * 为 {@code []} = 用户显式清空，一个技能都不给；有编码 = 严格按白名单。
 * MCP 仍是严格白名单：清单为空就不连接任何服务。</p>
 * <p>本类同时把 {@code toolsConfig} 作为 workspace {@code tools.json} 的<b>覆盖项</b>下发，
 * 因此数据库声明优先于手工放置的 tools.json —— 这是「能力以智能体定义为准」的必然代价，
 * 也避免同一份文件对多个智能体产生不同效果。</p>
 *
 * @author gogym
 */
public class AgentCapabilityResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentCapabilityResolver.class);

    /** 回写 mcp_servers.status 的取值：与 MCP 管理页展示的 CONNECTED / DISCONNECTED 对齐 */
    private static final String STATUS_CONNECTED = "CONNECTED";
    private static final String STATUS_DISCONNECTED = "DISCONNECTED";

    private final GimDatabase db;
    /** MCP env/headers 值入库加密，翻译配置时解密回明文；null 按明文读（单测） */
    private final SecretCipher cipher;

    public AgentCapabilityResolver(GimDatabase db, SecretCipher cipher) {
        this.db = db;
        this.cipher = cipher;
    }

    /**
     * 把定义声明的能力注入运行体；必须在首次对话前调用（装配是惰性的，setter 只是暂存）。
     *
     * @param def 智能体定义；字段为 null 视为「无能力」
     */
    public void applyTo(HarnessAgentGateway gateway, AgentDefinition def) {
        String skillsJson = def.getSkillsJson();
        // 从未声明（null/空白）下发 null=仓库全部技能可见；显式 [] 才是「用户清空的严格白名单」
        Set<String> skillNames = skillsJson == null || skillsJson.isBlank()
                ? null : skillNames(skillsJson);
        Map<String, io.agentscope.harness.agent.tools.McpServerConfig> servers =
                mcpServers(def.getMcpServersJson());

        ToolsConfig toolsConfig = new ToolsConfig();
        toolsConfig.setMcpServers(servers);

        gateway.setSkillAllowlist(skillNames);
        gateway.setToolsConfig(toolsConfig, this::recordMcpStatus);

        log.info("智能体能力: skills={} (声明 {}), mcp={}",
                skillNames == null ? "全部（未声明）" : skillNames,
                CapabilityCodec.readCodes(skillsJson), servers.keySet());
    }

    /**
     * 技能编码 → 技能名白名单。
     * <p>编码与名同时下发：编码是目录名（安装期标识），名是 SKILL.md 里的 frontmatter name
     * （运行期过滤键），两者不一致时也能命中；库里查不到的编码原样保留，便于手工声明。</p>
     */
    private Set<String> skillNames(String skillsJson) {
        Set<String> names = new LinkedHashSet<>();
        for (String code : CapabilityCodec.readCodes(skillsJson)) {
            names.add(code);
            db.skillDao().findByCode(code)
                    .map(SkillPackage::getSkillName)
                    .filter(name -> name != null && !name.isBlank())
                    .ifPresent(names::add);
        }
        return names;
    }

    /** MCP 服务编码 → AgentScope 配置；不存在、已禁用或翻译失败的跳过并留痕，不影响其他服务。 */
    private Map<String, io.agentscope.harness.agent.tools.McpServerConfig> mcpServers(String mcpServersJson) {
        Map<String, io.agentscope.harness.agent.tools.McpServerConfig> out = new LinkedHashMap<>();
        for (String code : CapabilityCodec.readCodes(mcpServersJson)) {
            Optional<McpServerConfig> found = db.mcpServerDao().findByCode(code);
            if (found.isEmpty()) {
                log.warn("智能体声明的 MCP 服务不存在，跳过: {}", code);
                continue;
            }
            McpServerConfig row = found.get();
            if (!EnabledStatus.isEnabled(row.getEnabled())) {
                log.info("智能体声明的 MCP 服务已禁用，跳过: {}", code);
                continue;
            }
            try {
                out.put(code, McpServerConfigs.from(row, cipher));
            } catch (RuntimeException e) {
                log.warn("MCP 服务配置无法翻译，跳过: {}", code, e);
            }
        }
        return out;
    }

    /**
     * MCP 注册结果回写到 {@code mcp_servers.status}，让管理页的连接状态来自真实结果。
     * <p>回写发生在智能体装配线程上（首次对话时），失败只告警：状态列是展示信息，
     * 不能因为它让对话起不来。</p>
     */
    private void recordMcpStatus(McpServerRegistrationResult result) {
        String code = result.serverName();
        if (code == null || code.isBlank()) {
            return;
        }
        boolean success = result.status() == McpServerRegistrationResult.Status.SUCCESS;
        if (!success) {
            log.warn("MCP 服务接入失败: {} ({}) -> {}", code, result.status(),
                    result.cause() == null ? "unknown" : result.cause().getMessage());
        } else {
            log.info("MCP 服务已接入: {} ({})", code, result.transport());
        }
        try {
            db.mcpServerDao().findByCode(code).ifPresent(row -> {
                row.setStatus(success ? STATUS_CONNECTED : STATUS_DISCONNECTED);
                db.mcpServerDao().update(row);
            });
        } catch (RuntimeException e) {
            log.warn("回写 MCP 连接状态失败: {}", code, e);
        }
    }
}
