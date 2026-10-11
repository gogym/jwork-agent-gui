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
import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.entity.McpServerConfig;
import com.gettyio.gim.agent.domain.entity.ModelConfig;
import com.gettyio.gim.agent.domain.entity.ModelProviderConfig;
import com.gettyio.gim.agent.domain.entity.SkillPackage;
import com.gettyio.gim.agent.domain.enums.AgentType;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 智能体定义（agents 表）的增删改查。设置窗口与侧栏共用本服务。
 * <p>保存与删除后立即让 {@link MultiAgentGateway} 丢弃该智能体的运行时，否则旧的人格/模型会继续生效。</p>
 *
 * @author gogym
 */
public class AgentAdminService {

    /**
     * 模型下拉选项；providerCode 与 modelCode 同时为空表示「跟随应用默认模型」。
     *
     * @param label 展示文本（提供商名 / 模型名）
     */
    public record ModelOption(String providerCode, String modelCode, String label) {

        /** 跟随应用默认模型的哨兵选项 */
        public static ModelOption followDefault() {
            return new ModelOption(null, null, null);
        }

        public boolean isDefault() {
            return (providerCode == null || providerCode.isBlank())
                    && (modelCode == null || modelCode.isBlank());
        }
    }

    private final GimDatabase db;
    private final MultiAgentGateway agentGateway;

    public AgentAdminService(GimDatabase db, MultiAgentGateway agentGateway) {
        this.db = db;
        this.agentGateway = agentGateway;
    }

    /**
     * 能力候选项（智能体表单里的一条可勾选项）。
     *
     * @param code      落库的稳定编码（技能目录名 / MCP 服务编码）
     * @param label     展示名
     * @param available 目录中是否处于启用态；禁用项勾了也不会生效，由表单标注提醒
     */
    public record CapabilityOption(String code, String label, boolean available) {
    }

    public List<AgentDefinition> list() {
        return db.agentDao().findAll();
    }

    public Optional<AgentDefinition> find(String agentCode) {
        return db.agentDao().findByCode(agentCode);
    }

    /** 内置默认智能体编码：会话/定时任务未指定智能体时回落到它 */
    public String defaultAgentCode() {
        return MultiAgentGateway.DEFAULT_AGENT_CODE;
    }

    public boolean isDefault(String agentCode) {
        return MultiAgentGateway.DEFAULT_AGENT_CODE.equals(agentCode);
    }

    /** 新会话未显式选择智能体时使用它 */
    public String effectiveCode(String agentCode) {
        return agentCode == null || agentCode.isBlank() ? defaultAgentCode() : agentCode;
    }

    /**
     * 智能体工作区目录（与运行时装配同源，见 {@link MultiAgentGateway#workspaceForAgent(String)}）：
     * 默认智能体即共享工作区，其余为 {@code agents/<编码>/}。供聊天页右栏文件树跟随会话定位产物落点。
     */
    public Path workspaceDir(String agentCode) {
        return agentGateway.workspaceForAgent(agentCode);
    }

    /** 引用该智能体的会话数 */
    public int sessionUsage(String agentCode) {
        return db.sessionDao().countByAgent(agentCode);
    }

    /** 引用该智能体的定时任务数 */
    public int taskUsage(String agentCode) {
        return db.taskDao().countByAgent(agentCode);
    }

    /**
     * 保存（存在则更新，否则插入），随后失效其运行时使新定义立即生效。
     * <p>temperature/maxTokens 为 null 表示沿用模型默认，调用方须显式传 null——实体自带 0.7/2000 的
     * Java 默认值，不清空会把它写进库，擅自限制回复长度。</p>
     */
    public AgentDefinition save(AgentDefinition form) {
        if (form.getAgentCode() == null || form.getAgentCode().isBlank()) {
            form.setAgentCode(newCode());
        }
        if (form.getAgentName() == null || form.getAgentName().isBlank()) {
            form.setAgentName(form.getAgentCode());
        }
        if (form.getAgentType() == null || form.getAgentType().isBlank()) {
            form.setAgentType(AgentType.SIMPLE.name());
        }
        if (form.getEnabled() == null) {
            form.setEnabled(1);
        }
        blankToNull(form);
        if (db.agentDao().findByCode(form.getAgentCode()).isPresent()) {
            db.agentDao().update(form);
        } else {
            db.agentDao().insert(form);
        }
        agentGateway.invalidate(form.getAgentCode());
        return form;
    }

    /** 删除智能体；内置默认智能体不可删（它是所有未指定智能体的回退目标） */
    public void delete(String agentCode) {
        if (isDefault(agentCode)) {
            throw new IllegalStateException("内置默认智能体不可删除");
        }
        db.agentDao().deleteByCode(agentCode);
        agentGateway.invalidate(agentCode);
    }

