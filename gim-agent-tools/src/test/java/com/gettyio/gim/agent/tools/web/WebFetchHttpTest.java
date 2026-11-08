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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code web_fetch} 对本地 {@link HttpServer} 的端到端单测：重定向后地址、GBK/meta 编码识别、
 * 正文抽取（导航页脚不进结果）、prompt 选段、max_chars 截断与非网页类型，全部不依赖外网。
 *
 * @author gogym
 */
class WebFetchHttpTest {

    private static final Charset GBK = Charset.forName("GBK");

    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/article", ex -> reply(ex, 200, "text/html; charset=utf-8", null,
                ARTICLE_PAGE.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/old", ex -> {
            String location = "http://" + ex.getRequestHeaders().getFirst("Host") + "/article";
            reply(ex, 302, "text/html; charset=utf-8", location, new byte[0]);
        });
        server.createContext("/stale", ex -> reply(ex, 300, "text/html; charset=utf-8", "/article",
                "<html>跳转提示</html>".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/gbk", ex -> reply(ex, 200, "text/html; charset=GBK", null,
                page("GBK中文标题", "这里是GBK编码的中文正文内容用于验证解码不会乱码出现。")
                        .getBytes(GBK)));
        server.createContext("/meta", ex -> reply(ex, 200, "text/html", null,
                ("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=gb2312\">"
                        + "<title>meta声明的中文标题</title></head><body>"
                        + paras("没有响应头charset时靠meta声明解码同样不能乱码")
                        + "</body></html>").getBytes(GBK)));
        server.createContext("/empty", ex -> reply(ex, 200, "text/html; charset=utf-8", null,
                "<html><body><script>var a=1;</script><div><span></span></div></body></html>"
                        .getBytes(StandardCharsets.UTF_8)));
        server.createContext("/json", ex -> reply(ex, 200, "application/json", null,
                "{\"total\":128,\"diff\":3}".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/binary", ex -> reply(ex, 200, "application/octet-stream", null,
                "whatever".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/missing", ex -> reply(ex, 404, "text/html; charset=utf-8", null,
                "<html><body>not found</body></html>".getBytes(StandardCharsets.UTF_8)));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 写响应：状态、Content-Type、可选 Location、正文。 */
    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String contentType,
                              String location, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        if (location != null) {
            ex.getResponseHeaders().add("Location", location);
        }
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
        ex.close();
    }

    /** 4 段正文，长度够过正文抽取的选层门槛。 */
    private static String paras(String lead) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            sb.append("<p>").append(lead).append("，这是用于验证解码与抽取效果的第").append(i)
                    .append("段中文内容。</p>");
        }
        return sb.toString();
    }

    /** 一个标题 + 4 段正文的极简页面。 */
    private static String page(String title, String lead) {
        return "<html><head><title>" + title + "</title></head><body>" + paras(lead) + "</body></html>";
    }

    /** 导航/页脚 + 八段正文，其中第三段带 prompt 想找的独有内容。 */
    private static final String ARTICLE_PAGE = """
            <html><head><title>支付平台退款统计</title></head><body>
            <nav><ul class="menu"><li><a href="/">首页</a></li><li><a href="/p">产品中心</a></li></ul></nav>
            <div class="article">
            <p>退款订单差异分析第一节：以支付平台回执为准统计订单条数，不看退款发起侧的记录。</p>
            <p>第二节：两边订单条数差值超过阈值时需要人工复核后出报表，复核结论写进备注列。</p>
            <p>第三节：用券订单的优惠券抵扣部分不计入实际退款金额，按平台回执的净额口径处理。</p>
            <p>第四节：跨月订单归入支付发生当月，不按退款发起时间统计，避免同月重复计入。</p>
            <p>第五节：接口超时未回执的订单在次日重查，仍缺失则单列一张待补清单交给运营。</p>
            <p>第六节：报表合计行与明细行差值应为零，否则视为口径不一致，需回到源表核对。</p>
            <p>第七节：手工补录的订单单独标记来源，统计时与系统回执区分，避免重复计算。</p>
            <p>第八节：最终报表按支付渠道分列汇总，合计行给出金额与条数两项核对指标。</p>
            </div>
            <footer>版权所有 2026 某某科技</footer>
            </body></html>
            """;

    private static WebSearchTools tools() {
        return new WebSearchTools(() -> WebSearchTools.PROVIDER_TAVILY, () -> "", () -> "");
    }

    @Test
    @DisplayName("抽取正文：给出标题、段落保留，导航与页脚不进上下文")
    void extractsArticleAndDropsChrome() {
        String out = tools().webFetch(base + "/article", null, null);

        assertTrue(out.contains("【网页正文】支付平台退款统计"), out);
        assertTrue(out.contains("地址：" + base + "/article"), out);
        assertTrue(out.contains("第三节：用券订单的优惠券抵扣部分不计入实际退款金额"), out);
        assertTrue(out.contains("第六节"), out);
        assertFalse(out.contains("产品中心"), out);
        assertFalse(out.contains("版权所有"), out);
    }

    @Test
    @DisplayName("跟随重定向后如实给出最终地址，并保留原地址")
    void reportsFinalUrlAfterRedirect() {
        String out = tools().webFetch(base + "/old", null, null);

        assertTrue(out.contains(base + "/article"), out);
        assertTrue(out.contains("（从 " + base + "/old 跳转）"), out);
        assertTrue(out.contains("第一节"), out);
    }

    @Test
    @DisplayName("客户端不自动跟随的重定向给出 Location，不谎称抓到了正文")
    void unresolvedRedirectGivesLocation() {
        String out = tools().webFetch(base + "/stale", null, null);

        assertTrue(out.contains("未跟随的重定向（HTTP 300）"), out);
        assertTrue(out.contains("/article"), out);
    }

    @Test
    @DisplayName("GBK 站点按响应头 charset 解码，不乱码")
    void honorsHeaderCharset() {
        String out = tools().webFetch(base + "/gbk", null, null);

        assertTrue(out.contains("【网页正文】GBK中文标题"), out);
        assertTrue(out.contains("GBK编码的中文正文内容用于验证解码不会乱码出现"), out);
    }

    @Test
    @DisplayName("响应头没写 charset 时回看 HTML 里的 meta 声明")
    void honorsMetaCharset() {
        String out = tools().webFetch(base + "/meta", null, null);

        assertTrue(out.contains("meta声明的中文标题"), out);
        assertTrue(out.contains("没有响应头charset时靠meta声明解码同样不能乱码"), out);
    }

    @Test
    @DisplayName("prompt 只回相关段落，并注明取回几段")
    void promptSelectsPassage() {
        String out = tools().webFetch(base + "/article", "优惠券抵扣", null);

        assertTrue(out.contains("与「优惠券抵扣」相关的段落"), out);
        assertTrue(out.contains("取回 1 段"), out);
        assertTrue(out.contains("第三节"), out);
        assertFalse(out.contains("第六节"), out);
    }

    @Test
    @DisplayName("prompt 没命中时降级为正文开头并说明原因")
    void promptMissFallsBackToHead() {
        String out = tools().webFetch(base + "/article", "服务器机房温度", null);

        assertTrue(out.contains("没有与该 prompt 直接相关的段落"), out);
        assertTrue(out.contains("第一节"), out);
    }

    @Test
    @DisplayName("max_chars 生效并提示可以带 prompt 或调大上限")
    void truncatesWithHint() {
        String out = tools().webFetch(base + "/article", null, 50);

        assertTrue(out.contains("已截断到 50"), out);
        assertTrue(out.contains("想看特定内容请带 prompt"), out);
    }

    @Test
    @DisplayName("JSON 原样给出，二进制等非网页类型说明处理方式")
    void nonHtmlContentTypes() {
        assertTrue(tools().webFetch(base + "/json", null, null).contains("\"diff\":3"));
        assertTrue(tools().webFetch(base + "/binary", null, null).contains("不是网页正文"));
    }

    @Test
    @DisplayName("页面只剩脚本与空容器时如实说没正文，不回空串让模型当成死链")
    void emptyArticleIsReported() {
        assertTrue(tools().webFetch(base + "/empty", null, null).contains("没有可抽取的正文"));
    }

    @Test
    @DisplayName("4xx 带状态码与地址返回")
    void httpErrorIsReported() {
        String out = tools().webFetch(base + "/missing", null, null);

        assertTrue(out.contains("抓取失败：HTTP 404"), out);
        assertTrue(out.contains("/missing"), out);
    }
}
