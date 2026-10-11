/*
 * Copyright 2026 gogym
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gettyio.gim.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 服务配置（由 MVP AiMcpServerConfig 去租户化移植）
 *
 * @author gogym
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
