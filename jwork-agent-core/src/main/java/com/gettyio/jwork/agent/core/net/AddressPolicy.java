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

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * 出网地址策略：模型写出来的 URL 不该把用户的笔记本变成访问内网的跳板。
 *
 * <p>桌面端跑在用户的办公网里，{@code web_fetch} 之类的工具一旦放开任意地址，模型（或被注入的
 * 网页正文、搜索结果标题）就能让本机去请求 {@code http://169.254.169.254/}（云主机/元数据服务）、
 * {@code http://127.0.0.1:11434/}（本机 Ollama）、路由器后台、内网 Confluence 之类，把响应正文
 * 带回上下文。这就是 SSRF，而且这里连「服务器」都不是——是用户自己的机器。</p>
 *
 * <p>判定按<b>解析后的地址</b>而非主机名字符串：{@code http://2130706433/}（十进制）、
 * {@code http://[::ffff:127.0.0.1]/}、{@code http://[::7f00:1]/} 都落在回环，字符串黑名单挡不住这些写法，
 * 要先经 {@link InetAddress} 归一再判。更冷的十六进制写法（如 {@code 0x7f.1}）JDK 不认，
 * 会走 DNS 解析失败——同样拒掉，那是兜底而不是识别。</p>
 *
 * <p>取舍：</p>
 * <ul>
 *   <li><b>重定向必须由调用方逐跳复检。</b>JDK 客户端透明跟随跳转时，策略只看得见第一跳，
 *    「公网入口 → 302 到内网」是最常见的绕法；{@link #refusal(String)} 因此被设计成可反复调用的纯函数，
 *    {@code web_fetch} 关掉自动跳转、自己按跳数循环校验。</li>
 *   <li><b>不解决 DNS 重绑定。</b>JDK 21 的 {@code HttpClient} 没有把「已校验的 IP」钉在连接上的公开
 *    API（要钉得住得自定义 {@code SocketFactory} 或 {@code Connector}，都是非公开类型）。
 *    TTL=0 的域名在校验后重新解析到内网仍能漏过——本机跑的是用户自己的网络，这一条留给
 *    「开关默认关 + 逐跳校验」把概率压到很低，不做半成品隧道劫持。</li>
 *   <li><b>不覆盖 6to4/Teredo 之类过渡地址。</b>它们需要本机确有相应中继才有意义，
 *    真要防得靠网络层，客户端字符串判定挡不住。</li>
 *   <li><b>用户自己配置的地址（模型服务、SearXNG 实例、WebDAV、知识库 URL 导入）不走这里。</b>
 *    那些是用户显式填进来的自建服务，常常就在局域网里；本策略只管<b>模型写出的、或服务端响应里
 *    带回的地址</b>（{@code web_fetch}、生成图下载地址、更新包与技能包下载地址）。</li>
 * </ul>
 *
 * @author gogym
 */
public final class AddressPolicy {

    /** 放行内网/回环地址的开关（由装配方注入设置项）；未装配时按最保守的「拒绝」处理。 */
    private static volatile BooleanSupplier allowPrivate = () -> false;

    private AddressPolicy() {
    }

    /** 注入开关快照（实时读取，改设置不必重启）。传 {@code null} 恢复默认拒绝。 */
    public static void configureAllowPrivate(BooleanSupplier supplier) {
        allowPrivate = supplier == null ? () -> false : supplier;
    }

    /** 当前是否放开内网地址（供提示文案与日志说明用）。 */
    public static boolean allowPrivateAddresses() {
        return allowPrivate.getAsBoolean();
    }

    /**
     * 这个地址能不能访问。
     *
     * @return 放行返回空；否则返回可直接展示给用户的拒绝理由
     */
    public static Optional<String> refusal(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl == null ? "" : rawUrl.trim());
        } catch (RuntimeException e) {
            return Optional.of("地址无法解析: " + rawUrl);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return Optional.of("仅支持 http/https 地址，当前为 " + (scheme.isBlank() ? "（缺协议）" : scheme));
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Optional.of("地址里没有主机名: " + rawUrl);
        }
        if (allowPrivate.getAsBoolean()) {
            // 用户明确放开：仍要求 http(s) 与有主机名，只是不再判地址归属
            return Optional.empty();
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return Optional.of("主机名解析失败: " + host);
        }
        for (InetAddress address : addresses) {
            InetAddress probe = unwrapEmbeddedIpv4(address);
            if (isPrivateOrReserved(probe)) {
                return Optional.of("目标是内网/保留地址 " + probe.getHostAddress() + "（主机 " + host
                        + "）；确需访问本机或局域网服务，请在「设置 > 网络」放开「允许访问内网地址」");
            }
        }
        return Optional.empty();
    }

    /**
     * 内嵌 IPv4 的 IPv6 写法还原成 IPv4 再判：映射 {@code ::ffff:a.b.c.d} 与兼容 {@code ::a.b.c.d}。
     *
     * <p>字面量写法下 JDK 已把映射形态归一成 {@code Inet4Address}，DNS 返回的 AAAA 记录却未必；
     * 兼容形态它一律原样给出 {@code Inet6Address}，而 {@code isLoopbackAddress()} 对
     * {@code ::7f00:1} 返回 {@code false}——不还原就等于放行了回环。</p>
     *
     * <p>副作用可接受：{@code ::} 还原成 0.0.0.0（仍按未指定地址拦），{@code ::1} 还原成 0.0.0.1
     * （落在 0/8 拦），判定与还原前一致。</p>
     */
    private static InetAddress unwrapEmbeddedIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 16) {
            return address;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return address;
            }
        }
        int marker = bytes[10] & 0xFF;
        if ((marker != 0x00 && marker != 0xFF) || (bytes[11] & 0xFF) != marker) {
            return address;
        }
        try {
            return InetAddress.getByAddress(new byte[]{ bytes[12], bytes[13], bytes[14], bytes[15] });
        } catch (UnknownHostException e) {
            return address;
        }
    }

    /** 回环、链路本地（含元数据服务 169.254.169.254）、站点本地、组播、未指定，以及若干保留段。 */
    private static boolean isPrivateOrReserved(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes == null || bytes.length == 0) {
            return true; // 读不出字节形态时按不可达处理：这一层宁可少访一个地址
        }
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            return first == 0                      // 0.0.0.0/8「本机此网络」
                    || (first == 100 && second >= 64 && second <= 127)  // 100.64.0.0/10 CGNAT
                    || (first == 198 && (second == 18 || second == 19)) // 198.18.0.0/15 设备基准测试
                    || (first == 192 && second == 0 && (bytes[2] & 0xFF) == 0); // 192.0.0.0/24 IETF 保留
        }
        if (bytes.length == 16) {
            int first = bytes[0] & 0xFF;
            return (first & 0xFE) == 0xFC; // fc00::/7 唯一本地地址（ULA）
        }
        return true;
    }
}
