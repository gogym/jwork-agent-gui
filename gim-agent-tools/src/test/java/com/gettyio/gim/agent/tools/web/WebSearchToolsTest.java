package com.gettyio.gim.agent.tools.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebSearchTools} 守卫路径单测：验证「未配置 Key / 空入参 / 非法地址」时返回明确的本地化提示而非抛异常，
 * 且这些分支<b>完全不触网</b>（在发起 HTTP 前即返回）。真实联网结果格式化由集成/手工验收覆盖。
 */
class WebSearchToolsTest {

    private static WebSearchTools withKey(Supplier<String> key) {
        return new WebSearchTools(key);
    }

    @Test
    @DisplayName("web_search：空 query 先行拒绝，不读 Key 也不联网")
    void blankQueryRejected() {
        assertTrue(withKey(() -> "sk-live").webSearch("   ", null).contains("不能为空"));
        assertTrue(withKey(() -> "sk-live").webSearch(null, null).contains("不能为空"));
    }

    @Test
    @DisplayName("web_search：未配置 Key（null/空白）返回配置引导")
    void missingKeyGuidesToConfig() {
        assertTrue(withKey(() -> null).webSearch("天气", null).contains("未配置"));
        assertTrue(withKey(() -> "   ").webSearch("天气", null).contains("未配置"));
    }

    @Test
    @DisplayName("web_search：Key 供给抛异常时按未配置处理，不向外抛")
    void keySupplierFailureTreatedAsUnconfigured() {
        Supplier<String> boom = () -> {
            throw new IllegalStateException("解密失败");
        };
        assertTrue(withKey(boom).webSearch("天气", null).contains("未配置"));
    }

    @Test
    @DisplayName("web_fetch：空 url 拒绝，非 http/https 明确提示")
    void fetchGuardsUrl() {
        assertTrue(withKey(() -> "sk").webFetch("").contains("不能为空"));
        assertTrue(withKey(() -> "sk").webFetch(null).contains("不能为空"));
        assertTrue(withKey(() -> "sk").webFetch("ftp://example.com/x").contains("仅支持"));
    }
}
