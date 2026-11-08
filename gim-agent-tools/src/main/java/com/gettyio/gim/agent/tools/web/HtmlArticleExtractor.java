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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置网页正文抽取（readability 档，纯 JDK、不引第三方依赖）。
 *
 * <p>为什么不能只是「剥标签」：真实页面六成文字是导航、侧栏、评论、相关推荐，正则剥完标签得到
 * 一大坨菜单，正文被埋在中间；一次抓取塞几万字符进上下文，模型还得自己筛。这里按 readability 的
 * 思路走三步：丢掉结构性噪声标签和 class/id 命中噪声词的子树；把剩下的建成轻量树，每个容器累计
 * 自己子树的文字量、段落数与链接密度；从根往下走「哪个子节点占了半数以上文字就进它」，直到没有
 * 子节点占多数——那层正是正文与兄弟段落并列的地方。</p>
 *
 * <p>解析是有意做浅的：只认标签名与 class/id/content 属性，容错优先（未闭合、大小写混排、
 * 属性里带尖括号都不抛异常）。误判也不会丢内容：噪声标记只影响「选哪一层」，父层照样累计到全部
 * 文字，抽不出正文时回退整页文本——返回空串会让模型以为链接是死链，转而反复抓取。</p>
 *
 * @author gogym
 */
public final class HtmlArticleExtractor {

    /** 正文抽取结果：标题（{@code <title>} 或 og:title）与按块分行的正文。 */
    public record Article(String title, String text) {
    }

    /** 整块跳过的标签：非正文内容或纯装饰。 */
    private static final Set<String> DROP_TAGS = Set.of(
            "script", "style", "noscript", "template", "svg", "iframe", "form",
            "nav", "header", "footer", "aside", "button", "select");

