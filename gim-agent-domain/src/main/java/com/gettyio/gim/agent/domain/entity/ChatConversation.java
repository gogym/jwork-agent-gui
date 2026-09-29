package com.gettyio.gim.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息（由 MVP AiConversation 去租户化移植）
 */
@Data
public class ChatConversation {

    private Long id;

    private String sessionId;

    private String agentCode;

    /** user / assistant / system / tool，见 ConversationRole */
    private String role;

    private String content;

    private Integer tokensUsed;

    private String modelCode;

    /** 工具调用记录 JSON 数组 */
    private String toolCalls;

    /** 扩展元数据 JSON */
    private String metadata;

    private LocalDateTime createdAt;
}
