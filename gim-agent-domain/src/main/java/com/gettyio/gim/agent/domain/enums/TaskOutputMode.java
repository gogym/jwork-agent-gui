package com.gettyio.gim.agent.domain.enums;

/**
 * 自动化任务输出模式
 */
public enum TaskOutputMode {

    /** 合并输出：所有执行结果汇聚到同一个会话（task-{code}） */
    MERGED,

    /** 独立输出：每次执行创建独立会话（task-{code}-yyyyMMdd-HHmmss） */
    SEPARATE;

    public static TaskOutputMode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return MERGED;
        }
        for (TaskOutputMode mode : values()) {
            if (mode.name().equalsIgnoreCase(code.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("不支持的任务输出模式: " + code);
    }
}
