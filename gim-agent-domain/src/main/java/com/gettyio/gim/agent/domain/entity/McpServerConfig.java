package com.gettyio.gim.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 服务配置（由 MVP AiMcpServerConfig 去租户化移植）
 */
@Data
public class McpServerConfig {

    private Long id;

    private String serverCode;

    private String serverName;

    /** STDIO / HTTP / SSE，见 McpTransportType */
    private String transportType;

    /** STDIO 启动命令 */
    private String command;

    /** 命令参数 JSON 数组 */
    private String argsJson;

    /** 环境变量 JSON 对象 */
    private String envJson;

    /** HTTP/SSE 基础地址 */
    private String baseUrl;

    /** 请求头 JSON 对象 */
    private String headersJson;

    private Integer enabled = 1;

    private Integer timeout = 30;

    /** 连接状态：CONNECTED / DISCONNECTED / UNKNOWN */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
