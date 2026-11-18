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

package com.gettyio.jwork.agent.ui.chat.workspace;

import com.gettyio.jwork.agent.core.doc.TabularInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TablePreviewHtml} 单测：页签/吸顶表头/数字右对齐/截断提示/明暗色板/转义防注入。
 * 纯字符串断言，不触 FX/JCEF；本地化文案只断言结构标记与数字，不绑定语种。
 *
 * @author gogym
 */
class TablePreviewHtmlTest {

    private static TabularInspector.SheetTable staff() {
        return new TabularInspector.SheetTable("员工信息", 3, List.of(
                List.of("工号", "姓名", "月薪(元)"),
                List.of("E001", "张伟", "18,000.00"),
                List.of("E002", "李娜", "21,000.00")), false);
    }

    @Test
    @DisplayName("单表：无页签条，表头吸顶，明色色板")
    void singleSheetHasStickyHeadAndLightTheme() {
        String html = TablePreviewHtml.render(List.of(staff()), false);
        assertTrue(html.contains("<html data-theme=\"light\">"), html);
        assertFalse(html.contains("class=\"tabs\""), html);
        assertTrue(html.contains("position:sticky"), html);
        assertTrue(html.contains("<thead>"), html);
        assertTrue(html.contains("nth-child(even)"), html);
    }

    @Test
    @DisplayName("数字列右对齐：金额列带 num 类，工号列不带")
    void numericColumnsRightAligned() {
        String html = TablePreviewHtml.render(List.of(staff()), false);
        assertTrue(html.contains("<td class=\"num\" title=\"18,000.00\">18,000.00</td>"), html);
        assertTrue(html.contains("<td title=\"E001\">E001</td>"), html);
        assertTrue(html.contains("tabular-nums"), html);
    }

    @Test
    @DisplayName("多表：页签条 + 首表可见其余 hidden，pick 脚本在场")
    void multiSheetTabsSwitchSections() {
        String html = TablePreviewHtml.render(List.of(staff(),
                new TabularInspector.SheetTable("销售明细", 1, List.of(List.of("日期")), false)), true);
        assertTrue(html.contains("class=\"tabs\""), html);
        assertTrue(html.contains("<button class=\"tab on\""), html);
        assertTrue(html.contains("<section class=\"sheet\" hidden>"), html);
        assertTrue(html.contains("function pick("), html);
    }

    @Test
    @DisplayName("截断提示：表底 note 给出总行数与已预览行数")
    void truncationNoteMentionsTotals() {
        TabularInspector.SheetTable cut = new TabularInspector.SheetTable("销售明细", 600,
                List.of(List.of("日期"), List.of("2026-10-05")), true);
        String html = TablePreviewHtml.render(List.of(cut), false);
        assertTrue(html.contains("class=\"note\""), html);
        assertTrue(html.contains("600"), html);
        assertTrue(html.contains("2"), html);
    }

    @Test
    @DisplayName("空表与空列表：note 空态而非空白页")
    void emptySheetRendersNote() {
        String html = TablePreviewHtml.render(List.of(
                new TabularInspector.SheetTable("空表", 0, List.of(), false)), false);
        assertTrue(html.contains("class=\"note\""), html);
        assertTrue(TablePreviewHtml.render(List.of(), false).contains("class=\"note\""));
    }

    @Test
    @DisplayName("暗色：data-theme=dark 切换色板变量")
    void darkThemeAttribute() {
        assertTrue(TablePreviewHtml.render(List.of(staff()), true).contains("<html data-theme=\"dark\">"));
    }

    @Test
    @DisplayName("单元格转义：脚本/标签进不了 DOM，title 与正文同转义")
    void cellsAreEscaped() {
        TabularInspector.SheetTable evil = new TabularInspector.SheetTable("x", 2, List.of(
                List.of("h"), List.of("<script>alert(1)</script>")), false);
        String html = TablePreviewHtml.render(List.of(evil), false);
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), html);
        assertFalse(html.contains("<script>alert(1)</script>"), html);
        assertEquals("a&amp;b&quot;c&lt;d&gt;&#39;", TablePreviewHtml.escape("a&b\"c<d>'"));
        assertEquals("", TablePreviewHtml.escape(null));
    }
}