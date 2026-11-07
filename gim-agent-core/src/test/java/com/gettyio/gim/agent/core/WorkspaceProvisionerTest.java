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

package com.gettyio.gim.agent.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置人格模板的代际识别与一键升级：AGENTS.md 只在缺失时播种、从不覆盖，新版总则要靠
 * {@link WorkspaceProvisioner#agentsMdState()} 认出「仍是旧版默认原文」才允许替换。
 * <p>{@code LEGACY_V0} 逐字取自本机 ~/.jwork/workspace/AGENTS.md（早期版本播种的真实产物）；
 * 认不出它，升级提示就永远不弹。</p>
 *
 * @author gogym
 */
class WorkspaceProvisionerTest {

    /** 补「联网检索」之前的内置人格（本机现存老工作区即为此文）。 */
    private static final String LEGACY_V0 = """
            # Jwork

            你是运行在本地桌面的个人 AI 助理。遵循以下原则：

            - 本地优先：优先使用用户授权目录内的文件与工具。
            - 安全确认：执行文件写入、shell 命令等副作用操作前，触发用户确认。
            - 记忆沉淀：将长期有价值的事实写入 MEMORY.md。
            - 定时能力：用户要求定期/到点执行某事时，使用 schedule_task 工具（Quartz cron 表达式），
              并用 list_tasks / cancel_task 管理与查询任务。若是一次性「指定时刻」执行（如“明天 8 点提醒我”），
              改传 run_at 参数（yyyy-MM-dd HH:mm:ss），它与 cron 互斥。
            - 技能复用：workspace/skills/ 下已启用的技能（SKILL.md）定义了专项任务规范，命中时应遵循。
            - 简洁直接：回答以可执行为先，避免冗余。
            """;

    /** 补「联网检索」那一版（仍无版本标记）。 */
    private static final String LEGACY_V1 = """
            # Jwork

            你是运行在本地桌面的个人 AI 助理。遵循以下原则：

            - 本地优先：优先使用用户授权目录内的文件与工具。
            - 安全确认：执行文件写入、shell 命令等副作用操作前，触发用户确认。
            - 记忆沉淀：将长期有价值的事实写入 MEMORY.md。
            - 定时能力：用户要求定期/到点执行某事时，使用 schedule_task 工具（Quartz cron 表达式），
              并用 list_tasks / cancel_task 管理与查询任务。若是一次性「指定时刻」执行（如“明天 8 点提醒我”），
              改传 run_at 参数（yyyy-MM-dd HH:mm:ss），它与 cron 互斥。
            - 技能复用：workspace/skills/ 下已启用的技能（SKILL.md）定义了专项任务规范，命中时应遵循。
            - 联网检索：需要实时信息（新闻、价格、最新版本、知识截止后的事实）时，使用 web_search 搜索，
              必要时用 web_fetch 读取某条链接的完整正文。
            - 简洁直接：回答以可执行为先，避免冗余。
            """;

    @TempDir
    Path root;

    private WorkspaceProvisioner provisioner() {
        return new WorkspaceProvisioner(new GimPaths(root));
    }

    private Path agentsMd() {
        return new GimPaths(root).agentsMd();
    }

    private void writeAgentsMd(String content) throws IOException {
        Files.createDirectories(agentsMd().getParent());
        Files.writeString(agentsMd(), content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("新工作区播种即当前模板，状态为 CURRENT")
    void provisionSeedsCurrentTemplate() throws IOException {
        provisioner().provision();
        String seeded = Files.readString(agentsMd(), StandardCharsets.UTF_8);
        assertTrue(seeded.contains("deliver_artifact"), "播种内容应含产物交付总则");
        assertTrue(seeded.contains("<!-- gim-agents-template: " + WorkspaceProvisioner.TEMPLATE_VERSION),
                "播种内容应带当前版本标记");
        assertEquals(WorkspaceProvisioner.AgentsMdState.CURRENT, provisioner().agentsMdState());
    }

    @Test
    @DisplayName("文件还没生成时报 ABSENT，不提示也不写盘")
    void absentFileReported() {
        assertEquals(WorkspaceProvisioner.AgentsMdState.ABSENT, provisioner().agentsMdState());
        assertFalse(provisioner().upgradeAgentsMd());
        assertFalse(Files.exists(agentsMd()));
    }

    @Test
    @DisplayName("旧版默认原文可一键升级，升级后落为当前模板")
    void legacyDefaultUpgrades() throws IOException {
        writeAgentsMd(LEGACY_V0);
        WorkspaceProvisioner p = provisioner();
        assertEquals(WorkspaceProvisioner.AgentsMdState.UPGRADABLE, p.agentsMdState());
        assertTrue(p.upgradeAgentsMd());
        assertEquals(WorkspaceProvisioner.AgentsMdState.CURRENT, p.agentsMdState());
        assertTrue(Files.readString(agentsMd(), StandardCharsets.UTF_8).contains("inspect_file"));
    }

    @Test
    @DisplayName("补「联网检索」那一版同样认得")
    void laterLegacyUpgrades() throws IOException {
        writeAgentsMd(LEGACY_V1);
        assertEquals(WorkspaceProvisioner.AgentsMdState.UPGRADABLE, provisioner().agentsMdState());
    }

    @Test
    @DisplayName("CRLF 落盘的旧版默认原文不因换行符误判成用户改写")
    void crlfLegacyStillRecognised() throws IOException {
        writeAgentsMd(LEGACY_V0.replace("\n", "\r\n"));
        assertEquals(WorkspaceProvisioner.AgentsMdState.UPGRADABLE, provisioner().agentsMdState());
    }

    @Test
    @DisplayName("用户动过一个字就不提示、不覆盖")
    void editedPersonaIsNeverTouched() throws IOException {
        String edited = LEGACY_V0 + "\n- 我的规矩：回答必须用中文。\n";
        writeAgentsMd(edited);
        WorkspaceProvisioner p = provisioner();
        assertEquals(WorkspaceProvisioner.AgentsMdState.CUSTOMIZED, p.agentsMdState());
        assertFalse(p.upgradeAgentsMd());
        assertEquals(edited, Files.readString(agentsMd(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("带旧版本标记的模板可升级（模拟未来再发新版）")
    void olderMarkedTemplateUpgrades() throws IOException {
        provisioner().provision();
        String marked = Files.readString(agentsMd(), StandardCharsets.UTF_8)
                .replace(WorkspaceProvisioner.TEMPLATE_VERSION + " -->", "1 -->");
        writeAgentsMd(marked);
        WorkspaceProvisioner p = provisioner();
        assertEquals(WorkspaceProvisioner.AgentsMdState.UPGRADABLE, p.agentsMdState());
        assertTrue(p.upgradeAgentsMd());
    }
}
