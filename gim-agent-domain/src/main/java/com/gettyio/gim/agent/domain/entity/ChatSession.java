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

    private String agentCode;

    private String title;

    /** 所属工作区分组 ID（可空，未分组为 null） */
    private String workspaceId;

    private String modelCode;

    private Integer messageCount = 0;

    /** ACTIVE / CLOSED，见 SessionStatus */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
