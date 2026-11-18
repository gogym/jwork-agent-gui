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

package com.gettyio.jwork.agent.core.doc;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TabularInspector} 单测：流式读取的表头/区间/dimension 规模、日期显示值还原、
 * csv 编码嗅探与失败兜底。
 *
 * @author gogym
 */
class TabularInspectorTest {

    @TempDir
    Path dir;

    /** 写一个两表 xlsx：订单表含表头 + 数据行（数值 + 日期），另有空表。 */
    private Path ordersXlsx() throws IOException {
        Path file = dir.resolve("orders.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("退款明细");
            XSSFRow head = sheet.createRow(0);
            head.createCell(0).setCellValue("商家订单号");
            head.createCell(1).setCellValue("付款时间");
            head.createCell(2).setCellValue("实付金额");

            XSSFRow row = sheet.createRow(1);
            row.createCell(0).setCellValue("T200P88001");
            row.createCell(1).setCellValue(dateValue("2026-10-01"));
            row.getCell(1).setCellStyle(dateStyle(wb));
            row.createCell(2).setCellValue(178660.17);

            XSSFRow row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("T200P88002");
            row2.createCell(1).setCellValue("2026-10-05");
            row2.createCell(2).setCellValue(199.0);

            wb.createSheet("空表");
            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }
        return file;
    }

    private static CellStyle dateStyle(XSSFWorkbook wb) {
        CreationHelper helper = wb.getCreationHelper();
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
        return style;
    }

