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

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 对比卡单列的定格页脚载荷：{@code chat.js} 的 {@code compareEnd(i, state, foot)} 契约视图。
 * <p>一列终结（完成/出错/停止）时由 Java 侧 {@code CompareRunner} 组装：徽章文案随终态换色，
 * 正文为耗时（秒，一位小数）· 总 tokens（无用量回报时只显耗时）。</p>
 *
 * @param badge 终态徽章文案（已完成/失败/已停止，已本地化）
 * @param text  耗时与用量文本（已本地化）
 *
 * @author gogym
 */
@JsonPropertyOrder({"badge", "text"})
public record CompareFoot(String badge, String text) {
}
