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

package com.gettyio.gim.agent.ui.chat.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 轻量代码着色器：把一段代码切成「关键字 / 字符串 / 数字 / 注释 / 普通」五类文本段，
 * 供 {@link MarkdownRenderer} 的代码块以 TextFlow 着色渲染。
 * <p>不是完整的语法分析：按字符扫描 + 每语言关键字表，够辨识代码结构即可；
 * 未知语言回退通用规则（字符串/数字/两种注释），保证任何代码块都有基本的可读性区分。
 * 纯静态无状态，不依赖 JavaFX 类型，可独立单测。</p>
 *
 * @author gogym
 */
public final class CodeHighlighter {

    private CodeHighlighter() {
    }

    /** 一个着色段：文本 + 样式类名（{@code null} 表示普通文本，用默认色）。 */
    public record Segment(String text, String styleClass) {
    }

    /** 状态机当前所处的一词法态。 */
    private enum Lex { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING }

    private static final Set<String> COMMON =
            Set.of("if", "else", "for", "while", "return", "break", "continue", "switch", "case",
                    "default", "try", "catch", "finally", "throw", "new", "in", "of", "do",
                    "function", "class", "extends", "implements", "import", "package", "public",
                    "private", "protected", "static", "final", "void", "int", "long", "float", "double",
                    "boolean", "bool", "char", "byte", "short", "var", "let", "const", "val", "def",
                    "elif", "lambda", "pass", "with", "as", "async", "await", "yield", "struct",
                    "enum", "interface", "namespace", "using", "this", "self", "super", "null", "true",
                    "false", "None", "True", "False", "nil", "select", "from", "where", "insert",
                    "update", "delete", "join", "group", "order", "by", "and", "or", "not");

    /** 各语言族额外补充的关键字（ COMMON 已含基础集，这里只放易混的领域词）。 */
    private static final Map<String, Set<String>> EXTRA = Map.of(
            "sql", Set.of("SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
                    "DELETE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "ORDER",
                    "LIMIT", "CREATE", "TABLE", "INDEX", "DROP", "ALTER", "PRIMARY", "KEY", "FOREIGN",
                    "REFERENCES", "AND", "OR", "NOT", "NULL", "AS", "DISTINCT", "COUNT", "SUM", "AVG"),
            "html", Set.of("html", "head", "body", "div", "span", "script", "style", "link", "meta"),
            "go", Set.of("func", "chan", "go", "defer", "range", "map", "type", "package"),
            "rust", Set.of("fn", "mut", "impl", "trait", "pub", "use", "mod", "match", "Some", "None", "Ok", "Err"),
            "shell", Set.of("echo", "cd", "ls", "export", "source", "fi", "then", "esac", "local"));

    /**
     * 切词法段。注释按 {@code //}、{@code #}、{@code --}、{@code /* *\/} 识别；
     * 字符串含单双引号与反引号；数字含小数与十六进制。输入 {@code null} 返回空表。
     */
    public static List<Segment> tokenize(String code, String languageHint) {
        List<Segment> out = new ArrayList<>();
        if (code == null || code.isEmpty()) {
            return out;
        }
        Set<String> keywords = keywordsFor(languageHint);
        int n = code.length();
        StringBuilder buf = new StringBuilder();   // 当前普通文本累积
        Lex lex = Lex.CODE;
        String stringQuote = "";

        for (int i = 0; i < n; i++) {
            char c = code.charAt(i);
            char next = i + 1 < n ? code.charAt(i + 1) : '\0';

            if (lex == Lex.STRING) {
                int at = code.indexOf(stringQuote, i);
                if (at < 0) {
                    buf.append(code, i, n);
                    i = n;
                } else {
                    buf.append(code, i, at + stringQuote.length());
                    i = at + stringQuote.length() - 1;
                }
                flush(buf, out, "md-code-str");
                lex = Lex.CODE;
                continue;
            }
            if (lex == Lex.LINE_COMMENT) {
                if (c == '\n') {
                    flush(buf, out, "md-code-com");
                    buf.append('\n');
                    lex = Lex.CODE;
                } else {
                    buf.append(c);
                }
                continue;
            }
            if (lex == Lex.BLOCK_COMMENT) {
                if (c == '*' && next == '/') {
                    buf.append("*/");
                    i++;
                    flush(buf, out, "md-code-com");
                    lex = Lex.CODE;
                } else {
                    buf.append(c);
                }
                continue;
            }

            // CODE 态：尝试进入其它词法态
            if ((c == '/' && next == '/') || (c == '#') || (c == '-' && next == '-')) {
                int len = (c == '-' && next == '-') ? 2 : (c == '/' ? 2 : 1);
                flush(buf, out, null);
                buf.append(code, i, i + len);
                i += len - 1;
                lex = Lex.LINE_COMMENT;
                continue;
            }
            if (c == '/' && next == '*') {
                flush(buf, out, null);
                buf.append("/*");
                i++;
                lex = Lex.BLOCK_COMMENT;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                flush(buf, out, null);
                stringQuote = String.valueOf(c);
                buf.append(c);
                lex = Lex.STRING;
                continue;
            }
            if (Character.isDigit(c) && (buf.length() == 0 || !isWordChar(buf.charAt(buf.length() - 1)))) {
                flush(buf, out, null);
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(code.charAt(j)) || code.charAt(j) == '.'
                        || (code.charAt(j) == 'x' && j == i + 1) || code.charAt(j) == 'b')) {
                    j++;
                }
                buf.append(code, i, j);
                i = j - 1;
                flush(buf, out, "md-code-num");
                continue;
            }
            if (isWordChar(c) && (buf.isEmpty() || !isWordChar(buf.charAt(buf.length() - 1)))) {
                // 词首：向前吞完整单词后判关键字
                flush(buf, out, null);
                int j = i;
                while (j < n && isWordChar(code.charAt(j))) {
                    j++;
                }
                buf.append(code, i, j);
                i = j - 1;
                flushWord(buf, out, keywords);
                continue;
            }
            buf.append(c);
        }
        // 收尾：残留文本按其词法态着色
        String tailCls = switch (lex) {
            case LINE_COMMENT, BLOCK_COMMENT -> "md-code-com";
            case STRING -> "md-code-str";
            default -> null;
        };
        flush(buf, out, tailCls);
        return out;
    }

    private static Set<String> keywordsFor(String languageHint) {
        if (languageHint == null || languageHint.isBlank()) {
            return COMMON;
        }
        String lang = languageHint.trim().toLowerCase(Locale.ROOT);
        for (var e : EXTRA.entrySet()) {
            if (lang.equals(e.getKey()) || lang.startsWith(e.getKey() + "-")) {
                Set<String> all = new java.util.HashSet<>(COMMON);
                all.addAll(e.getValue());
                return all;
            }
        }
        return COMMON;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** 把累积缓冲按指定样式类冲刷成段；空缓冲忽略。 */
    private static void flush(StringBuilder buf, List<Segment> out, String styleClass) {
        if (buf.length() == 0) {
            return;
        }
        out.add(new Segment(buf.toString(), styleClass));
        buf.setLength(0);
    }

    /** 冲刷一个完整单词：命中关键字表则按关键字着色，否则普通。 */
    private static void flushWord(StringBuilder buf, List<Segment> out, Set<String> keywords) {
        String word = buf.toString();
        buf.setLength(0);
        out.add(new Segment(word, keywords.contains(word) ? "md-code-kw" : null));
    }
}