    /** 会产生块边界（换行）的标签。 */
    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "div", "li", "ul", "ol", "dl", "dt", "dd", "article", "section", "main",
            "blockquote", "pre", "table", "tbody", "thead", "tr", "td", "th", "caption",
            "h1", "h2", "h3", "h4", "h5", "h6", "figure", "figcaption", "br", "hr",
            "address", "details", "summary", "body");

    /** 计入「段落数」的标签：正文密度的信号。 */
    private static final Set<String> PARAGRAPH_TAGS = Set.of(
            "p", "li", "dd", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6",
            "td", "figcaption", "summary");

    /** 不入栈的标签。 */
    private static final Set<String> VOID_TAGS = Set.of(
            "br", "hr", "img", "meta", "link", "input", "source", "track", "wbr",
            "area", "base", "col", "embed", "param");

    private static final Set<String> HEADING_TAGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");

    /** class/id 命中这些词就认为该子树不是正文：中文站也普遍沿用这套英文类名。 */
    private static final List<String> JUNK_HINTS = List.of(
            "nav", "menu", "sidebar", "breadcrumb", "crumb", "footer", "masthead",
            "comment", "discuss", "reply-list", "share", "social", "subscribe", "newsletter",
            "advert", "promo", "related", "recommend", "pagination", "pager", "cookie",
            "copyright", "toolbar", "search-result", "login-form", "language", "banner",
            "popup", "modal");

    /** 选中层至少这么多字符，否则回退整页：太深的单子节点往往只是一个小标题。 */
    private static final int MIN_ARTICLE_CHARS = 200;

    /** 走树的最大层数：防御畸形页面里的超深嵌套。 */
    private static final int MAX_DESCEND_DEPTH = 40;

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9a-fA-F]+);");

    private HtmlArticleExtractor() {
    }

    /** 一个元素节点：{@code text}/{@code linkChars}/{@code paragraphs} 累计整棵子树。 */
    private static final class Node {
        private final String tag;
        private final List<Node> children = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final boolean junk;
        private int paragraphs;
        private int linkChars;

        private Node(String tag, boolean junk) {
            this.tag = tag;
            this.junk = junk;
        }

        private int chars() {
            return text.length();
        }

        private double linkDensity() {
            int len = text.length();
            return len == 0 ? 0 : (double) linkChars / len;
        }
    }

    /**
     * 抽取正文。
     *
     * @param html 原始 HTML
     * @return 标题与正文；输入空白时返回空 Article，由调用方按「无正文」处理
     */
    public static Article extract(String html) {
        if (html == null || html.isBlank()) {
            return new Article("", "");
        }
        String body = stripComments(html);
        String lower = body.toLowerCase(Locale.ROOT);
        String title = scanTitle(body, lower);
        Node root = buildTree(body, lower);
        Node winner = descendToArticle(root);
        String text = normalize(winner == null ? root.text.toString() : winner.text.toString());
        if (text.length() < MIN_ARTICLE_CHARS) {
            String whole = normalize(root.text.toString());
            if (whole.length() > text.length()) {
                text = whole;
            }
        }
        return new Article(title, text);
    }

    // ===================== 建树 =====================

    /** 单遍扫描建轻量树：文本写进当前容器并沿祖先链累计，祖先因此看得见整棵子树的文字量。 */
    private static Node buildTree(String body, String lower) {
        Node root = new Node("body", false);
        List<Node> path = new ArrayList<>();
        path.add(root);
        String skipping = null;
        int linkDepth = 0;
        int pos = 0;
        while (pos < body.length()) {
            int lt = lower.indexOf('<', pos);
            if (lt < 0) {
                if (skipping == null) {
                    addText(path, body.substring(pos), linkDepth > 0);
                }
                break;
            }
            if (lt > pos && skipping == null) {
                // 丢弃区内的文本直接跳过：script/style 里的字符串字面量不是正文
                addText(path, body.substring(pos, lt), linkDepth > 0);
            }
            int gt = closingBracket(lower, lt);
            if (gt < 0) {
                // 畸形尾（有 < 没 >）：剩下全当文本，顺带避免死循环
                if (skipping == null) {
                    addText(path, body.substring(lt), linkDepth > 0);
                }
                break;
            }
            String tag = body.substring(lt + 1, gt).trim();
            pos = gt + 1;
            if (tag.isEmpty()) {
                continue;
            }
            String name = tagName(tag);
            if (skipping != null) {
                if (tag.charAt(0) == '/' && name.equals(skipping)) {
                    skipping = null;
                }
                continue;
            }
            if (name.isEmpty()) {
                continue;
            }
            if (tag.charAt(0) != '/' && DROP_TAGS.contains(name)) {
                skipping = name;
                continue;
            }
            if ("a".equals(name)) {
                linkDepth = tag.charAt(0) == '/' ? Math.max(0, linkDepth - 1) : linkDepth + 1;
                continue;
            }
            if (tag.charAt(0) == '/') {
                if (PARAGRAPH_TAGS.contains(name)) {
                    for (int i = path.size() - 1; i >= 0; i--) {
                        Node n = path.get(i);
                        if (!n.junk) {
                            n.paragraphs++;
                        }
                    }
                }
                popTo(path, name);
                continue;
            }
            if (VOID_TAGS.contains(name) || tag.endsWith("/")) {
                if ("br".equals(name) || "hr".equals(name)) {
                    appendAll(path, "\n");
                }
                continue;
            }
            boolean block = BLOCK_TAGS.contains(name);
            push(path, name, junkAttributes(tag));
            if (block) {
                appendAll(path, "\n");
                if (HEADING_TAGS.contains(name)) {
                    // 标题留个 Markdown 记号，模型据此分节；正文里不会再出现这种前缀
                    appendAll(path, "#".repeat(name.charAt(1) - '0') + " ");
                }
            }
        }
        return root;
    }

    private static void push(List<Node> path, String name, boolean junk) {
        Node parent = path.get(path.size() - 1);
        Node node = new Node(name, junk || parent.junk);
        parent.children.add(node);
        path.add(node);
    }

    /** 弹到 name 所在层：未闭合标签一并收口，不抛异常。 */
    private static void popTo(List<Node> path, String name) {
        for (int i = path.size() - 1; i > 0; i--) {
            if (path.get(i).tag.equals(name)) {
                while (path.size() > i) {
                    path.remove(path.size() - 1);
                }
                return;
            }
        }
    }

    private static void addText(List<Node> path, String raw, boolean inLink) {
        String text = decodeEntities(raw).replaceAll("\\s+", " ");
        if (text.isBlank()) {
            return;
        }
        if (!text.endsWith(" ")) {
            text = text + " ";
        }
        for (int i = path.size() - 1; i >= 0; i--) {
            Node n = path.get(i);
            if (n.junk) {
                continue;
            }
            n.text.append(text);
            if (inLink) {
                n.linkChars += text.length();
            }
        }
    }

    private static void appendAll(List<Node> path, String marker) {
        for (int i = path.size() - 1; i >= 0; i--) {
            Node n = path.get(i);
            if (!n.junk) {
                n.text.append(marker);
            }
        }
    }

    // ===================== 选正文容器 =====================

    /**
     * 从根往下走：占父节点半数以上文字、且不是链接堆的子节点就是正文所在；没有这样的子节点时停下，
     * 此时当前层正是「正文与兄弟段落并列」的容器。
     *
     * @return 正文容器；根节点本身就是最紧的一层时返回 {@code null}（调用方直接用整页文本）
     */
    private static Node descendToArticle(Node root) {
        Node current = root;
        for (int guard = 0; guard < MAX_DESCEND_DEPTH; guard++) {
            Node best = null;
            int bestChars = 0;
            for (Node child : current.children) {
                if (!child.junk && child.chars() > bestChars) {
                    bestChars = child.chars();
                    best = child;
                }
            }
            if (best == null || bestChars * 2 < current.chars()) {
                break;
            }
            if (best.linkDensity() > 0.7) {
                break;
            }
            current = best;
        }
        return current == root ? null : current;
    }

    /** 规整输出：块分行、去掉零碎短行与纯符号行、压缩连续空行。 */
    private static String normalize(String text) {
        String[] lines = text.replace('\t', ' ').split("\n");
        StringBuilder sb = new StringBuilder();
        int blank = 0;
        for (String line : lines) {
            String one = line.replaceAll(" {2,}", " ").trim();
            if (one.isEmpty()) {
                if (++blank >= 2 || sb.isEmpty()) {
                    continue;
                }
                sb.append('\n');
                continue;
            }
            blank = 0;
            if (one.replaceAll("[^\\p{L}\\p{N}]", "").isEmpty()) {
                continue;
            }
            if (one.length() < 3 && !one.startsWith("#")) {
                continue;
            }
            sb.append(one).append('\n');
        }
        return sb.toString().trim();
    }

    // ===================== 零碎工具 =====================

    private static String stripComments(String html) {
        return html.replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?is)<!doctype[^>]*>", " ")
                .replaceAll("(?is)<\\?.*?\\?>", " ");
    }

    /** 从 {@code lt} 处找标签结尾的尖括号：跳过引号内部，属性值里的 {@code >} 不算结束。 */
    private static int closingBracket(String lower, int lt) {
        char quote = 0;
        for (int i = lt + 1; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private static String tagName(String tag) {
        int i = tag.charAt(0) == '/' ? 1 : 0;
        int start = i;
        while (i < tag.length() && Character.isLetterOrDigit(tag.charAt(i))) {
            i++;
        }
        return tag.substring(start, i).toLowerCase(Locale.ROOT);
    }

    /** class/id 命中噪声词：整棵子树按非正文处理（只影响选层，不丢内容）。 */
    private static boolean junkAttributes(String tag) {
        return junkValue(attribute(tag, "class")) || junkValue(attribute(tag, "id"));
    }

    private static boolean junkValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        for (String hint : JUNK_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static String scanTitle(String body, String lower) {
        int t = lower.indexOf("<title");
        if (t >= 0) {
            int gt = closingBracket(lower, t);
            int close = gt < 0 ? -1 : lower.indexOf("</title>", gt);
            if (close > gt) {
                String title = decodeEntities(body.substring(gt + 1, close)).replaceAll("\\s+", " ").trim();
                if (!title.isEmpty()) {
                    return title;
                }
            }
        }
        int og = lower.indexOf("og:title");
        if (og > 0) {
            int tagStart = lower.lastIndexOf('<', og);
            int tagEnd = tagStart < 0 ? -1 : closingBracket(lower, tagStart);
            if (tagEnd > og) {
                String content = attribute(body.substring(tagStart, tagEnd + 1), "content");
                if (content != null && !content.isBlank()) {
                    return decodeEntities(content).replaceAll("\\s+", " ").trim();
                }
            }
        }
        return "";
    }

    /** 取属性值（引号或裸值都认）：属性名大小写不敏感，值按原样返回。 */
    private static String attribute(String tag, String name) {
        int at = tag.toLowerCase(Locale.ROOT).indexOf(name + "=");
        if (at < 0) {
            return null;
        }
        int value = at + name.length() + 1;
        if (value >= tag.length()) {
            return null;
        }
        char quote = tag.charAt(value);
        if (quote == '"' || quote == '\'') {
            int close = tag.indexOf(quote, value + 1);
            return close > value ? tag.substring(value + 1, close) : null;
        }
        int space = tag.indexOf(' ', value);
        return space > value ? tag.substring(value, space) : tag.substring(value);
    }

    /** 常见命名实体 + 数字引用；够用它且不引 java.desktop。 */
    private static String decodeEntities(String raw) {
        if (raw.indexOf('&') < 0) {
            return raw;
        }
        String s = raw.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&mdash;", "—").replace("&ndash;", "–")
                .replace("&hellip;", "…").replace("&middot;", "·").replace("&copy;", "©");
        Matcher m = NUMERIC_ENTITY.matcher(s);
        if (!m.find()) {
            return s;
        }
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group(1);
            int code;
            try {
                code = digits.charAt(0) == 'x' || digits.charAt(0) == 'X'
                        ? Integer.parseInt(digits.substring(1), 16)
                        : Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                code = 0;
            }
            String replacement = code > 0 && code <= 0x10FFFF ? new String(Character.toChars(code)) : " ";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        return m.appendTail(sb).toString();
    }
}
