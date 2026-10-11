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

/**
 * 单次工具调用的 JS 桥接载荷：{@code chat.js} 的 {@code toolRow(r)} 契约视图，
 * 由 {@link com.gettyio.gim.agent.core.chat.ToolCallRecordCodec.ToolCallRecord} 逐条映射而来。
 * <p>{@code ok} 恒序列化（不可用默认值省略判定，失败 false 是有效值）；
 * {@code input/output} 无值时省略，前端 {@code if (r.input)} 判定等价于原 null 语义；
 * {@code durationMs} 为毫秒耗时（未知 0）。</p>
 *
 * @param name       工具名（如 {@code read_file}；前端对 spawn/send 类工具按 {@code input}
 *                   里的 {@code agent_id} 改显委派目标）
 * @param input      调用入参 JSON 文本；无则省略
 * @param output     输出摘要文本；无则省略
 * @param ok         本次调用是否成功（false 时前端计失败数并亮红图标）
 * @param durationMs 调用耗时毫秒（未知 0）
 *
 * @author gogym
 */
@JsonPropertyOrder({"name", "input", "output", "ok", "durationMs"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebChatTool(String name, String input, String output, boolean ok, long durationMs) {
}