    /** 模型下拉数据：跟随默认 + 各启用提供商下的启用模型 */
    public List<ModelOption> modelOptions() {
        List<ModelOption> options = new ArrayList<>();
        Map<String, String> providerNames = new LinkedHashMap<>();
        for (ModelProviderConfig provider : db.modelProviderDao().findAll()) {
            providerNames.put(provider.getProviderCode(),
                    provider.getProviderName() == null || provider.getProviderName().isBlank()
                            ? provider.getProviderCode() : provider.getProviderName());
        }
        providerNames.forEach((providerCode, providerName) -> {
            for (ModelConfig model : db.modelConfigDao().findEnabledByProvider(providerCode)) {
                String shown = model.getModelName() == null || model.getModelName().isBlank()
                        ? model.getModelCode() : model.getModelName();
                options.add(new ModelOption(providerCode, model.getModelCode(), providerName + " · " + shown));
            }
        });
        return options;
    }

    // ---- 能力清单（技能 / MCP） ----

    /** 技能候选：库里全部技能包（含禁用，以便回显已声明但不会生效的那几项） */
    public List<CapabilityOption> skillOptions() {
        List<CapabilityOption> options = new ArrayList<>();
        for (SkillPackage s : db.skillDao().findAll()) {
            options.add(new CapabilityOption(s.getSkillCode(),
                    s.getSkillName() == null || s.getSkillName().isBlank() ? s.getSkillCode() : s.getSkillName(),
                    EnabledStatus.isEnabled(s.getEnabled())));
        }
        return options;
    }

    /** MCP 服务候选：库里全部服务（含禁用） */
    public List<CapabilityOption> mcpOptions() {
        List<CapabilityOption> options = new ArrayList<>();
        for (McpServerConfig m : db.mcpServerDao().findAll()) {
            options.add(new CapabilityOption(m.getServerCode(),
                    m.getServerName() == null || m.getServerName().isBlank() ? m.getServerCode() : m.getServerName(),
                    EnabledStatus.isEnabled(m.getEnabled())));
        }
        return options;
    }

    public List<String> selectedSkillCodes(AgentDefinition form) {
        return form == null ? List.of() : CapabilityCodec.readCodes(form.getSkillsJson());
    }

    public List<String> selectedMcpCodes(AgentDefinition form) {
        return form == null ? List.of() : CapabilityCodec.readCodes(form.getMcpServersJson());
    }

    /** 勾选结果转成落库 JSON：空集合写成 {@code []} */
    public String toSkillsJson(List<String> codes) {
        return CapabilityCodec.writeCodes(codes);
    }

    public String toMcpServersJson(List<String> codes) {
        return CapabilityCodec.writeCodes(codes);
    }

    /** 已声明技能的展示名（供设置页卡片与提示文案） */
    public List<String> declaredSkillNames(String skillsJson) {
        List<String> names = new ArrayList<>();
        for (String code : CapabilityCodec.readCodes(skillsJson)) {
            names.add(db.skillDao().findByCode(code)
                    .map(SkillPackage::getSkillName)
                    .filter(name -> name != null && !name.isBlank())
                    .orElse(code));
        }
        return names;
    }

    /** 已声明 MCP 服务的展示名 */
    public List<String> declaredMcpNames(String mcpServersJson) {
        List<String> names = new ArrayList<>();
        for (String code : CapabilityCodec.readCodes(mcpServersJson)) {
            names.add(db.mcpServerDao().findByCode(code)
                    .map(McpServerConfig::getServerName)
                    .filter(name -> name != null && !name.isBlank())
                    .orElse(code));
        }
        return names;
    }

    /**
     * 技能目录变化（安装/启禁/删除）后，让声明了该技能的智能体运行时失效。
     * <p>技能仓库在装配时扫描目录，不重建就看不到新状态。</p>
     */
    public void onSkillCatalogChanged(String skillCode) {
        invalidateReferencing(skillCode, true);
    }

    /** MCP 服务配置变化后，让引用它的智能体重建（不会连带影响其他智能体） */
    public void onMcpServerChanged(String serverCode) {
        invalidateReferencing(serverCode, false);
    }

    private void invalidateReferencing(String code, boolean skill) {
        if (blank(code)) {
            return;
        }
        for (AgentDefinition agent : db.agentDao().findAll()) {
            List<String> declared = CapabilityCodec.readCodes(skill ? agent.getSkillsJson() : agent.getMcpServersJson());
            if (declared.contains(code)) {
                agentGateway.invalidate(agent.getAgentCode());
            }
        }
    }

    /** 智能体编码内部生成，不对用户暴露（不同于提供商/模型编码需对齐外部 API 标识） */
    private String newCode() {
        String code;
        do {
            code = "agent-" + UUID.randomUUID().toString().substring(0, 8);
        } while (db.agentDao().findByCode(code).isPresent());
        return code;
    }

    private static void blankToNull(AgentDefinition form) {
        if (blank(form.getDescription())) {
            form.setDescription(null);
        }
        if (blank(form.getSystemPrompt())) {
            form.setSystemPrompt(null);
        }
        if (blank(form.getWorkspaceDir())) {
            form.setWorkspaceDir(null);
        }
        if (blank(form.getProjectPath())) {
            form.setProjectPath(null);
        }
        // 只指定了 provider 或只指定了 model 都视为未定向，避免跨提供商错配
        if (blank(form.getProviderCode()) || blank(form.getModelCode())) {
            form.setProviderCode(null);
            form.setModelCode(null);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
