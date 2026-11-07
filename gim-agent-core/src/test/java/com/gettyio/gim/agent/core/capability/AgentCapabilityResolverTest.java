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
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.entity.SkillPackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 技能白名单三态：未声明（skills_json 为 null/空白）= 仓库里全部技能可见；
 * 显式 {@code []} = 一个都不给；有编码 = 按编码+技能名放行。
 * <p>把「没配置」误当成「配置成空」正是新建智能体看不见技能的原因，三态各锁一条断言。</p>
 *
 * @author gogym
 */
class AgentCapabilityResolverTest {

    @TempDir
    Path temp;

    private AgentCapabilityResolver resolver;
    private HarnessAgentGateway gateway;
    private Set<String> applied;

    @BeforeEach
    void setUp() {
        GimDatabase db = new GimDatabase(new SqliteConnectionProvider(temp.resolve("gim.db")));
        db.initSchema();
        SkillPackage office = new SkillPackage();
        office.setSkillCode("office-documents");
        office.setSkillName("办公文档生成");
        office.setSource("BUILTIN");
        office.setEnabled(1);
        db.skillDao().insert(office);

        resolver = new AgentCapabilityResolver(db);
        gateway = mock(HarnessAgentGateway.class);
        // setSkillAllowlist 是 void，用 doAnswer 抓真实入参：null 与空集在 verify 里区分不开
        applied = null;
        doAnswer(inv -> {
            applied = inv.getArgument(0);
            return null;
        }).when(gateway).setSkillAllowlist(any());
    }

    private AgentDefinition def(String skillsJson) {
        AgentDefinition d = new AgentDefinition();
        d.setAgentCode("a-1");
        d.setAgentName("默认助理");
        d.setSkillsJson(skillsJson);
        d.setMcpServersJson("[]");
        return d;
    }

    @Test
    @DisplayName("从未声明技能：下发 null，仓库技能全部可见")
    void unconfiguredMeansAll() {
        resolver.applyTo(gateway, def(null));
        assertNull(applied);
    }

    @Test
    @DisplayName("空白清单同样按未声明处理")
    void blankMeansAll() {
        resolver.applyTo(gateway, def("   "));
        assertNull(applied);
    }

    @Test
    @DisplayName("显式清空为空数组：下发空集，运行体据此走 SkillFilter.none()")
    void explicitEmptyMeansNone() {
        resolver.applyTo(gateway, def("[]"));
        assertNotNull(applied);
        assertTrue(applied.isEmpty());
    }

    @Test
    @DisplayName("声明的编码连编码带技能名一并放行")
    void declaredCodesCarryNamesToo() {
        resolver.applyTo(gateway, def(CapabilityCodec.writeCodes(List.of("office-documents"))));
        assertTrue(applied.contains("office-documents"), String.valueOf(applied));
        assertTrue(applied.contains("办公文档生成"), String.valueOf(applied));
    }

    @Test
    @DisplayName("库里查不到的编码原样保留，便于手工声明")
    void unknownCodeSurvives() {
        resolver.applyTo(gateway, def("[\"my-own-skill\"]"));
        assertEquals(Set.of("my-own-skill"), applied);
    }

    @Test
    @DisplayName("MCP 清单仍严格：未声明也不连接任何服务")
    void mcpStaysStrict() {
        resolver.applyTo(gateway, def(null));
        verify(gateway).setToolsConfig(org.mockito.ArgumentMatchers.argThat(cfg -> cfg.getMcpServers().isEmpty()),
                any());
    }
}
