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

package com.gettyio.jwork.agent.tools.data;

import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TableInspectTools} 单测：清单模式（不传 sheet）、相对路径解析、分页与拒答口径。
 *
 * @author gogym
 */
class TableInspectToolsTest {

    @TempDir
    Path dir;

    private TableInspectTools tools;
    private Path orders;

    @BeforeEach
    void setUp() throws IOException {
        tools = new TableInspectTools(() -> dir);
        orders = writeOrders(dir.resolve("订单.xlsx"), 12);
    }

    @Test
    @DisplayName("不传 sheet：列出工作表规模并给出下一步取数方式")
    void omitsSheetListsWorkbook() {
        String out = tools.inspectFile(orders.toString(), null, null, null);
        assertTrue(out.contains("订单.xlsx"), out);
        assertTrue(out.contains("工作表 1 个"), out);
        assertTrue(out.contains("约 13 行"), out);
        assertTrue(out.contains("inspect_file(path="), out);
    }

    @Test
    @DisplayName("相对路径挂到工作区根：attachments/<sid>/x.csv 可用")
    void resolvesRelativeAgainstWorkspace() throws IOException {
        Path sub = Files.createDirectories(dir.resolve("attachments/s1"));
        Files.writeString(sub.resolve("pay.csv"), "订单编号,金额\n1001,120.5\n1002,88\n");
        String out = tools.inspectFile("attachments/s1/pay.csv", "1", 1, 2);
        assertTrue(out.contains("1001"), out);
        assertTrue(out.contains("1002"), out);
    }

    @Test
    @DisplayName("分页：offset 之后继续取，并提示下一页")
    void pagesWithOffset() {
        String out = tools.inspectFile(orders.toString(), "订单", 1, 5);
        assertTrue(out.contains("T200P88001"), out);
        assertTrue(out.contains("T200P88005"), out);
        assertFalse(out.contains("T200P88006"), out);
        assertTrue(out.contains("offset=6"), out);
    }

    @Test
    @DisplayName("口径：空路径、非表格、不存在均返回可读原因")
    void explainsRejectedInput() {
        assertTrue(tools.inspectFile("  ", null, null, null).contains("不能为空"));
        assertTrue(tools.inspectFile("a.docx", null, null, null).contains("只支持表格文件"));
        assertTrue(tools.inspectFile(orders.getParent().resolve("missing.xlsx").toString(), "1", 0, 1)
                .contains("文件不存在"));
        assertTrue(tools.inspectFile(dir.resolve("破损.xlsx").toString(), null, null, null).contains("读取失败"));
    }

    private static Path writeOrders(Path file, int rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("订单");
            XSSFRow head = sheet.createRow(0);
            head.createCell(0).setCellValue("商家订单号");
            head.createCell(1).setCellValue("金额");
            for (int i = 1; i <= rows; i++) {
                XSSFRow r = sheet.createRow(i);
                r.createCell(0).setCellValue("T200P" + (88000 + i));
                r.createCell(1).setCellValue(10.0 + i);
            }
            wb.write(out);
        }
        Files.write(file, out.toByteArray());
        return file;
    }
}
