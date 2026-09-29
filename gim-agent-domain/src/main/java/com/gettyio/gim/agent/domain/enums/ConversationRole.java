package com.gettyio.gim.agent.domain.enums;

/**
 * 对话消息角色
 */
public enum ConversationRole {

    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    TOOL("tool");

    private final String code;

    ConversationRole(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ConversationRole fromCode(String code) {
        for (ConversationRole role : values()) {
            if (role.code.equalsIgnoreCase(code)) {
                return role;
            }
        }
        throw new IllegalArgumentException("不支持的角色: " + code);
    }
}
