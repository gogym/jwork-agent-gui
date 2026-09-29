package com.gettyio.gim.agent.core.chat;

import com.gettyio.gim.agent.domain.enums.StreamEventType;
import lombok.Getter;
import lombok.ToString;

/**
 * 桌面端流式语义事件：AgentScope Reactor 流归一化后送往 UI 渲染层。
 */
@Getter
@ToString
public class StreamEvent {

    private final StreamEventType type;
    private final String sessionId;
    /** 文本增量 / 完成文本 / 错误信息 */
    private final String text;
    /** 工具相关事件的字段 */
    private final String toolName;
    private final String toolInput;

    private StreamEvent(StreamEventType type, String sessionId, String text, String toolName, String toolInput) {
        this.type = type;
        this.sessionId = sessionId;
        this.text = text;
        this.toolName = toolName;
        this.toolInput = toolInput;
    }

    public static StreamEvent textDelta(String sessionId, String text) {
        return new StreamEvent(StreamEventType.TEXT_DELTA, sessionId, text, null, null);
    }

    public static StreamEvent thinkingDelta(String sessionId, String text) {
        return new StreamEvent(StreamEventType.THINKING_DELTA, sessionId, text, null, null);
    }

    public static StreamEvent toolStart(String sessionId, String toolName, String toolInput) {
        return new StreamEvent(StreamEventType.TOOL_START, sessionId, null, toolName, toolInput);
    }

    public static StreamEvent toolResult(String sessionId, String toolName, String result) {
        return new StreamEvent(StreamEventType.TOOL_RESULT, sessionId, result, toolName, null);
    }

    public static StreamEvent confirmRequest(String sessionId, String toolName, String toolInput) {
        return new StreamEvent(StreamEventType.CONFIRM_REQUEST, sessionId, null, toolName, toolInput);
    }

    public static StreamEvent complete(String sessionId) {
        return new StreamEvent(StreamEventType.COMPLETE, sessionId, null, null, null);
    }

    public static StreamEvent error(String sessionId, String message) {
        return new StreamEvent(StreamEventType.ERROR, sessionId, message, null, null);
    }
}
