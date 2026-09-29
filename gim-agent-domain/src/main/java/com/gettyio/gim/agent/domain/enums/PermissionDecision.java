package com.gettyio.gim.agent.domain.enums;

/**
 * 工具调用安全确认决策，持久化用户"总是允许"选择
 */
public enum PermissionDecision {

    /** 允许本次 */
    ALLOW,

    /** 总是允许该工具（写库，后续不再询问） */
    ALWAYS_ALLOW,

    /** 拒绝 */
    DENY;

    public static PermissionDecision fromCode(String code) {
        if (code == null || code.isBlank()) {
            return DENY;
        }
        for (PermissionDecision decision : values()) {
            if (decision.name().equalsIgnoreCase(code.trim())) {
                return decision;
            }
        }
        throw new IllegalArgumentException("不支持的确认决策: " + code);
    }
}
