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

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.message.ToolUseBlock;

import java.util.LinkedHashSet;

/**
 * 把 AgentScope 的 {@link AgentEvent} 归一化为桌面 {@link StreamEvent}。
 * <p>基于 2.0.3 实测的 event 子类访问器，按具体类型分派，未知类型跳过。</p>
 *
 * @author gogym
 */
public final class AgentEventMapper {

    private AgentEventMapper() {
    }

    /**
     * @return 归一化事件；返回 null 表示该 Agent 事件不下发给 UI
     */
    public static StreamEvent toStreamEvent(String sessionId, AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent e) {
            return StreamEvent.textDelta(sessionId, e.getDelta());
        }
        if (event instanceof ThinkingBlockDeltaEvent e) {
            return StreamEvent.thinkingDelta(sessionId, e.getDelta());
        }
        // 工具生命周期事件（ToolCallStart/Delta/End、ToolResult*）由 {@link HarnessAgentGateway} 聚合为
        // TOOL_START / TOOL_END 下发，不经本无状态映射器，故此处不再处理工具事件。
        // ExceedMaxItersEvent 同样由网关处理：它要触发自动续跑，不是无状态映射能表达的。
        if (event instanceof RequireUserConfirmEvent e) {
            // M2：仅下发确认信号，完整 HITL 决策链在 M3 接入
            String summary = e.getToolCalls() == null ? "" : e.getToolCalls().toString();
            return StreamEvent.confirmRequest(sessionId, "tool", summary);
        }
        if (event instanceof AgentEndEvent) {
            return StreamEvent.complete(sessionId);
        }
        // 子代理被暴露成可直接对话的入口：桌面端没有多入口概念，至少让用户知道有新智能体在接手
        if (event instanceof SubagentExposedEvent e) {
            return StreamEvent.notice(sessionId, "subagentExposed", exposedLabel(e));
        }
        // 本轮工具调用全部被拒：框架可能就此收步，正文明说一句，别让回复看起来像断在半路
        if (event instanceof AllToolsDeniedEvent e) {
            return StreamEvent.notice(sessionId, "allToolsDenied", deniedNames(e));
        }
        return null;
    }

    /** 暴露入口的显示名：标签优先，退回智能体 ID，都没有就给个不会渲染成空串的名字。 */
    private static String exposedLabel(SubagentExposedEvent event) {
        for (String candidate
                : new String[] {event.getLabel(), event.getAgentId(), event.getSubagentId()}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "subagent";
    }

    /** 被拒工具名（去重、逗号分隔）：NOTICE 的参数按逗号拆成占位符，别的分隔符会连成一坨。 */
    private static String deniedNames(AllToolsDeniedEvent event) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (ToolUseBlock call : event.getDeniedToolCalls()) {
            if (call != null && call.getName() != null && !call.getName().isBlank()) {
                names.add(call.getName().trim());
            }
        }
        return names.isEmpty() ? "tool" : String.join(",", names);
    }
}
