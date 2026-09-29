package com.gettyio.gim.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务（AgentProactiveMessage 语义迁移：到点以系统消息投递给 Agent）
 */
@Data
public class ScheduledTask {

    private Long id;

    private String taskCode;

    private String taskName;

    private String agentCode;

    /** 投递给 Agent 的任务指令 */
    private String prompt;

    /** Quartz cron 表达式 */
    private String cronExpression;

    /** 状态，见 TaskStatus */
    private String status;

    /** 是否一次性任务 */
    private Integer once = 0;

    private LocalDateTime lastRunAt;

    private LocalDateTime nextRunAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
