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

package com.gettyio.gim.agent.domain.entity;

import java.time.LocalDateTime;

/**
 * 消息全文搜索命中行：一条非系统消息及其所属会话标题。
 * <p>供 {@code SessionSearchDialog} 的「消息内容」分组渲染；片段截取在 UI 侧按关键词位置计算，
 * 实体只带原文与定位所需的会话/主键。</p>
 *
 * @author gogym
 */
public record MessageHit(long conversationId, String sessionId, String sessionTitle,
                         String role, LocalDateTime createdAt, String content) {
}
