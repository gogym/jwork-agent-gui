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

package com.gettyio.gim.agent.ui.chat.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * 助手消息的一个时序分段：{@code chat.js} 的轮次渲染按段还原当时的交错布局，是
 * {@link com.gettyio.gim.agent.core.chat.ChatSegmentCodec.ChatSegment} 的送前端视图——
 * 流式与重放共用它，一轮只有一套排法。
 * <p>旁白段（{@code notice}）到这里已按当前语言解析成文案，前端不再需要编码；
 * 思考段带 {@code ms}（该段思考耗时毫秒），前端据此显示「思考 · Ns」一行。</p>
 * <p>{@code type} 恒序列化；{@code text}、{@code tools}、{@code ms} 无值整体省略。</p>
 *
 * @param type  {@code text} / {@code thinking} / {@code tools} / {@code notice}
 * @param text  text/thinking/notice 段的文本；tools 段省略
 * @param tools tools 段的调用清单；其余段省略
 * @param ms    thinking 段的思考耗时（毫秒）；其余段省略
 *
 * @author gogym
 */
@JsonPropertyOrder({"type", "text", "tools", "ms"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebChatSegment(
        String type,
        @JsonInclude(JsonInclude.Include.NON_NULL) String text,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<WebChatTool> tools,
        Long ms) {
}
