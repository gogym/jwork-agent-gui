package com.gettyio.gim.agent.domain.entity;

import java.time.LocalDateTime;

/**
 * 消息全文搜索命中行：一条非系统消息及其所属会话标题。
 * <p>供 {@code SessionSearchDialog} 的「消息内容」分组渲染；片段截取在 UI 侧按关键词位置计算，
 * 实体只带原文与定位所需的会话/主键。</p>
 */
public record MessageHit(long conversationId, String sessionId, String sessionTitle,
                         String role, LocalDateTime createdAt, String content) {
}
