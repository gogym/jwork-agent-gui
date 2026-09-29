package com.gettyio.gim.agent.domain.enums;

/**
 * MCP 传输类型
 */
public enum McpTransportType {

    STDIO,
    HTTP,
    SSE;

    public static McpTransportType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return STDIO;
        }
        for (McpTransportType type : values()) {
            if (type.name().equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("不支持的 MCP 传输类型: " + code);
    }
}
