package com.gettyio.gim.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务的一次执行记录（对应 task_runs 表）。
 */
@Data
public class TaskRun {

    private Long id;

    private String taskCode;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** 是否成功 */
    private boolean success;

    /** 成功摘要（应答前若干字） */
    private String summary;

    /** 失败错误信息 */
    private String error;

    /** 本次执行写入的会话 */
    private String sessionId;
}
