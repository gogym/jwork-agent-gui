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

package com.gettyio.gim.agent.core.chat;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.core.capability.CapabilityCodec;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.entity.SkillPackage;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 占用浮层分类估算的口径锁定：系统提示词 = 定义提示词（空白回退 AGENTS.md）+ 工作区注入文档；
 * 工具 = 内置经验值 + 声明 MCP 服务数×单服务估值；技能 = 白名单内名称+描述体量；消息 = 历史字符/2.5。
 * <p>各项独立粗估、只读库与磁盘，不装配运行体——浮层悬停不该付出装配代价。</p>
 *
 * @author gogym
 */
class ChatServiceBreakdownTest {

    @TempDir
    Path temp;

    private GimDatabase db;
    private ChatService service;
    private Path ws;

    @BeforeEach
    void setUp() throws Exception {
        db = new GimDatabase(new SqliteConnectionProvider(temp.resolve("gim.db")));
        db.initSchema();
        ws = temp.resolve("ws");
        Files.createDirectories(ws);
        AgentGateway gateway = mock(AgentGateway.class);
        when(gateway.workspaceForAgent("a1")).thenReturn(ws);
        when(gateway.workspaceForAgent("a2")).thenReturn(ws);
        service = new ChatService(gateway, db, new GimPaths(temp), null);

        AgentDefinition a1 = agent("a1");
        a1.setSystemPrompt("p".repeat(250));
        a1.setSkillsJson(CapabilityCodec.writeCodes(List.of("s1")));
        a1.setMcpServersJson(CapabilityCodec.writeCodes(List.of("m1", "m2")));
        db.agentDao().insert(a1);
        SkillPackage s1 = new SkillPackage();
        s1.setSkillCode("s1");
        s1.setSkillName("n".repeat(10));
        s1.setDescription("d".repeat(40));
        s1.setSource("BUILTIN");
        s1.setEnabled(1);
        db.skillDao().insert(s1);
        Files.writeString(ws.resolve("KNOWLEDGE.md"), "k".repeat(100));
        Files.writeString(ws.resolve("MEMORY.md"), "m".repeat(50));
    }

    private static AgentDefinition agent(String code) {
        AgentDefinition d = new AgentDefinition();
        d.setAgentCode(code);
        d.setAgentName("助理" + code);
        d.setAgentType("SIMPLE");
        return d;
    }

    @Test
    @DisplayName("四类估算各归其源：提示词+注入文档 / 内置+MCP / 技能描述 / 历史字符")
    void breakdownReadsEachSource() {
        ContextBreakdown bd = service.contextBreakdown("a1", 1000);
        assertEquals(160, bd.systemTokens(), "(250 提示词 + 100 知识 + 50 记忆) / 2.5");
        assertEquals(2400, bd.toolTokens(), "1400 内置 + 2 服务 × 500");
        assertEquals(20, bd.skillTokens(), "(10 名称 + 40 描述) / 2.5");
        assertEquals(1, bd.skillCount());
        assertEquals(400, bd.messageTokens(), "1000 历史字符 / 2.5");
    }

    @Test
    @DisplayName("提示词空白回退 AGENTS.md；未声明技能/MCP 按零与内置底值")
    void blankPromptFallsBackToAgentsMd() throws Exception {
        Files.writeString(ws.resolve("AGENTS.md"), "a".repeat(75));
        AgentDefinition a2 = agent("a2");
        a2.setSystemPrompt("   ");
        db.agentDao().insert(a2);
        ContextBreakdown bd = service.contextBreakdown("a2", 0);
        assertEquals(90, bd.systemTokens(), "(75 AGENTS + 100 知识 + 50 记忆) / 2.5");
        assertEquals(1400, bd.toolTokens(), "未声明 MCP 只剩内置底值");
        assertEquals(0, bd.skillTokens());
        assertEquals(0, bd.skillCount());
        assertEquals(0, bd.messageTokens());
    }

    @Test
    @DisplayName("未知智能体：无工作区无定义，系统/技能归零，消息仍按历史估")
    void unknownAgentDegradesToZero() {
        ContextBreakdown bd = service.contextBreakdown("nope", 250);
        assertEquals(0, bd.systemTokens());
        assertEquals(1400, bd.toolTokens());
        assertEquals(0, bd.skillTokens());
        assertEquals(0, bd.skillCount());
        assertEquals(100, bd.messageTokens());
    }
}