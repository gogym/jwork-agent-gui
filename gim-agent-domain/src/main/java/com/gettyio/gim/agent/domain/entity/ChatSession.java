package com.gettyio.gim.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话（由 MVP AiSession 去租户化移植）
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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
