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

package com.gettyio.jwork.agent.core.capability;

import com.gettyio.jwork.agent.domain.enums.McpTransportType;
import com.gettyio.jwork.agent.domain.security.SecretCipher;
import io.agentscope.harness.agent.tools.McpServerConfig;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把 {@code mcp_servers} 表的一行翻译成 AgentScope 的 MCP 客户端配置。
 * <p>字段对齐：{@code transport_type→transport}、{@code command/args_json/env_json} 用于 stdio，
 * {@code base_url/headers_json} 用于 sse/http；{@code args_json} 允许写成 JSON 数组或空格分隔。</p>
 * <p>env/headers 的值入库时逐个加密（{@link McpSecrets}），翻译时须带 {@link SecretCipher}
 * 解密回明文再交给 AgentScope；不带 cipher 的重载按明文读（单测用）。</p>
 * <p>超时：表里的 timeout 是「单次请求秒数」，另外给初始化设一个上限，避免不可达的服务把首轮对话
 * 拖到用户以为程序卡死。</p>
 *
 * @author gogym
 */
public final class McpServerConfigs {

    /** 连接初始化上限（秒）：即使配置了更大的 timeout，也不让装配长时间阻塞 */
    private static final long INIT_TIMEOUT_CAP_SECONDS = 20;
    private static final long DEFAULT_REQUEST_TIMEOUT_SECONDS = 30;

    private McpServerConfigs() {
    }

    /**
     * @param row mcp_servers 表记录（调用方保证非空且已启用）
     * @return AgentScope MCP 服务配置
     * @throws IllegalArgumentException 传输类型不支持
     */
    public static McpServerConfig from(com.gettyio.jwork.agent.domain.entity.McpServerConfig row) {
        return from(row, null);
    }

    /**
     * @param row mcp_servers 表记录（调用方保证非空且已启用）
     * @param cipher 密钥解密器；null 表示 env/headers 按明文读
     * @return AgentScope MCP 服务配置
     * @throws IllegalArgumentException 传输类型不支持
     */
    public static McpServerConfig from(com.gettyio.jwork.agent.domain.entity.McpServerConfig row, SecretCipher cipher) {
        McpServerConfig cfg = new McpServerConfig();
        cfg.setTransport(McpTransportType.fromCode(row.getTransportType()).name().toLowerCase(Locale.ROOT));
        cfg.setCommand(blankToNull(row.getCommand()));
        List<String> args = CapabilityCodec.readArgs(row.getArgsJson());
        if (!args.isEmpty()) {
            cfg.setArgs(args);
        }
        Map<String, String> env = McpSecrets.readMap(row.getEnvJson(), cipher);
        if (!env.isEmpty()) {
            cfg.setEnv(env);
        }
        cfg.setUrl(blankToNull(row.getBaseUrl()));
        Map<String, String> headers = McpSecrets.readMap(row.getHeadersJson(), cipher);
        if (!headers.isEmpty()) {
            cfg.setHeaders(headers);
        }
        long requestTimeout = row.getTimeout() == null || row.getTimeout() <= 0
                ? DEFAULT_REQUEST_TIMEOUT_SECONDS : row.getTimeout();
        cfg.setTimeout(Duration.ofSeconds(requestTimeout));
        cfg.setInitializationTimeout(Duration.ofSeconds(Math.min(requestTimeout, INIT_TIMEOUT_CAP_SECONDS)));
        return cfg;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
