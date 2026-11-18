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

package com.gettyio.jwork.agent.core.subagent;

import com.gettyio.jwork.agent.core.capability.CapabilityCodec;
import com.gettyio.jwork.agent.domain.entity.AgentDefinition;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * 把数据库里的智能体定义映射为框架可委派的 {@link SubagentDeclaration}。
 * <p>主控智能体据此把子任务派给其它「专职」智能体：每个启用（且非自身）的 {@link AgentDefinition}
 * 生成一条声明，人格走内联系统提示（{@code inlineAgentsBody}），技能沿用定义的白名单，
 * 模型不显式指定（回退继承父模型，避免把库内模型编码误当框架 {@code ModelRegistry} 名）。</p>
 * <p>描述是框架强制字段（空则 {@code build()} 抛异常），故以「描述 → 显示名 → 兜底文案」逐级取值；
 * 单条映射失败只跳过该智能体，不影响其余委派对象与主控装配。</p>
 *
 * @author gogym
 */
public final class SubagentSpecs {

    private SubagentSpecs() {
    }

    /**
     * 依据「除自身外的其它启用智能体」生成委派声明列表。
     *
     * @param enabled 全部启用的智能体定义（含自身，通常来自 {@code findEnabled()}）
     * @param selfCode 当前正在装配的智能体编码（排除，避免自我委派）
     * @return 可委派声明列表；无可委派对象时返回空列表
     */
    public static List<SubagentDeclaration> mapOtherEnabled(List<AgentDefinition> enabled, String selfCode) {
        if (enabled == null || enabled.isEmpty()) {
            return List.of();
        }
        String self = selfCode == null ? "" : selfCode.trim();
        List<SubagentDeclaration> out = new ArrayList<>();
        for (AgentDefinition def : enabled) {
            if (def == null) {
                continue;
            }
            String code = def.getAgentCode();
            if (code == null || code.isBlank() || code.trim().equals(self)) {
                // 无编码无法被委派引用；等于自身则跳过，防止主控把任务派给自己
                continue;
            }
            String name = code.trim();
            String description = firstNonBlank(def.getDescription(), def.getAgentName(),
                    "Delegable agent " + name);
            try {
                SubagentDeclaration.Builder builder = SubagentDeclaration.builder()
                        .name(name)
                        .description(description);
                String prompt = def.getSystemPrompt();
                if (prompt != null && !prompt.isBlank()) {
                    builder.inlineAgentsBody(prompt);
                }
                List<String> skills = CapabilityCodec.readCodes(def.getSkillsJson());
                if (!skills.isEmpty()) {
                    builder.skills(skills);
                }
                out.add(builder.build());
            } catch (IllegalArgumentException e) {
                // 名称/描述非法或来源冲突：跳过该智能体，不阻断其余委派与主控装配
                continue;
            }
        }
        return out;
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
        }
        return "";
    }
}
