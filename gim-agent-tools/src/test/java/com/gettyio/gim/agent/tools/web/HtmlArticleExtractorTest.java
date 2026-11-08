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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HtmlArticleExtractor} 正文抽取单测：用「导航 + 正文」并存的仿真实页面验证选层、噪声子树剔除、
 * 标题提取与实体解码，重点看「正文留下、菜单丢掉」而不是逐字符比对排版。
 *
 * @author gogym
 */
class HtmlArticleExtractorTest {

    /** 一段够长、能过 MIN_ARTICLE_CHARS 门槛的正文句子。 */
    private static String para(String text) {
        return "<p>" + text + "</p>";
    }

    /** 重复生成 n 段中文正文，保证选中层文字量压过导航。 */
    private static String article(int n, String topic) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            sb.append(para(topic + "第" + i + "条说明，这里是一段足够长的正文内容用于验证抽取效果。"));
        }
        return sb.toString();
    }

    private static final String NAV = """
            <ul class="menu">
              <li><a href="/1">首页</a></li>
              <li><a href="/2">产品中心</a></li>
              <li><a href="/3">解决方案</a></li>
            </ul>
            """;

    @Test
    @DisplayName("正文容器胜出：留下文章段落，丢掉导航菜单与页脚")
    void picksArticleOverNavigation() {
        String html = "<html><head><title>退款规则说明</title></head><body>"
                + "<nav>" + NAV + "</nav>"
                + "<div class=\"content\">" + article(8, "退款") + "</div>"
                + "<footer class=\"footer\">Copyright 2026 某某公司版权所有</footer>"
                + "</body></html>";
        HtmlArticleExtractor.Article article = HtmlArticleExtractor.extract(html);

        assertEquals("退款规则说明", article.title());
        assertTrue(article.text().contains("退款第1条说明"), article.text());
        assertTrue(article.text().contains("退款第8条说明"), article.text());
        assertFalse(article.text().contains("产品中心"), article.text());
        assertFalse(article.text().contains("Copyright"), article.text());
    }

    @Test
    @DisplayName("class/id 命中噪声词的子树被跳过，即使它没包在 nav 标签里")
    void junkClassSubtreeDropped() {
        String html = "<div id=\"app\"><div class=\"comment-list\"><p>这条评论全是灌水内容不需要出现在正文里哦哦哦</p></div>"
                + "<article>" + article(8, "统计口径") + "</article></div>";
        String text = HtmlArticleExtractor.extract(html).text();

        assertTrue(text.contains("统计口径第3条说明"), text);
        assertFalse(text.contains("灌水内容"), text);
    }

    @Test
    @DisplayName("标题行以 # 标记，层级随 h 级别变化")
    void headingsBecomeMarkdown() {
        String html = "<main><h2>结算规则</h2>" + article(6, "结算") + "</main>";
        String text = HtmlArticleExtractor.extract(html).text();

        assertTrue(text.contains("## 结算规则"), text);
    }

    @Test
    @DisplayName("无正文容器可选时回退整页文本，不返回空串骗模型说是死链")
    void fallsBackToWholePage() {
        String html = "<p>这是一段没有任何包裹结构的纯文本说明，重复很多次也要保证长度足够通过门槛。</p>"
                + "<p>这是一段没有任何包裹结构的纯文本说明，重复很多次也要保证长度足够通过门槛。</p>"
                + "<p>这是一段没有任何包裹结构的纯文本说明，重复很多次也要保证长度足够通过门槛。</p>";
        String text = HtmlArticleExtractor.extract(html).text();

        assertTrue(text.contains("纯文本说明"), text);
    }

    @Test
    @DisplayName("链接密度过高的容器不当正文：链接列表页胜过文字量也不选，回退整页")
    void linkFarmIsNotArticle() {
        StringBuilder links = new StringBuilder("<div class=\"list\">");
        for (int i = 1; i <= 30; i++) {
            links.append("<a href=\"/n/").append(i).append("\">推荐文章标题").append(i)
                    .append("，这一行长度足够让链接密度计算有意义。</a>");
        }
        links.append("</div>");
        String html = "<body>" + links + "<div class=\"tips\">小提示一句。</div></body>";
        String text = HtmlArticleExtractor.extract(html).text();

        // 选层停在链接堆上就会丢掉并列的 tips；能同时看到两者说明走的是整页文本兜底
        assertTrue(text.contains("推荐文章标题1"), text);
        assertTrue(text.contains("小提示一句"), text);
    }

    @Test
    @DisplayName("实体解码与乱标签容错：未闭合、属性里带尖括号都不抛异常")
    void toleratesMalformedMarkup() {
        String html = "<div class=a>x\"><p>正文里出现 &amp; 符号与 &#20013;文实体，"
                + "<span>嵌套但从不闭合</span>，后面还有一长串内容用来把字数抬过门槛。"
                + "再补一句让这段文本足够长一些。继续补一句保证长度。最后收尾一句。"
                + "</p></div>";
        String text = HtmlArticleExtractor.extract(html).text();

        assertTrue(text.contains("正文里出现 & 符号与 中文实体"), text);
    }

    @Test
    @DisplayName("空输入与无正文输入返回空文本，交给调用方判空")
    void blankInputYieldsEmptyArticle() {
        assertEquals("", HtmlArticleExtractor.extract(null).text());
        assertEquals("", HtmlArticleExtractor.extract("   ").text());
        assertEquals("", HtmlArticleExtractor.extract("<div><script>var a=1;</script></div>").text());
    }

    @Test
    @DisplayName("没有 <title> 时用 og:title 兜底")
    void ogTitleFallback() {
        String html = "<html><head><meta property=\"og:title\" content=\"支付订单差异分析\"></head><body>"
                + article(6, "差异") + "</body></html>";
        assertEquals("支付订单差异分析", HtmlArticleExtractor.extract(html).title());
    }

    @Test
    @DisplayName("表格内容按行输出，不被当成噪声丢掉")
    void keepsTableRows() {
        String html = "<article><h1>对账结果</h1>"
                + "<table><tr><td>订单号</td><td>金额</td></tr>"
                + "<tr><td>PO-2026-0001</td><td>128.50</td></tr>"
                + "<tr><td>PO-2026-0002</td><td>96.00</td></tr></table>"
                + article(6, "对账") + "</article>";
        String text = HtmlArticleExtractor.extract(html).text();

        assertTrue(text.contains("PO-2026-0001"), text);
        assertTrue(text.contains("128.50"), text);
    }
}
