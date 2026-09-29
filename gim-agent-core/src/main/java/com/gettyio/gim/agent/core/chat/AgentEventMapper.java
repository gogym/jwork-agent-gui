package com.gettyio.gim.agent.core.chat;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;

/**
 * 把 AgentScope 的 {@link AgentEvent} 归一化为桌面 {@link StreamEvent}。
 * <p>基于 2.0.3 实测的 event 子类访问器，按具体类型分派，未知类型跳过。</p>
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
        if (event instanceof ToolCallStartEvent e) {
            return StreamEvent.toolStart(sessionId, e.getToolCallName(), null);
        }
        if (event instanceof ToolResultTextDeltaEvent e) {
            return StreamEvent.toolResult(sessionId, e.getToolCallName(), e.getDelta());
        }
        if (event instanceof RequireUserConfirmEvent e) {
            // M2：仅下发确认信号，完整 HITL 决策链在 M3 接入
            String summary = e.getToolCalls() == null ? "" : e.getToolCalls().toString();
            return StreamEvent.confirmRequest(sessionId, "tool", summary);
        }
        if (event instanceof AgentEndEvent) {
            return StreamEvent.complete(sessionId);
        }
        return null;
    }
}
