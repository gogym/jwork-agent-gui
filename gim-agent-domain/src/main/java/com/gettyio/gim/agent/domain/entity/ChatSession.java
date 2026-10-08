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
