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

    /** Quartz cron 表达式（周期性任务）；与 {@link #runAt} 至少居其一 */
    private String cronExpression;

    /** 一次性任务的指定执行时刻（ISO-8601）；非空到时一次性触发，与 cron 互斥 */
    private LocalDateTime runAt;

    /** 状态，见 TaskStatus */
    private String status;

    /** 是否一次性任务 */
    private Integer once = 0;

    private LocalDateTime lastRunAt;

    private LocalDateTime nextRunAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
