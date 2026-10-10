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

    /** 到期时间（ISO-8601）；非空时过期后不再触发（已注册的周期任务同步裁剪） */
    private LocalDateTime endAt;

    /** 状态，见 TaskStatus */
    private String status;

    /** 是否一次性任务 */
    private Integer once = 0;

    /** 输出模式，见 TaskOutputMode（MERGED/SEPARATE）；独立模式下每次执行创建独立会话 */
    private String outputMode = "MERGED";

    /** 是否无人值守运行：1 时执行中自动通过工具授权，0 时仅只读工具 */
    private Integer unattended = 0;

    /** 模型覆盖-提供方编码（与 {@link #modelCode} 成对生效；空则用智能体默认） */
    private String providerCode;

    /** 模型覆盖-模型编码（与 {@link #providerCode} 成对生效；空则用智能体默认） */
    private String modelCode;

    private LocalDateTime lastRunAt;

    private LocalDateTime nextRunAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
