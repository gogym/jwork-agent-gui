package com.gettyio.gim.agent.domain.enums;

/**
 * Agent 类型（沿用服务端 MVP 的枚举语义，去掉租户维度）
 */
public enum AgentType {

    /** 单 Agent 对话 */
    SIMPLE,

    /** 工作流编排 */
    WORKFLOW,

    /** 多 Agent 协作 */
    MULTI;

    public static AgentType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return SIMPLE;
        }
        for (AgentType type : values()) {
            if (type.name().equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("不支持的 Agent 类型: " + code);
    }
}
