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

package com.gettyio.jwork.agent.core.chat;

import com.gettyio.jwork.agent.core.capability.AgentCapabilityResolver;
import com.gettyio.jwork.agent.domain.dao.AgentDefinitionDao;
import com.gettyio.jwork.agent.domain.entity.AgentDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MultiAgentGateway} 运行体缓存/失效与工作区回退单测。
 * <p>巧妙利用 {@link HarnessAgentGateway#stream} 返回惰性 {@code Flux}——不订阅即不触发模型/Agent 重组，
 * 故 {@code gatewayFor→build} 的装配（assembler、能力下发、缓存写入）可被纯测，无需 AgentScope 运行时。</p>
 *
 * @author gogym
 */
class MultiAgentGatewayTest {

    @TempDir
    Path temp;

    private AgentDefinitionDao dao;
    private AgentCapabilityResolver capabilities;
    private Path shared;
    private MultiAgentGateway gateway;

    private final AtomicInteger assembled = new AtomicInteger();
    private final List<HarnessAgentGateway> built = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dao = mock(AgentDefinitionDao.class);
        capabilities = mock(AgentCapabilityResolver.class);
        shared = temp.resolve("shared");
        when(dao.findEnabled()).thenReturn(List.of());
        MultiAgentGateway.ModelResolver resolver = (definition, providerCode, modelCode) -> null;
        gateway = new MultiAgentGateway(dao, resolver, "默认人格", shared, capabilities, g -> {
            assembled.incrementAndGet();
            built.add(g);
        });
    }

    private static ChatRequest req(String agentCode, String providerCode, String modelCode) {
        return ChatRequest.builder()
                .agentCode(agentCode)
                .sessionId("s-" + agentCode)
                .text("hi")
                .providerCode(providerCode)
                .modelCode(modelCode)
                .build();
    }

    @Test
    @DisplayName("同键二次请求命中缓存：只装配一次，复用同一运行体")
    void sameKeyAssemblesOnceAndReuses() {
        ChatRequest r = req("A", null, null);
        gateway.stream(r);
        gateway.stream(r);

        assertEquals(1, assembled.get(), "相同 agentCode/provider/model 应命中缓存");
        assertEquals(1, built.size());
        verify(capabilities, times(1)).applyTo(any(HarnessAgentGateway.class), any(AgentDefinition.class));
    }

    @Test
    @DisplayName("不同 modelCode 视为不同运行体：各装配一次")
    void distinctModelYieldsSeparateRuntime() {
        gateway.stream(req("A", "p", "m1"));
        gateway.stream(req("A", "p", "m2"));

        assertEquals(2, assembled.get(), "模型覆盖不同应产生独立运行体");
        assertTrue(built.get(0) != built.get(1));
    }

    @Test
    @DisplayName("invalidate 后同键重装：装配计数递增")
    void invalidateForcesReassembly() {
        ChatRequest r = req("A", null, null);
        gateway.stream(r);
        assertEquals(1, assembled.get());

        gateway.invalidate("A");
        gateway.stream(r);
        assertEquals(2, assembled.get(), "失效后应重新装配");
    }

    @Test
    @DisplayName("invalidate 仅影响目标智能体：其它智能体运行体保持缓存")
    void invalidateScopedToItsAgent() {
        gateway.stream(req("A", null, null)); // A: 1
        gateway.stream(req("B", null, null)); // B: 2
        assertEquals(2, assembled.get());

        gateway.invalidate("A");
        HarnessAgentGateway beforeB = built.get(1);
        gateway.stream(req("B", null, null)); // B 仍命中缓存
        assertEquals(2, assembled.get(), "失效 A 不应波及 B 的缓存");
        assertSame(beforeB, built.get(1), "B 复用同一运行体实例");

        gateway.stream(req("A", null, null)); // A 已失效，重装：3
        assertEquals(3, assembled.get());
    }

    @Test
    @DisplayName("工作区：定义缺失按默认专属落点解析并创建")
    void workspaceDefaultsForMissingDef() {
        when(dao.findByCode("X")).thenReturn(Optional.empty());
        Path got = gateway.workspaceForAgent("X");
        assertEquals(shared.resolve("agents").resolve("X"), got);
        assertTrue(Files.isDirectory(got), "默认专属工作区应被创建");
    }

    @Test
    @DisplayName("工作区：未配置 workspace_dir 的智能体默认专属 agents/<编码>/")
    void workspaceDefaultsToAgentDir() {
        AgentDefinition d = new AgentDefinition();
        d.setAgentCode("agent-abc12345");
        when(dao.findByCode("agent-abc12345")).thenReturn(Optional.of(d));

        Path got = gateway.workspaceForAgent("agent-abc12345");
        assertEquals(shared.resolve("agents").resolve("agent-abc12345"), got);
        assertTrue(Files.isDirectory(got), "默认专属工作区应被创建");
    }

    @Test
    @DisplayName("工作区：默认智能体未配置时保持共享根（全局记忆载体）")
    void defaultAgentKeepsSharedWorkspace() {
        AgentDefinition d = new AgentDefinition();
        d.setAgentCode(MultiAgentGateway.DEFAULT_AGENT_CODE);
        when(dao.findByCode(MultiAgentGateway.DEFAULT_AGENT_CODE)).thenReturn(Optional.of(d));

        assertEquals(shared, gateway.workspaceForAgent(MultiAgentGateway.DEFAULT_AGENT_CODE));
    }

    @Test
    @DisplayName("工作区：相对目录解析到共享目录之内并创建")
    void workspaceResolvesRelativeInsideShared() {
        AgentDefinition d = new AgentDefinition();
        d.setAgentCode("A");
        d.setWorkspaceDir("sub");
        when(dao.findByCode("A")).thenReturn(Optional.of(d));

        Path got = gateway.workspaceForAgent("A");
        assertEquals(shared.resolve("sub"), got);
        assertTrue(Files.isDirectory(got), "相对工作区应被创建");
    }

    @Test
    @DisplayName("工作区：越出共享目录的相对路径回退共享目录")
    void workspaceOutOfBoundsFallsBack() {
        AgentDefinition d = new AgentDefinition();
        d.setAgentCode("C");
        d.setWorkspaceDir("../escape");
        when(dao.findByCode("C")).thenReturn(Optional.of(d));

        assertEquals(shared, gateway.workspaceForAgent("C"), "越界工作区应回退共享目录");
    }

    @Test
    @DisplayName("工作区：空白 agentCode 归一为默认智能体后回退")
    void blankAgentCodeNormalizesToDefault() {
        when(dao.findByCode(anyString())).thenReturn(Optional.empty());
        assertEquals(shared, gateway.workspaceForAgent("   "));
        verify(dao).findByCode(MultiAgentGateway.DEFAULT_AGENT_CODE);
    }
}
