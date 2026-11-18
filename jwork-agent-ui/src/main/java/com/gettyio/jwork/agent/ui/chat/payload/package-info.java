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

/**
 * 聊天页 Java→JS 桥接载荷 DTO：{@code chat.js} 各 {@code ChatAPI.*} 入口的结构化入参视图。
 * <p>Java 侧把消息/对比卡/文案包组装为本包的记录（record），经 Jackson 序列化后以脚本参数
 * 下发前端；字段名与省略规则是对 {@code chat.js} 的稳定契约（各记录类 javadoc 逐字段说明），
 * 改动需同步前端。序列化统一走
 * {@link com.gettyio.jwork.agent.ui.chat.ChatMessagePayload#jsValue(Object)} 与
 * {@link com.gettyio.jwork.agent.ui.chat.ChatMessagePayload#toJson(java.util.List)} 出口，
 * 保证非法数据降级不破坏脚本字符串。</p>
 */
package com.gettyio.jwork.agent.ui.chat.payload;
