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
import lombok.Getter;
import lombok.ToString;

/**
 * 桌面端流式语义事件：AgentScope Reactor 流归一化后送往 UI 渲染层。
 *
 * @author gogym
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
    /** {@link StreamEventType#TOOL_END} 携带的工具输出摘要（已截断）、耗时（毫秒）与成败 */
    private final String toolOutput;
    private final long toolDurationMillis;
    private final boolean toolOk;
    /** {@link StreamEventType#USAGE} 事件携带的本轮累计 token 用量，其余为 null */
    private final TokenUsage usage;

    private StreamEvent(StreamEventType type, String sessionId, String text, String toolName, String toolInput,
                        String toolOutput, long toolDurationMillis, boolean toolOk, TokenUsage usage) {
        this.type = type;
        this.sessionId = sessionId;
        this.text = text;
        this.toolName = toolName;
        this.toolInput = toolInput;
        this.toolOutput = toolOutput;
        this.toolDurationMillis = toolDurationMillis;
        this.toolOk = toolOk;
        this.usage = usage;
    }

    public static StreamEvent textDelta(String sessionId, String text) {
        return new StreamEvent(StreamEventType.TEXT_DELTA, sessionId, text, null, null, null, 0L, false, null);
    }

    public static StreamEvent thinkingDelta(String sessionId, String text) {
        return new StreamEvent(StreamEventType.THINKING_DELTA, sessionId, text, null, null, null, 0L, false, null);
    }

    public static StreamEvent toolStart(String sessionId, String toolName, String toolInput) {
        return new StreamEvent(StreamEventType.TOOL_START, sessionId, null, toolName, toolInput, null, 0L, false, null);
    }

    /**
     * 工具调用结束事件：携带工具名、入参（截断）、输出摘要（截断）、耗时（毫秒）与成败。
     * <p>供落盘层累积为 {@code tool_calls} 列、渲染层还原为可折叠工具卡片。</p>
     */
    public static StreamEvent toolEnd(String sessionId, String toolName, String toolInput, String toolOutput,
                                      long durationMillis, boolean ok) {
        return new StreamEvent(StreamEventType.TOOL_END, sessionId, null, toolName, toolInput,
                toolOutput, durationMillis, ok, null);
    }

    public static StreamEvent confirmRequest(String sessionId, String toolName, String toolInput) {
        return new StreamEvent(StreamEventType.CONFIRM_REQUEST, sessionId, null, toolName, toolInput, null, 0L, false, null);
    }

    /** 本轮 token 用量事件：携带累计的输入/输出/缓存 token，供落盘层写入 assistant 消息。 */
    public static StreamEvent usage(String sessionId, TokenUsage usage) {
        return new StreamEvent(StreamEventType.USAGE, sessionId, null, null, null, null, 0L, false, usage);
    }

    public static StreamEvent complete(String sessionId) {
        return new StreamEvent(StreamEventType.COMPLETE, sessionId, null, null, null, null, 0L, false, null);
    }

    public static StreamEvent error(String sessionId, String message) {
        return new StreamEvent(StreamEventType.ERROR, sessionId, message, null, null, null, 0L, false, null);
    }
}
