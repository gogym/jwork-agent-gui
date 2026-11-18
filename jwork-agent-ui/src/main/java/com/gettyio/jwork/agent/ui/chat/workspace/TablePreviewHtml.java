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
import com.gettyio.jwork.agent.ui.i18n.I18n;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 表格预览 HTML：把 {@link TabularInspector.SheetTable} 渲染成带工作表页签的 web 表格，
 * 交 {@code JcefOsrView} 装载（与 HTML/SVG 产物同一条阅读通道）。
 * <p>观感约定：页签条常驻顶部不随表滚动；表头吸顶；斑马纹 + 悬停行；数字列右对齐等宽数字；
 * 宽表横向滚动、长单元格省略号 + title 悬停全文；被预览上限截断时表底给出总行数提示。
 * 明暗两套色板经 {@code html[data-theme]} 切换，与 {@code UiTheme} 同步。</p>
 * <p>纯字符串拼装、不触 FX/JCEF，包私有便于单测；单元格一律 HTML 转义防注入。</p>
 *
 * @author gogym
 */
final class TablePreviewHtml {

    /** 数字列判定：千分位/小数/百分号/负号皆算数字（金额、数量列右对齐）。 */
    private static final Pattern NUMERIC = Pattern.compile("-?[\\d,]+(\\.\\d+)?%?");

    private TablePreviewHtml() {
    }

    /** 组装整页 HTML；{@code dark} 取当前 UiTheme 模式。空表列表返回空态页。 */
    static String render(List<TabularInspector.SheetTable> sheets, boolean dark) {
        StringBuilder body = new StringBuilder();
        boolean multi = sheets != null && sheets.size() > 1;
        if (multi) {
            body.append("<div class=\"tabs\">");
            for (int i = 0; i < sheets.size(); i++) {
                body.append("<button class=\"tab").append(i == 0 ? " on" : "").append("\" onclick=\"pick(")
                        .append(i).append(")\">").append(escape(sheets.get(i).name())).append("</button>");
            }
            body.append("</div>");
        }
        if (sheets == null || sheets.isEmpty()) {
            body.append("<div class=\"note\">").append(escape(I18n.t("chat.workspace.table.empty")))
                    .append("</div>");
        } else {
            for (int i = 0; i < sheets.size(); i++) {
                body.append(sheetSection(sheets.get(i), i != 0));
            }
        }
        return """
                <!DOCTYPE html>
                <html data-theme="%s"><head><meta charset="utf-8"><style>
                :root{--bg:#ffffff;--fg:#24292f;--mut:#697179;--bd:#e4e7eb;--head:#f4f6f8;--zebra:#fafbfc;--hover:#eef2f6;}
                html[data-theme=dark]{--bg:#1e2126;--fg:#e6e8eb;--mut:#98a1ab;--bd:#343a42;--head:#262b31;--zebra:#22262c;--hover:#2c323a;}
                *{box-sizing:border-box;}
                html,body{margin:0;height:100%%;}
                body{background:var(--bg);color:var(--fg);font:12px/1.5 "Segoe UI","Microsoft YaHei",sans-serif;display:flex;flex-direction:column;}
                .tabs{flex:none;display:flex;gap:4px;padding:8px 12px;border-bottom:1px solid var(--bd);overflow-x:auto;}
                .tab{flex:none;padding:4px 12px;border:1px solid transparent;border-radius:6px;background:transparent;color:var(--mut);font:inherit;cursor:pointer;}
                .tab:hover{background:var(--hover);}
                .tab.on{background:var(--head);border-color:var(--bd);color:var(--fg);font-weight:600;}
                .sheet{flex:1;overflow:auto;}
                .sheet[hidden]{display:none;}
                table{border-collapse:collapse;min-width:100%%;}
                th,td{padding:6px 12px;border-bottom:1px solid var(--bd);text-align:left;white-space:nowrap;max-width:340px;overflow:hidden;text-overflow:ellipsis;}
                th{position:sticky;top:0;background:var(--head);font-weight:600;z-index:1;}
                th.num,td.num{text-align:right;font-variant-numeric:tabular-nums;}
                tbody tr:nth-child(even){background:var(--zebra);}
                tbody tr:hover{background:var(--hover);}
                .note{padding:8px 12px;color:var(--mut);font-size:11px;}
                </style></head><body>
                %s
                <script>function pick(i){var t=document.querySelectorAll('.tab'),s=document.querySelectorAll('.sheet');for(var k=0;k<t.length;k++){t[k].classList.toggle('on',k===i);s[k].hidden=k!==i;}}</script>
                </body></html>
                """.formatted(dark ? "dark" : "light", body);
    }

    /** 单表 section：首行作吸顶表头，其余斑马行；截断/空表在表底给提示。 */
    private static String sheetSection(TabularInspector.SheetTable sheet, boolean hidden) {
        StringBuilder sb = new StringBuilder();
        sb.append("<section class=\"sheet\"").append(hidden ? " hidden" : "").append('>');
        List<List<String>> rows = sheet.rows();
        if (rows.isEmpty()) {
            sb.append("<div class=\"note\">").append(escape(I18n.t("chat.workspace.table.empty")))
                    .append("</div>");
        } else {
            List<String> head = rows.get(0);
            List<List<String>> body = rows.subList(1, rows.size());
            sb.append("<table><thead><tr>");
            for (int c = 0; c < head.size(); c++) {
                sb.append(cell("th", head.get(c), numericColumn(body, c)));
            }
            sb.append("</tr></thead><tbody>");
            for (List<String> row : body) {
                sb.append("<tr>");
                for (int c = 0; c < head.size(); c++) {
                    sb.append(cell("td", c < row.size() ? row.get(c) : "", numericColumn(body, c)));
                }
                sb.append("</tr>");
            }
            sb.append("</tbody></table>");
            if (sheet.truncated()) {
                sb.append("<div class=\"note\">")
                        .append(escape(I18n.t("chat.workspace.table.rowsNote", sheet.totalRows(), rows.size())))
                        .append("</div>");
            }
        }
        sb.append("</section>");
        return sb.toString();
    }

    /** 单元格：转义正文 + title 悬停全文（长文被省略号截断时仍可看全）。 */
    private static String cell(String tag, String value, boolean numeric) {
        String v = value == null ? "" : value;
        return "<" + tag + (numeric ? " class=\"num\"" : "") + " title=\"" + escape(v) + "\">"
                + escape(v) + "</" + tag + ">";
    }

    /** 数字列：body 里该列非空值全匹配数字形态且至少一个非空（表头文字不参与判定）。 */
    private static boolean numericColumn(List<List<String>> body, int col) {
        boolean seen = false;
        for (List<String> row : body) {
            String v = col < row.size() ? row.get(col) : "";
            if (v == null || v.isBlank()) {
                continue;
            }
            if (!NUMERIC.matcher(v.strip()).matches()) {
                return false;
            }
            seen = true;
        }
        return seen;
    }

    /** HTML 转义：正文与属性共用，杜绝单元格内容注入脚本/标签。 */
    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}