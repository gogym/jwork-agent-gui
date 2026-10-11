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

package com.gettyio.gim.agent.core.capability;

import com.gettyio.gim.agent.domain.entity.McpServerConfig;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务连通性探测：按 {@code mcp_servers} 表配置真实建连一次（transport 装配与框架
 * {@code McpServerRegistrar} 同口径，配置翻译复用 {@link McpServerConfigs}），握手成功即
 * 列出服务暴露的工具，随后立即关闭连接。
 * <p>面向管理页「测试连接」：既是手动重试入口（装配失败后无需等下一轮对话才重连），
 * 也让每个服务实际提供哪些工具可见。stdio 探测结束会关闭子进程，不留残留。</p>
 *
 * @author gogym
 */
public final class McpConnectionProbe {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionProbe.class);

    /** 探测单步（初始化 / 列工具）阻塞上限：配置里的 initializationTimeout 仍是第一道闸。 */
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(30);

    /** 工具信息：名称 + 描述（描述可空）。 */
    public record ToolInfo(String name, String description) {
    }

    /** 探测结果：成功与否；失败时 tools 为空、error 为原因。 */
    public record ProbeResult(boolean success, List<ToolInfo> tools, String error) {
    }

    private McpConnectionProbe() {
    }

    /**
     * 探测单个 MCP 服务（同步阻塞，调用方应在后台线程执行）。
     *
     * @param row mcp_servers 表记录
     * @return 探测结果；任何失败都折叠为 error 文本，不抛异常
     */
    public static ProbeResult probe(McpServerConfig row) {
        io.agentscope.harness.agent.tools.McpServerConfig cfg;
        try {
            cfg = McpServerConfigs.from(row);
        } catch (RuntimeException e) {
            return failure(e);
        }
        McpClientWrapper client = null;
        try {
            McpClientBuilder builder = McpClientBuilder.create(row.getServerCode());
            configureTransport(builder, cfg);
            if (cfg.getTimeout() != null) {
                builder.timeout(cfg.getTimeout());
            }
            if (cfg.getInitializationTimeout() != null) {
                builder.initializationTimeout(cfg.getInitializationTimeout());
            }
            client = builder.buildSync();
            client.initialize().block(BLOCK_TIMEOUT);
            List<io.modelcontextprotocol.spec.McpSchema.Tool> tools = client.listTools().block(BLOCK_TIMEOUT);
            List<ToolInfo> infos = tools == null ? List.of()
                    : tools.stream().map(t -> new ToolInfo(t.name(), t.description())).toList();
            return new ProbeResult(true, infos, null);
        } catch (RuntimeException e) {
            log.info("MCP 探测失败: {} -> {}", row.getServerCode(), e.getMessage());
            return failure(e);
        } finally {
            closeQuietly(client, row.getServerCode());
        }
    }

    /** transport 装配：与框架 {@code McpServerRegistrar.buildClient} 同口径（stdio/sse/http）。 */
    private static void configureTransport(
            McpClientBuilder builder, io.agentscope.harness.agent.tools.McpServerConfig cfg) {
        String transport = cfg.getTransport() == null ? "" : cfg.getTransport();
        switch (transport) {
            case "stdio" -> {
                if (cfg.getCommand() == null || cfg.getCommand().isBlank()) {
                    throw new IllegalArgumentException("stdio 服务缺少启动命令（command）");
                }
                Map<String, String> env = cfg.getEnv() == null ? Map.of() : new LinkedHashMap<>(cfg.getEnv());
                List<String> args = cfg.getArgs() == null ? List.of() : cfg.getArgs();
                builder.stdioTransport(cfg.getCommand(), args, env);
            }
            case "sse" -> {
                requireUrl(cfg, "sse");
                builder.sseTransport(cfg.getUrl());
                if (cfg.getHeaders() != null && !cfg.getHeaders().isEmpty()) {
                    builder.headers(cfg.getHeaders());
                }
            }
            case "http", "streamable-http", "streamablehttp" -> {
                requireUrl(cfg, transport);
                builder.streamableHttpTransport(cfg.getUrl());
                if (cfg.getHeaders() != null && !cfg.getHeaders().isEmpty()) {
                    builder.headers(cfg.getHeaders());
                }
            }
            default -> throw new IllegalArgumentException("不支持的传输类型：" + transport);
        }
    }

    private static void requireUrl(io.agentscope.harness.agent.tools.McpServerConfig cfg, String transport) {
        if (cfg.getUrl() == null || cfg.getUrl().isBlank()) {
            throw new IllegalArgumentException(transport + " 服务缺少服务地址（url）");
        }
    }

    private static void closeQuietly(McpClientWrapper client, String serverCode) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (RuntimeException e) {
            log.warn("关闭 MCP 探测连接失败: {}", serverCode, e);
        }
    }

    /** 从异常链取最有信息量的一句原因。 */
    private static ProbeResult failure(RuntimeException e) {
        Throwable t = e;
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isBlank())) {
            t = t.getCause();
        }
        String msg = t.getMessage() == null || t.getMessage().isBlank()
                ? t.getClass().getSimpleName() : t.getMessage();
        return new ProbeResult(false, List.of(), msg);
    }
}
