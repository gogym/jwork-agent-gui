package com.gettyio.gim.agent.domain.enums;

/**
 * 会话状态
 */
public enum SessionStatus {

    ACTIVE("ACTIVE"),
    CLOSED("CLOSED");

    private final String code;

    SessionStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static SessionStatus fromCode(String code) {
        for (SessionStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("不支持的会话状态: " + code);
    }
}
