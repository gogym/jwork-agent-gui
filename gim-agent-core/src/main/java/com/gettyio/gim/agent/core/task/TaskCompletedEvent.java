package com.gettyio.gim.agent.core.task;

import lombok.Getter;
import lombok.ToString;

/**
 * 定时任务执行完成事件：由 scheduler 投递，UI 侧以系统通知呈现。
 */
@Getter
@ToString
public class TaskCompletedEvent {

    private final String taskCode;
    private final String taskName;
    private final String sessionId;
    /** 执行结果摘要（助手应答首段或错误信息） */
    private final String summary;
    private final boolean success;

    public TaskCompletedEvent(String taskCode, String taskName, String sessionId, String summary, boolean success) {
        this.taskCode = taskCode;
        this.taskName = taskName;
        this.sessionId = sessionId;
        this.summary = summary;
        this.success = success;
    }
}
