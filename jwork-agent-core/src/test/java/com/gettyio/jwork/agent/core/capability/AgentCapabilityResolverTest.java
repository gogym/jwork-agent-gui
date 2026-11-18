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

package com.gettyio.jwork.agent.core.capability;

import com.gettyio.jwork.agent.core.chat.HarnessAgentGateway;
import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.persist.SqliteConnectionProvider;
import com.gettyio.jwork.agent.domain.entity.AgentDefinition;
import com.gettyio.jwork.agent.domain.entity.McpServerConfig;
import com.gettyio.jwork.agent.domain.entity.SkillPackage;
import com.gettyio.jwork.agent.domain.security.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
    private JworkDatabase db;

    @BeforeEach
    void setUp() {
        db = new JworkDatabase(new SqliteConnectionProvider(temp.resolve("jwork.db")));
        db.initSchema();
        SkillPackage office = new SkillPackage();
        office.setSkillCode("office-documents");
        office.setSkillName("办公文档生成");
        office.setSource("BUILTIN");
        office.setEnabled(1);
        db.skillDao().insert(office);

        resolver = new AgentCapabilityResolver(db, null);
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

    @Test
    @DisplayName("MCP 密钥：库中密文在翻译配置时解密回明文下发")
    void mcpSecretsDecryptedOnApply() {
        SecretCipher cipher = new SecretCipher() {
            @Override
            public String encrypt(String plainText) {
                return prefix() + Base64.getEncoder().encodeToString(plainText.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public String decrypt(String cipherText) {
                return new String(Base64.getDecoder().decode(cipherText.substring(prefix().length())),
                        StandardCharsets.UTF_8);
            }

            @Override
            public String prefix() {
                return "t:";
            }
        };
        McpServerConfig row = new McpServerConfig();
        row.setServerCode("s1");
        row.setServerName("联网搜索");
        row.setTransportType("HTTP");
        row.setBaseUrl("https://example.com/mcp");
        row.setEnabled(1);
        row.setHeadersJson(McpSecrets.writeMap(Map.of("Authorization", "Bearer sk-1"), cipher));
        db.mcpServerDao().insert(row);

        io.agentscope.harness.agent.tools.ToolsConfig[] captured =
                new io.agentscope.harness.agent.tools.ToolsConfig[1];
        doAnswer(inv -> {
            captured[0] = inv.getArgument(0);
            return null;
        }).when(gateway).setToolsConfig(any(), any());
        AgentDefinition d = def(null);
        d.setMcpServersJson(CapabilityCodec.writeCodes(List.of("s1")));
        new AgentCapabilityResolver(db, cipher).applyTo(gateway, d);

        assertNotNull(captured[0]);
        assertEquals("Bearer sk-1",
                captured[0].getMcpServers().get("s1").getHeaders().get("Authorization"));
    }
}