    private static Date dateValue(String iso) {
        return Date.from(LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    @Test
    @DisplayName("工作表清单：名称与 dimension 规模")
    void listsSheetsWithDimension() throws IOException {
        List<TabularInspector.Sheet> sheets = TabularInspector.sheets(ordersXlsx());
        assertEquals(2, sheets.size());
        assertEquals("退款明细", sheets.get(0).name());
        assertEquals(3, sheets.get(0).rows());
        assertEquals(3, sheets.get(0).cols());
        assertEquals("空表", sheets.get(1).name());
    }

    @Test
    @DisplayName("行区间：日期还原成显示值，而不是 45000 序列号")
    void readsRangeWithFormattedDates() throws IOException {
        TabularInspector.Table t = TabularInspector.read(ordersXlsx(), "退款明细", 1, 2);
        assertNotNull(t);
        assertEquals(2, t.rows().size());
        assertEquals("T200P88001", t.rows().get(0).get(0));
        assertEquals("2026-10-01", t.rows().get(0).get(1));
        assertTrue(t.rows().get(0).get(2).startsWith("178660"), t.rows().get(0).get(2));
    }

    @Test
    @DisplayName("工作表可按名称或 1 基序号定位；不存在的表返回 null")
    void resolvesSheetByIndexAndRejectsUnknown() throws IOException {
        Path file = ordersXlsx();
        assertEquals("退款明细", TabularInspector.read(file, "1", 0, 1).sheet());
        assertEquals("空表", TabularInspector.read(file, "2", 0, 1).sheet());
        assertEquals("退款明细", TabularInspector.read(file, "退款明细", 0, 1).sheet());
        assertNull(TabularInspector.read(file, "没有这张表", 0, 1));
    }

    @Test
    @DisplayName("概览文本含表头样例行，供附件摘要直接引用")
    void overviewContainsHeaderSample() throws IOException {
        String text = TabularInspector.overview(ordersXlsx(), 2);
        assertNotNull(text);
        assertTrue(text.contains("商家订单号"), text);
        assertTrue(text.contains("退款明细"), text);
    }

    @Test
    @DisplayName("工具渲染：给出绝对路径、本次区间与下一页 offset")
    void renderGuidesPaging() throws IOException {
        Path file = ordersXlsx();
        String text = TabularInspector.render(file, "退款明细", 0, 1);
        assertTrue(text.contains("工作表：退款明细"), text);
        assertTrue(text.contains("offset=1"), text);
        assertTrue(text.contains(file.toAbsolutePath().toString().replace('\\', '/'))
                || text.contains(file.toAbsolutePath().toString()), text);
    }

    @Test
    @DisplayName("超过 MAX_ROWS 的行数请求被钳制，不至于一次吐完")
    void capsRequestedRows() throws IOException {
        Path file = bigXlsx(TabularInspector.MAX_ROWS + 50);
        TabularInspector.Table t = TabularInspector.read(file, null, 0, 10_000);
        assertEquals(TabularInspector.MAX_ROWS, t.rows().size());
        assertEquals(TabularInspector.MAX_ROWS + 50, t.totalRows());
    }

    @Test
    @DisplayName("非表格文件：概览返回 null、渲染给出可读原因")
    void nonTabularFallsBack() throws IOException {
        Path txt = Files.writeString(dir.resolve("note.txt"), "hello");
        assertNull(TabularInspector.overview(txt, 3));
        assertTrue(TabularInspector.render(txt, null, 0, 10).contains("不是可按行探查"));
    }

    @Test
    @DisplayName("csv：GBK 编码嗅探出中文表头，分隔符按首行判定")
    void csvSniffsGbkAndDelimiter() throws IOException {
        Path file = dir.resolve("pay.csv");
        String csv = "订单编号;宝贝标题;金额\n1001;利郎购物金;120.5\n1002;利郎男装;88.0\n";
        Files.write(file, csv.getBytes(Charset.forName("GBK")));
        String text = TabularInspector.render(file, null, 0, 5);
        assertTrue(text.contains("宝贝标题"), text);
        assertTrue(text.contains("利郎购物金"), text);
        assertTrue(text.contains("已到末行"), text);
    }

    @Test
    @DisplayName("损坏的 xlsx：清单为空而非抛异常")
    void corruptFileYieldsEmptySheets() throws IOException {
        Path file = Files.write(dir.resolve("bad.xlsx"), new byte[] {1, 2, 3, 4});
        assertTrue(TabularInspector.sheets(file).isEmpty());
        assertNull(TabularInspector.read(file, null, 0, 10));
    }

    @Test
    @DisplayName("类型判定：xlsx/csv/tsv 可探查，docx 不行")
    void typeDetection() {
        assertTrue(TabularInspector.isTabular("A.XLSX"));
        assertTrue(TabularInspector.isTabular("a.csv"));
        assertFalse(TabularInspector.isTabular("a.docx"));
    }

    @Test
    @DisplayName("预览：全表收集、日期还原显示值、空表保留")
    void previewCollectsSheetsWithDisplayValues() throws IOException {
        List<TabularInspector.SheetTable> sheets = TabularInspector.preview(ordersXlsx());
        assertEquals(2, sheets.size());
        assertEquals("退款明细", sheets.get(0).name());
        assertEquals(3, sheets.get(0).totalRows());
        assertEquals(3, sheets.get(0).rows().size());
        assertFalse(sheets.get(0).truncated());
        assertEquals("2026-10-01", sheets.get(0).rows().get(1).get(1));
        assertEquals("空表", sheets.get(1).name());
        assertTrue(sheets.get(1).rows().isEmpty());
    }

    @Test
    @DisplayName("预览：行数与单元格长度按预览上限钳制并带截断标记")
    void previewCapsRowsAndCells() throws IOException {
        Path big = bigXlsx(TabularInspector.PREVIEW_ROWS + 20);
        List<TabularInspector.SheetTable> sheets = TabularInspector.preview(big);
        assertEquals(1, sheets.size());
        assertEquals(TabularInspector.PREVIEW_ROWS, sheets.get(0).rows().size());
        assertEquals(TabularInspector.PREVIEW_ROWS + 20, sheets.get(0).totalRows());
        assertTrue(sheets.get(0).truncated());

        Path csv = dir.resolve("long.csv");
        Files.writeString(csv, "a,b\n" + "x".repeat(TabularInspector.PREVIEW_CELL_CHARS + 50) + ",2\n");
        List<TabularInspector.SheetTable> cs = TabularInspector.preview(csv);
        assertEquals(1, cs.size());
        assertEquals("(csv)", cs.get(0).name());
        assertFalse(cs.get(0).truncated());
        String cell = cs.get(0).rows().get(1).get(0);
        assertEquals(TabularInspector.PREVIEW_CELL_CHARS + 1, cell.length());
        assertTrue(cell.endsWith("…"), cell);
    }

    @Test
    @DisplayName("预览：公式单元格取缓存计算结果而非公式串")
    void previewFormulaShowsCachedResult() throws IOException {
        Path file = dir.resolve("formula.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("汇总");
            XSSFRow head = sheet.createRow(0);
            head.createCell(0).setCellValue("数量");
            head.createCell(1).setCellValue("单价");
            head.createCell(2).setCellValue("金额");
            XSSFRow row = sheet.createRow(1);
            row.createCell(0).setCellValue(120);
            row.createCell(1).setCellValue(899);
            row.createCell(2).setCellFormula("A2*B2");
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }
        List<TabularInspector.SheetTable> sheets = TabularInspector.preview(file);
        assertEquals("107880", sheets.get(0).rows().get(1).get(2));
    }

    @Test
    @DisplayName("预览：损坏/非表格/空路径返回空列表供调用方回退")
    void previewFallsBackToEmpty() throws IOException {
        Path bad = Files.write(dir.resolve("bad2.xlsx"), new byte[] {1, 2, 3, 4});
        assertTrue(TabularInspector.preview(bad).isEmpty());
        Path txt = Files.writeString(dir.resolve("note2.txt"), "hello");
        assertTrue(TabularInspector.preview(txt).isEmpty());
        assertTrue(TabularInspector.preview(null).isEmpty());
    }

    private Path bigXlsx(int rows) throws IOException {
        Path file = dir.resolve("big" + rows + ".xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("data");
            XSSFRow head = sheet.createRow(0);
            head.createCell(0).setCellValue("id");
            for (int i = 1; i < rows; i++) {
                XSSFRow r = sheet.createRow(i);
                r.createCell(0).setCellValue(i);
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }
        return file;
    }
}
