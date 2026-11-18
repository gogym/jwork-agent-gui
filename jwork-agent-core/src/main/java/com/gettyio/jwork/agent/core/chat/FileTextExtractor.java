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

package com.gettyio.jwork.agent.core.chat;

import com.gettyio.jwork.agent.domain.util.FileKinds;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 文档附件正文抽取：把 pdf/docx/xlsx/txt/md/csv 的字节转成纯文本喂给模型。
 * <p>抽取失败不抛异常（返回空串或错误提示），以免一个坏文件阻断整轮对话。
 * 结果按 {@link #MAX_CHARS} 截断，防止超大文档撑爆上下文。</p>
 *
 * @author gogym
 */
public final class FileTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(FileTextExtractor.class);

    /** 抽取文本上限（字符）：超出截断并追加省略标记。 */
    public static final int MAX_CHARS = 200_000;

    private FileTextExtractor() {
    }

    /** 该文件名/ MIME 是否属于可抽取的文档类型。 */
    public static boolean supports(String fileName, String mime) {
        return FileKinds.isExtractableDocument(fileName) || isPlainTextMime(mime);
    }

    private static boolean isPlainTextMime(String mime) {
        return mime != null && (mime.startsWith("text/") || mime.contains("csv"));
    }

    /**
     * 从字节抽取纯文本；无法识别或失败时尽力返回可解码文本或错误提示（永不返回 null）。
     */
    public static String extract(String fileName, String mime, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String text;
        try {
            if (n.endsWith(FileKinds.PDF)) {
                text = extractPdf(bytes);
            } else if (n.endsWith(FileKinds.DOCX)) {
                text = extractDocx(bytes);
            } else if (n.endsWith(FileKinds.XLSX)) {
                text = extractXlsx(bytes);
            } else {
                // txt/md/csv 及未知文本类：按 UTF-8 解码
                text = new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            log.warn("抽取文档正文失败: {}", fileName, t);
            text = "";
        }
        return cap(text);
    }

    private static String extractPdf(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private static String extractDocx(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) {
                    sb.append(t).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String extractXlsx(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                sb.append("## ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (Cell cell : row) {
                        if (line.length() > 0) {
                            line.append('\t');
                        }
                        line.append(fmt.formatCellValue(cell));
                    }
                    if (line.length() > 0) {
                        sb.append(line).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String cap(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        if (trimmed.length() <= MAX_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_CHARS) + "\n…(内容已截断)";
    }
}
