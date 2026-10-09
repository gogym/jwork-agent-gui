package com.gettyio.gim.agent.core.capability;

import com.gettyio.gim.agent.domain.entity.McpServerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpConnectionProbe} 单测：无效配置在翻译/装配阶段快速失败并折叠为结果，
 * 不可达的 HTTP 服务不抛异常、不长期阻塞。成功路径需真实 MCP 服务，此处不做。
 */
class McpConnectionProbeTest {

    private static McpServerConfig row(String code, String transport) {
        McpServerConfig m = new McpServerConfig();
        m.setServerCode(code);
        m.setTransportType(transport);
        m.setTimeout(3);
        return m;
    }

    @Test
    @DisplayName("未知传输类型：配置翻译阶段即失败")
    void unsupportedTransportFails() {
        McpConnectionProbe.ProbeResult r = McpConnectionProbe.probe(row("s1", "carrier-pigeon"));
        assertFalse(r.success());
        assertTrue(r.tools().isEmpty());
        assertNotNull(r.error());
        assertTrue(r.error().contains("传输类型"), "应给出可读原因: " + r.error());
    }

    @Test
    @DisplayName("stdio 缺启动命令：快速失败并给出原因")
    void stdioMissingCommandFails() {
        McpConnectionProbe.ProbeResult r = McpConnectionProbe.probe(row("s2", "STDIO"));
        assertFalse(r.success());
        assertTrue(r.tools().isEmpty());
        assertNotNull(r.error());
        assertTrue(r.error().contains("command"), "应给出可读原因: " + r.error());
    }

    @Test
    @DisplayName("http 服务不可达：失败结果带原因，不抛异常、不长期阻塞")
    void httpUnreachableFails() {
        McpServerConfig m = row("s3", "HTTP");
        m.setBaseUrl("http://127.0.0.1:1/mcp");
        long start = System.currentTimeMillis();
        McpConnectionProbe.ProbeResult r = McpConnectionProbe.probe(m);
        long elapsed = System.currentTimeMillis() - start;
        assertFalse(r.success());
        assertTrue(r.tools().isEmpty());
        assertNotNull(r.error());
        assertTrue(elapsed < 15_000, "连接被拒应快速返回，实际 " + elapsed + "ms");
    }
}
