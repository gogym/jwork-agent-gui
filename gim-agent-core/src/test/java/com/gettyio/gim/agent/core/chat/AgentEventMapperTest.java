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

import com.gettyio.gim.agent.domain.enums.StreamEventType;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentEventMapper} 单测：子代理暴露/工具全拒两条旁白，以及带 source 的子代理事件识别。
 *
 * @author gogym
 */
class AgentEventMapperTest {

    private static ToolUseBlock call(String name) {
        return ToolUseBlock.builder().name(name).build();
    }

    @Test
    @DisplayName("正文与思考增量原样下发")
    void deltasPassThrough() {
        StreamEvent text = AgentEventMapper.toStreamEvent("s1", new TextBlockDeltaEvent("r", "b", "你好"));
        assertEquals(StreamEventType.TEXT_DELTA, text.getType());
        assertEquals("你好", text.getText());

        StreamEvent thinking = AgentEventMapper.toStreamEvent("s1", new ThinkingBlockDeltaEvent("r", "b", "嗯"));
        assertEquals(StreamEventType.THINKING_DELTA, thinking.getType());
    }

    @Test
    @DisplayName("子代理暴露为入口：旁白带得上标签")
    void subagentExposedBecomesNotice() {
        StreamEvent notice =
                AgentEventMapper.toStreamEvent("s1", new SubagentExposedEvent("sub-9", "researcher", "s9", "深度调研"));

        assertEquals(StreamEventType.NOTICE, notice.getType());
        assertEquals("subagentExposed", notice.getToolName());
        assertEquals("深度调研", notice.getText());
        assertEquals("s1", notice.getSessionId());
    }

    @Test
    @DisplayName("标签全空也要给个名字，旁白不能是空串")
    void subagentExposedFallsBackToIds() {
        StreamEvent byAgent =
                AgentEventMapper.toStreamEvent("s1", new SubagentExposedEvent("sub-9", "researcher", "s9", "  "));
        assertEquals("researcher", byAgent.getText());

        StreamEvent nameless = AgentEventMapper.toStreamEvent("s1", new SubagentExposedEvent(null, null, null, null));
        assertEquals("subagent", nameless.getText());
    }

    @Test
    @DisplayName("本轮工具全被拒：旁白列出被拒的工具名（去重、逗号分隔）")
    void allToolsDeniedListsNames() {
        StreamEvent notice = AgentEventMapper.toStreamEvent("s1",
                new AllToolsDeniedEvent(List.of(call("execute_shell"), call("execute_shell"), call("write_file"))));

        assertEquals(StreamEventType.NOTICE, notice.getType());
        assertEquals("allToolsDenied", notice.getToolName());
        assertEquals("execute_shell,write_file", notice.getText());
    }

    @Test
    @DisplayName("空拒绝列表也要留占位参数，否则 UI 按无参数渲染会露出 {0}")
    void allToolsDeniedWithoutCallsStillHasArg() {
        StreamEvent notice = AgentEventMapper.toStreamEvent("s1", new AllToolsDeniedEvent(List.of()));

        assertEquals("tool", notice.getText());
    }

    @Test
    @DisplayName("主轮 AgentEnd 才是收尾")
    void agentEndCompletes() {
        StreamEvent done = AgentEventMapper.toStreamEvent("s1", new AgentEndEvent("r1"));

        assertEquals(StreamEventType.COMPLETE, done.getType());
        assertEquals("s1", done.getSessionId());
    }

    @Test
    @DisplayName("带 source 的事件即子代理事件，主轮自身没有该标记")
    void taggedEventsAreSubagentSourced() {
        // withSource 是就地打标记并返回自身，三个判断得各用各的事件对象
        AgentEvent plain = new TextBlockDeltaEvent("r", "b", "x");
        AgentEvent tagged = new TextBlockDeltaEvent("r", "b", "y").withSource("main/researcher");
        AgentEvent blankSource = new TextBlockDeltaEvent("r", "b", "z").withSource("  ");

        assertFalse(HarnessAgentGateway.fromSubagent(plain));
        assertFalse(HarnessAgentGateway.fromSubagent(blankSource));
        assertTrue(HarnessAgentGateway.fromSubagent(tagged));
    }
}
