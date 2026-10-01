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
    /** {@link StreamEventType#USAGE} 事件携带的本轮累计 token 用量，其余为 null */
    private final TokenUsage usage;

    private StreamEvent(StreamEventType type, String sessionId, String text, String toolName, String toolInput,
                        TokenUsage usage) {
        this.type = type;
        this.sessionId = sessionId;
        this.text = text;
        this.toolName = toolName;
        this.toolInput = toolInput;
        this.usage = usage;
    }

    public static StreamEvent textDelta(String sessionId, String text) {
        return new StreamEvent(StreamEventType.TEXT_DELTA, sessionId, text, null, null, null);
    }

    public static StreamEvent thinkingDelta(String sessionId, String text) {
        return new StreamEvent(StreamEventType.THINKING_DELTA, sessionId, text, null, null, null);
    }

    public static StreamEvent toolStart(String sessionId, String toolName, String toolInput) {
        return new StreamEvent(StreamEventType.TOOL_START, sessionId, null, toolName, toolInput, null);
    }

    public static StreamEvent toolResult(String sessionId, String toolName, String result) {
        return new StreamEvent(StreamEventType.TOOL_RESULT, sessionId, result, toolName, null, null);
    }

    public static StreamEvent confirmRequest(String sessionId, String toolName, String toolInput) {
        return new StreamEvent(StreamEventType.CONFIRM_REQUEST, sessionId, null, toolName, toolInput, null);
    }

    /** 本轮 token 用量事件：携带累计的输入/输出/缓存 token，供落盘层写入 assistant 消息。 */
    public static StreamEvent usage(String sessionId, TokenUsage usage) {
        return new StreamEvent(StreamEventType.USAGE, sessionId, null, null, null, usage);
    }

    public static StreamEvent complete(String sessionId) {
        return new StreamEvent(StreamEventType.COMPLETE, sessionId, null, null, null, null);
    }

    public static StreamEvent error(String sessionId, String message) {
        return new StreamEvent(StreamEventType.ERROR, sessionId, message, null, null, null);
    }
}
