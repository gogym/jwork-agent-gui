package com.gettyio.gim.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话分组：纯 UI 组织层，归属到某个智能体（{@code agentCode}）。
 * <p>会话通过 {@code sessions.group_id} 关联到分组，一条会话最多属于一个分组，NULL 即未分组。</p>
 */
@Data
public class SessionGroup {

    private Long id;

    /** 业务分组 ID（与 sessions.group_id 对齐） */
    private String groupId;

    /** 归属智能体：分组与智能体的关联键 */
    private String agentCode;

    private String name;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
