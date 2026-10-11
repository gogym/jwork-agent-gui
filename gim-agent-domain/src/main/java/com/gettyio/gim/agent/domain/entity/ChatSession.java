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

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话（由 MVP AiSession 去租户化移植）
 *
 * @author gogym
 */
@Data
public class ChatSession {

    private Long id;

    /** 业务会话 ID（与 AgentScope sessionId 对齐） */
    private String sessionId;

    /** 归属智能体：会话与智能体的唯一关联键 */
    private String agentCode;

    /** 所属分组 ID（可空，NULL=未分组）；仅当前智能体范围内的 UI 组织 */
    private String groupId;

    private String title;

    private String modelCode;

    private Integer messageCount = 0;

    /** ACTIVE / CLOSED，见 SessionStatus */
    private String status;

    /** 置顶：1=置顶，侧栏置顶会话恒排在最前 */
    private Integer pinned = 0;

    /** 收藏：1=收藏，侧栏以星标标记 */
    private Integer favorite = 0;

    /** 分支来源会话 ID（复制会话到新分叉点时记录父会话），非分支会话为 null */
    private String parentSessionId;

    /** 最近一次手动压缩后的上下文估算（token）：新一轮真实用量回报覆盖前，占用条以它为显示基准；null=无 */
    private Integer compactedTokens;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
