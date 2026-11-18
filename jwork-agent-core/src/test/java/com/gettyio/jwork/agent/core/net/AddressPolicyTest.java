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

package com.gettyio.jwork.agent.core.net;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AddressPolicy} 单测：协议门槛、内网/保留地址拦截（含 IPv6 与映射写法）、
 * 公网地址放行与「允许访问内网」开关。
 * <p>用例只用 IP 字面量与 {@code localhost}，不做外网 DNS 解析——CI 断网时结果不该变。</p>
 *
 * @author gogym
 */
class AddressPolicyTest {

    @BeforeEach
    void defaultIsDeny() {
        AddressPolicy.configureAllowPrivate(null);
    }

    private static void assertBlocked(String url) {
        assertTrue(AddressPolicy.refusal(url).isPresent(), "应当拦住: " + url);
    }

    private static void assertAllowed(String url) {
        assertTrue(AddressPolicy.refusal(url).isEmpty(), "应当放开: " + url);
    }

    @Test
    @DisplayName("非 http(s) 协议一律拒绝")
    void nonHttpSchemeRefused() {
        // Arrays.asList 而非 List.of：null 入参也要挡住，List.of 不接受 null
        for (String url : Arrays.asList("file:///C:/Windows/win.ini", "javascript:alert(1)",
                "data:text/plain,hi", "mailto:a@b.com", "gopher://127.0.0.1:11211/xSTATS",
                "ftp://10.0.0.1/a", "http://", "https://", "", "   ", "no-scheme.example.com/a",
                null)) {
            assertBlocked(url);
        }
    }

    @Test
    @DisplayName("回环、元数据与局域网段拦下：桌面端不该被当成内网跳板")
    void internalRangesBlocked() {
        for (String url : List.of(
                "http://127.0.0.1:11434/api/tags",
                "http://localhost:8080/admin",
                "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
                "http://10.1.2.3/",
                "http://192.168.0.1/",
                "http://172.16.5.5/",
                "http://0.0.0.0/",
                "http://0.1.2.3/",
                "http://100.64.0.1/",
                "http://100.127.255.255/",
                "http://198.18.0.1/",
                "http://224.0.0.1/",
                "http://[::1]/",
                "http://[fd00::11]/",
                "http://[fe80::1]/",
                "http://[::ffff:127.0.0.1]/",
                "http://[::ffff:7f00:1]/",
                "http://[::7f00:1]/",
                "http://[::a00:1]/",
                "http://2130706433/")) {
            assertBlocked(url);
        }
    }

    @Test
    @DisplayName("公网地址与站本地段边界外放行：不能顺手把正常网页也拦了")
    void publicAddressesAllowed() {
        for (String url : List.of(
                "http://8.8.8.8/",
                "https://1.1.1.1/",
                "http://172.32.0.1/",
                "http://100.128.0.1/",
                "http://169.253.0.1/",
                "http://203.0.113.9/")) {
            assertAllowed(url);
        }
    }

    @Test
    @DisplayName("拒绝理由里带上命中地址，用户才知道被拦在哪")
    void reasonNamesTheAddress() {
        String reason = AddressPolicy.refusal("http://169.254.169.254/latest/meta-data/").orElse("");
        assertTrue(reason.contains("169.254.169.254"), reason);
        // 理由是「内网地址」而非「解析失败」，说明嵌入式 IPv4 写法真被认出来了，不是靠兜底挡下
        String mapped = AddressPolicy.refusal("http://[::7f00:1]/").orElse("");
        assertTrue(mapped.contains("127.0.0.1"), mapped);
    }

    @Test
    @DisplayName("放开内网开关后不再判地址，但协议门槛仍然有效")
    void allowPrivateSkipsAddressChecks() {
        AddressPolicy.configureAllowPrivate(() -> true);
        assertTrue(AddressPolicy.allowPrivateAddresses());
        assertAllowed("http://127.0.0.1:11434/api/tags");
        assertAllowed("http://192.168.1.1/");
        assertBlocked("file:///C:/Windows/win.ini");
        assertBlocked("http://");

        AddressPolicy.configureAllowPrivate(null);
        assertFalse(AddressPolicy.allowPrivateAddresses(), "开关恢复默认拒绝");
        assertBlocked("http://127.0.0.1/");
    }

    @Test
    @DisplayName("开关读到的值实时生效：设置页改一次即变，不需重启")
    void allowPrivateSupplierIsReadLive() {
        boolean[] on = {false};
        AddressPolicy.configureAllowPrivate(() -> on[0]);
        assertBlocked("http://10.0.0.1/");
        on[0] = true;
        assertAllowed("http://10.0.0.1/");
    }
}
