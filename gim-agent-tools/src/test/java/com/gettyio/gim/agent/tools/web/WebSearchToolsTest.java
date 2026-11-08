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

package com.gettyio.gim.agent.tools.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebSearchTools} 守卫路径单测：验证「未配置（Key / 实例地址）/ 空入参 / 非法地址」时返回明确的本地化提示而非抛异常，
 * 且这些分支<b>完全不触网</b>（在发起 HTTP 前即返回）。真实联网结果格式化由集成/手工验收覆盖。
 *
 * @author gogym
 */
class WebSearchToolsTest {

    private static WebSearchTools withKey(Supplier<String> key) {
        return new WebSearchTools(() -> WebSearchTools.PROVIDER_TAVILY, key, () -> "");
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
    @DisplayName("web_search：博查未配置 Key 返回配置引导")
    void bochaMissingKeyGuidesToConfig() {
        WebSearchTools tools = new WebSearchTools(() -> WebSearchTools.PROVIDER_BOCHA, () -> null, () -> "");
        assertTrue(tools.webSearch("天气", null).contains("未配置"));
    }

    @Test
    @DisplayName("web_search：SearXNG 未配置实例地址返回配置引导，地址缺协议头给出明确提示")
    void searxngGuardsInstanceAddress() {
        WebSearchTools missing = new WebSearchTools(() -> WebSearchTools.PROVIDER_SEARXNG, () -> "", () -> "   ");
        assertTrue(missing.webSearch("天气", null).contains("未配置"));
        WebSearchTools badScheme = new WebSearchTools(() -> WebSearchTools.PROVIDER_SEARXNG, () -> "",
                () -> "searx.example.com");
        assertTrue(badScheme.webSearch("天气", null).contains("http"));
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
    @DisplayName("web_search：提供方读取抛异常时回退默认分支，不向外抛")
    void providerSupplierFailureFallsBackToDefault() {
        Supplier<String> boom = () -> {
            throw new IllegalStateException("设置读取失败");
        };
        WebSearchTools tools = new WebSearchTools(boom, () -> "", () -> "");
        assertTrue(tools.webSearch("天气", null).contains("未配置"));
    }

    @Test
    @DisplayName("web_fetch：空 url 拒绝，非 http/https 明确提示")
    void fetchGuardsUrl() {
        assertTrue(withKey(() -> "sk").webFetch("", null, null).contains("不能为空"));
        assertTrue(withKey(() -> "sk").webFetch(null, null, null).contains("不能为空"));
        assertTrue(withKey(() -> "sk").webFetch("ftp://example.com/x", null, null).contains("仅支持"));
    }
}
