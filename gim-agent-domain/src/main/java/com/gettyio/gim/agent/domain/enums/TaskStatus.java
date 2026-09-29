package com.gettyio.gim.agent.domain.enums;

/**
 * 定时任务状态
 */
public enum TaskStatus {

    /** 就绪，等待触发 */
    READY,

    /** 暂停 */
    PAUSED,

    /** 已完成（一次性任务） */
    FINISHED,

    /** 执行失败 */
    FAILED;

    public static TaskStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return READY;
        }
        for (TaskStatus status : values()) {
            if (status.name().equalsIgnoreCase(code.trim())) {
                return status;
            }
        }
        throw new IllegalArgumentException("不支持的任务状态: " + code);
    }
}
