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

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.model.openai.exception.RateLimitException;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手动压缩的本地降级链路：模型永久不可用（额度触顶 429）时摘要调用失败，
 * 压缩自动改走「整段归档 JSONL + 按预算保留尾部 + 说明消息」，不耗模型且占用下降。
 * <p>状态存储注入内存实现：测试不往用户主目录落会话状态；归档仍落临时工作区以便断言内容。</p>
 *
 * @author gogym
 */
class HarnessAgentGatewayLocalCompactionTest {

    @TempDir
    Path temp;

    private HarnessAgentGateway gateway;

    @BeforeEach
    void setUp() {
        // 永远 429 额度触顶的模型：摘要与记忆沉淀两段调用都会失败，触发降级
        Model dead = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.error(new RateLimitException(
                        "429 BudgetLimitExceeded: workspace budget exhausted", "BudgetLimitExceeded", "{}"));
            }

            @Override
            public String getModelName() {
                return "fake-budget-dead";
            }

            @Override
            public int getContextWindowSize() {
                // 上报真实量级窗口：强制配置 triggerTokens=0 走动态阈值，不上报会兜底 160k 而永不触发
                return 32_000;
            }
        };
        gateway = new HarnessAgentGateway("t-local-compact", "本地压缩测试", "测试提示词",
                () -> dead, temp.resolve("ws"));
        gateway.setStateStoreOverride(new InMemoryAgentStateStore());
    }

    @Test
    @DisplayName("额度触顶：降级本地压缩，归档全量历史且尾部按预算保留")
    void budgetDeadFallsBackToLocalArchive() throws Exception {
        HarnessAgent a = gateway.agent();
        List<Msg> seed = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            seed.add(Msg.builder()
                    .role(i % 2 == 0 ? MsgRole.USER : MsgRole.ASSISTANT)
                    .textContent("第 " + i + " 轮对话内容 " + "x".repeat(8000))
                    .build());
        }
        a.getDelegate().getAgentState(null, "s1").contextMutable().addAll(seed);
        a.getDelegate().saveAgentState(null, "s1");

        ContextCompactionResult result = gateway.compactContext("s1").block(Duration.ofSeconds(120));
        assertNotNull(result);
        assertTrue(result.compacted(), "降级压缩也应报告已压缩");
        assertTrue(result.localFallback(), "模型不可用必须走本地降级而非报错");
        assertTrue(result.estimatedTokens() > 0);

        List<Msg> after = a.getDelegate().getAgentState(null, "s1").contextMutable();
        assertTrue(after.size() < seed.size(), "占用应下降: " + after.size() + " vs " + seed.size());
        assertTrue(after.size() >= 2, "尾部至少留一条真实消息");
        assertEquals("local-compaction", after.get(0).getName());
        assertTrue(after.get(0).getTextContent().contains("context-archive-"), "说明消息应带归档路径");
        assertEquals(seed.get(seed.size() - 1).getTextContent(), after.get(after.size() - 1).getTextContent(),
                "尾部保留的应是最近的消息");

        Path dir = temp.resolve("ws").resolve("sessions").resolve("s1");
        List<Path> archives;
        try (Stream<Path> files = Files.list(dir)) {
            archives = files.filter(p -> p.getFileName().toString().startsWith("context-archive-")).toList();
        }
        assertEquals(1, archives.size(), "应且仅应有一份归档");
        List<String> lines = Files.readAllLines(archives.get(0));
        assertEquals(seed.size(), lines.size(), "归档须含全量历史，一条不丢");
        String firstLine = lines.get(0);
        assertTrue(firstLine.contains("\"role\":\"USER\""),
                "归档行应是带角色的 JSON: " + firstLine.substring(0, Math.min(160, firstLine.length())));
        assertTrue(lines.get(lines.size() - 1).contains("\"role\":\"ASSISTANT\",\"name\":\"\",\"text\":\""),
                "末行应是完整闭合的 ASSISTANT JSON");
    }

    @Test
    @DisplayName("空上下文：跳过压缩，不触发降级也不写归档")
    void emptyContextSkipsWithoutFallback() {
        ContextCompactionResult result = gateway.compactContext("s-empty").block(Duration.ofSeconds(120));
        assertNotNull(result);
        assertFalse(result.compacted());
        assertFalse(result.localFallback());
        assertFalse(Files.exists(temp.resolve("ws").resolve("sessions").resolve("s-empty")));
    }
}