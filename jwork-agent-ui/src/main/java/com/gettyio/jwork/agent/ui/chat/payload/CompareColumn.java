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

package com.gettyio.jwork.agent.ui.chat.payload;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 对比卡单列的起始载荷：{@code chat.js} 的 {@code compareStart(cols)} 契约视图。
 * <p>多模型对比发送时每列一条（与列顺序一致），Java 侧 {@code CompareRunner} 组装。</p>
 *
 * @param label   列头标题（模型显示名）
 * @param running 运行中徽章文案（已本地化）
 *
 * @author gogym
 */
@JsonPropertyOrder({"label", "running"})
public record CompareColumn(String label, String running) {
}
