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

package com.gettyio.jwork.agent.ui.chat.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodeHighlighter} 单测：验证注释/字符串/数字/关键字四类切段与重组一致性。
 *
 * @author gogym
 */
class CodeHighlighterTest {

    /** 拼回原文：切段必须无损重组（渲染依赖这一点保证代码不变形）。 */
    private static String join(List<CodeHighlighter.Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (CodeHighlighter.Segment s : segments) {
            sb.append(s.text());
        }
        return sb.toString();
    }

    @Test
    void reassemblesLosslessly() {
        String code = "public class A { // demo\n  int x = \"hi\" + 0x1f; /* t */ }";
        assertEquals(code, join(CodeHighlighter.tokenize(code, "java")));
    }

    @Test
    void highlightsKeywordsStringsNumbersAndComments() {
        String code = "// 注释\nint x = 42;\nString s = \"ok\";";
        List<CodeHighlighter.Segment> segs = CodeHighlighter.tokenize(code, "java");
        assertTrue(segs.stream().anyMatch(s -> "md-code-com".equals(s.styleClass()) && s.text().startsWith("//")));
        assertTrue(segs.stream().anyMatch(s -> "md-code-kw".equals(s.styleClass()) && s.text().equals("int")));
        assertTrue(segs.stream().anyMatch(s -> "md-code-num".equals(s.styleClass()) && s.text().equals("42")));
        assertTrue(segs.stream().anyMatch(s -> "md-code-str".equals(s.styleClass()) && s.text().equals("\"ok\"")));
    }

    @Test
    void unknownLanguageFallsBackToCommonRules() {
        String code = "x = 'v' # tail";
        List<CodeHighlighter.Segment> segs = CodeHighlighter.tokenize(code, "ruby");
        assertTrue(segs.stream().anyMatch(s -> "md-code-str".equals(s.styleClass())));
        assertTrue(segs.stream().anyMatch(s -> "md-code-com".equals(s.styleClass()) && s.text().startsWith("#")));
        assertNull(segs.get(0).styleClass());
    }

    @Test
    void sqlExtraKeywordsHitCaseInsensitiveFamily() {
        String code = "SELECT id FROM t WHERE x = 1;";
        List<CodeHighlighter.Segment> segs = CodeHighlighter.tokenize(code, "sql");
        assertTrue(segs.stream().anyMatch(s -> "md-code-kw".equals(s.styleClass()) && s.text().equals("SELECT")));
    }

    @Test
    void emptyInputGivesEmptyOutput() {
        assertTrue(CodeHighlighter.tokenize(null, "java").isEmpty());
        assertTrue(CodeHighlighter.tokenize("", "java").isEmpty());
    }
}
