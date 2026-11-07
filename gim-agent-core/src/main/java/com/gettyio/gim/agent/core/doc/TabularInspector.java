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

package com.gettyio.gim.agent.core.doc;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.Styles;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表格文件探查：按 <b>SAX 流式</b>读取 xlsx 与 csv/tsv，只输出「结构概览 + 指定行区间」，不把整表塞进上下文。
 * <p>为什么不用 {@code WorkbookFactory}：DOM 方式会把整个工作簿装进内存，几十万行的导出文件直接 OOM；
 * 流式读取让「10 万行账单」这类任务能被反复切片查看，取满区间即提前终止遍历。</p>
 * <p>单元格值一律经 {@link DataFormatter} 还原成 Excel 里看到的显示值：否则日期交给模型的是
 * 45000 这种序列号，任何按时间筛选的统计都必然出错。</p>
 *
 * @author gogym
 */
public final class TabularInspector {

    private static final Logger log = LoggerFactory.getLogger(TabularInspector.class);

    /** 单次返回的最大行数：再多就截断，防止一次工具调用吃掉整个上下文窗口。 */
    public static final int MAX_ROWS = 400;
    /** 概览里每个工作表附带的样例行数。 */
    public static final int SAMPLE_ROWS = 3;
    /** 渲染文本总量上限（字符）：超出即截断并提示缩小区间。 */
    private static final int MAX_OUTPUT_CHARS = 12_000;
    /** 单元格显示值长度上限。 */
    private static final int MAX_CELL_CHARS = 80;
    /** csv/tsv 只读头部字节上限：超过不整体载入内存。 */
    private static final int MAX_CSV_BYTES = 16 * 1024 * 1024;

    /** dimension 引用（如 {@code A1:AZ12000}）的末尾坐标解析。 */
    private static final Pattern CELL_REF = Pattern.compile("([A-Za-z]+)(\\d+)");

    private TabularInspector() {
    }

    /** 工作表条目：名称 + 行列规模（读不到为 0）。 */
    public record Sheet(String name, int rows, int cols) {
    }

    /** 一次行区间读取的结果。 */
    public record Table(String sheet, int totalRows, int offset, List<List<String>> rows) {
    }

    /** 该文件名是否属于可按行探查的表格类型。 */
    public static boolean isTabular(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(".xlsx") || n.endsWith(".xlsm")
                || n.endsWith(".csv") || n.endsWith(".tsv") || n.endsWith(".tab");
    }

    /**
     * 工作表清单（xlsx 逐表只取 dimension，不读行数据；csv/tsv 视为单表）。
     *
     * @return 失败（非表格、损坏）返回空列表
     */
    public static List<Sheet> sheets(Path file) {
        String n = name(file).toLowerCase(Locale.ROOT);
        try {
            if (isCsv(n)) {
                List<List<String>> head = csvRows(file, 0, SAMPLE_ROWS);
                int cols = head.isEmpty() ? 0 : head.get(0).size();
                return List.of(new Sheet("(csv)", countRows(file), cols));
            }
            return xlsxSheets(file);
        } catch (Exception e) {
            log.warn("读取表格结构失败: {}", file, e);
            return List.of();
        }
    }

    /**
     * 读取指定工作表的行区间。
     *
     * @param sheetRef 工作表名或 1 基序号；空则取第一个
     * @param offset   起始行（0 基；0 通常是表头行）
     * @param rows     行数，钳制到 {@link #MAX_ROWS}
     * @return 结果；工作表不存在或读取失败返回 null
     */
    public static Table read(Path file, String sheetRef, int offset, int rows) {
        int from = Math.max(0, offset);
        int count = Math.min(MAX_ROWS, Math.max(1, rows));
        String n = name(file).toLowerCase(Locale.ROOT);
        try {
            if (isCsv(n)) {
                return new Table("(csv)", countRows(file), from, csvRows(file, from, count));
            }
            return xlsxRead(file, sheetRef, from, count);
        } catch (Exception e) {
            log.warn("读取表格区间失败: {}", file, e);
            return null;
        }
    }

    /**
     * 附件概览文本：规模 + 表头与样例行 + 后续取数指引，供对话上下文注入。
     *
     * @return 非表格类型返回 null（调用方回落到正文抽取）
     */
    public static String overview(Path file, int sampleRows) {
        List<Sheet> list = sheets(file);
        if (list.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Sheet s : list) {
            Table t = read(file, s.name(), 0, Math.max(1, sampleRows));
            sb.append("· 工作表 ").append(s.name());
            if (s.rows() > 0) {
                sb.append("（约 ").append(s.rows()).append(" 行 × ").append(s.cols()).append(" 列）");
            }
            sb.append('\n');
            if (t == null || t.rows().isEmpty()) {
                sb.append("  （无可见数据）\n");
                continue;
            }
            for (List<String> row : t.rows()) {
                sb.append("  ").append(joinRow(row)).append('\n');
            }
        }
        return cap(sb.toString());
    }

    /**
     * 渲染一次行区间读取为文本（{@code inspect_file} 工具返回值）：规模、本次区间、逐行内容、下一页 offset。
     *
     * @param file     表格文件
     * @param sheetRef 工作表名或 1 基序号
     * @param offset   起始行（0 基）
     * @param rows     行数
     * @return 供模型阅读的文本；失败时返回明确原因，绝不返回空串让模型猜
     */
    public static String render(Path file, String sheetRef, int offset, int rows) {
        if (file == null || !Files.isRegularFile(file)) {
            return "文件不存在：" + file;
        }
        if (!isTabular(name(file))) {
            return "不是可按行探查的表格文件（支持 .xlsx/.xlsm/.csv/.tsv）：" + name(file) + "；文档类请用 read_file。";
        }
        Table t = read(file, sheetRef, offset, rows);
        if (t == null) {
            return "读取失败或工作表不存在：" + name(file) + "，工作表=" + (sheetRef == null ? "" : sheetRef)
                    + "。只传 path 可列出全部工作表。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("文件：").append(file.toAbsolutePath()).append('\n');
        sb.append("工作表：").append(t.sheet());
        if (t.totalRows() > 0) {
            sb.append("（约 ").append(t.totalRows()).append(" 行）");
        }
        sb.append("；本次第 ").append(t.offset() + 1).append('–')
                .append(t.offset() + t.rows().size()).append(" 行\n");
        int rowNum = t.offset() + 1;
        for (List<String> row : t.rows()) {
            sb.append("r").append(rowNum++).append('\t').append(joinRow(row)).append('\n');
        }
        if (t.rows().isEmpty()) {
            sb.append("（该区间没有数据；offset 从 0 起算）\n");
        } else if (t.totalRows() > t.offset() + t.rows().size()) {
            sb.append("下一页：offset=").append(t.offset() + t.rows().size()).append('\n');
        } else {
            sb.append("已到末行。\n");
        }
        return cap(sb.toString());
    }

    // ---- xlsx 流式 ----

    private static List<Sheet> xlsxSheets(Path file) throws Exception {
        List<Sheet> out = new ArrayList<>();
        try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg, true);
            Styles styles = reader.getStylesTable();
            SharedStrings sst = reader.getSharedStringsTable();
            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (it.hasNext()) {
                Dimension dim = new Dimension();
                String sheetName = "?";
                // SheetIterator.getSheetName() 返回「最近一次 next() 的表」，必须在 next() 之后取
                try (InputStream in = it.next()) {
                    sheetName = it.getSheetName();
                    parse(in, styles, sst, dim, IgnoreSink.INSTANCE, true);
                } catch (IOException | RuntimeException e) {
                    log.warn("读取工作表规模失败: {} / {}", file, sheetName, e);
                }
                out.add(new Sheet(sheetName, dim.rows, dim.cols));
            }
        }
        return out;
    }

    private static Table xlsxRead(Path file, String sheetRef, int offset, int count) throws Exception {
        try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg, true);
            Styles styles = reader.getStylesTable();
            SharedStrings sst = reader.getSharedStringsTable();
            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) reader.getSheetsData();
            int index = 0;
            while (it.hasNext()) {
                try (InputStream in = it.next()) {
                    String sheetName = it.getSheetName();
                    index++;
                    if (!matchesSheet(sheetRef, sheetName, index)) {
                        continue;
                    }
                    Dimension dim = new Dimension();
                    RangeSink sink = new RangeSink(offset, count);
                    parse(in, styles, sst, dim, sink, false);
                    int total = dim.rows > 0 ? dim.rows : sink.lastRow + 1;
                    return new Table(sheetName, total, offset, sink.rows);
                }
            }
            return null;
        }
    }

    /** 工作表定位：名称（忽略大小写）或 1 基序号；空取第一个。 */
    private static boolean matchesSheet(String ref, String sheetName, int index) {
        if (ref == null || ref.isBlank()) {
            return index == 1;
        }
        String r = ref.trim();
        return r.equals(String.valueOf(index)) || r.equalsIgnoreCase(sheetName);
    }

    private static void parse(InputStream in, Styles styles, SharedStrings sst, Dimension dim,
                              XSSFSheetXMLHandler.SheetContentsHandler sink, boolean stopAtDimension)
            throws Exception {
        XSSFSheetXMLHandler poi = new XSSFSheetXMLHandler(styles, sst, sink, new DataFormatter(), false);
        XMLReader xml = newSecureXmlReader();
        xml.setContentHandler(new SheetXmlHandler(poi, dim, stopAtDimension));
        try {
            xml.parse(new InputSource(in));
        } catch (StopScan ignored) {
            // 需要的数据（dimension / 行区间）已到手，剩余行不再解析
        }
    }

    private static XMLReader newSecureXmlReader() throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XMLReader xml = factory.newSAXParser().getXMLReader();
        xml.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        xml.setFeature("http://xml.org/sax/features/external-general-entities", false);
        xml.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return xml;
    }

    /** 转发给 POI 处理器，顺带截获 {@code <dimension ref>}；列表模式下读到 dimension 即终止。 */
    private static final class SheetXmlHandler extends DefaultHandler {
        private final DefaultHandler delegate;
        private final Dimension dimension;
        private final boolean stopAtDimension;

        private SheetXmlHandler(DefaultHandler delegate, Dimension dimension, boolean stopAtDimension) {
            this.delegate = delegate;
            this.dimension = dimension;
            this.stopAtDimension = stopAtDimension;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            delegate.startElement(uri, localName, qName, attributes);
            if ("dimension".equals(localName)) {
                dimension.apply(attributes.getValue("ref"));
                if (stopAtDimension) {
                    throw new StopScan();
                }
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            delegate.endElement(uri, localName, qName);
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            delegate.characters(ch, start, length);
        }
    }

    /** {@code <dimension ref="A1:AZ12000">} 解出的规模；缺该元素时保持 0。 */
    private static final class Dimension {
        private int rows;
        private int cols;

        private void apply(String ref) {
            if (ref == null || ref.isBlank()) {
                return;
            }
            String[] parts = ref.split(":");
            String last = parts[parts.length - 1];
            int bang = last.lastIndexOf('!');
            if (bang >= 0) {
                last = last.substring(bang + 1);
            }
            Matcher m = CELL_REF.matcher(last);
            if (m.find()) {
                cols = colIndexOf(m.group(1) + "1") + 1;
                try {
                    rows = Integer.parseInt(m.group(2));
                } catch (NumberFormatException e) {
                    rows = 0;
                }
            }
        }
    }

    /** 忽略行内容的 sink（列表模式用）。 */
    private static final class IgnoreSink implements XSSFSheetXMLHandler.SheetContentsHandler {
        private static final IgnoreSink INSTANCE = new IgnoreSink();

        @Override
        public void startRow(int rowNum) {
        }

        @Override
        public void endRow(int rowNum) {
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
        }
    }

    /** 行区间收集器：攒够即抛 {@link StopScan} 提前终止遍历。 */
    private static final class RangeSink implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final int offset;
        private final int limit;
        private final List<List<String>> rows = new ArrayList<>(64);
        private int lastRow = -1;
        private List<String> current;

        private RangeSink(int offset, int limit) {
            this.offset = offset;
            this.limit = limit;
        }

        @Override
        public void startRow(int rowNum) {
            lastRow = Math.max(lastRow, rowNum);
            current = (rowNum >= offset && rowNum < offset + limit) ? new ArrayList<>(16) : null;
        }

        @Override
        public void endRow(int rowNum) {
            if (current == null) {
                return;
            }
            rows.add(List.copyOf(current));
            current = null;
            if (rows.size() >= limit) {
                throw new StopScan();
            }
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (current == null) {
                return;
            }
            int col = cellReference == null ? current.size() : colIndexOf(cellReference);
            while (current.size() < col) {
                current.add("");
            }
            current.add(oneLine(formattedValue == null ? "" : formattedValue));
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
        }
    }

    /** 单元格引用（如 {@code AB12}）→ 0 基列号。 */
    private static int colIndexOf(String cellReference) {
        int col = 0;
        for (int i = 0; i < cellReference.length(); i++) {
            char c = Character.toUpperCase(cellReference.charAt(i));
            if (c < 'A' || c > 'Z') {
                break;
            }
            col = col * 26 + (c - 'A' + 1);
        }
        return Math.max(0, col - 1);
    }

    // ---- csv / tsv ----

    private static boolean isCsv(String lowerName) {
        return lowerName.endsWith(".csv") || lowerName.endsWith(".tsv") || lowerName.endsWith(".tab");
    }

    private static List<List<String>> csvRows(Path file, int offset, int count) throws IOException {
        String text = readHead(file);
        char delimiter = sniffDelimiter(text);
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int seen = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quoted) {
                if (c != '"') {
                    cell.append(c);
                    i++;
                    continue;
                }
                if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i += 2;
                    continue;
                }
                quoted = false;
                i++;
                continue;
            }
            if (c == '"' && cell.isEmpty()) {
                quoted = true;
                i++;
                continue;
            }
            if (c == delimiter) {
                row.add(oneLine(cell.toString()));
                cell.setLength(0);
                i++;
                continue;
            }
            if (c == '\n' || c == '\r') {
                i += (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') ? 2 : 1;
                row.add(oneLine(cell.toString()));
                cell.setLength(0);
                if (!isBlankRow(row)) {
                    if (seen >= offset && out.size() < count) {
                        out.add(List.copyOf(row));
                    }
                    seen++;
                    if (seen >= offset + count) {
                        return out;
                    }
                }
                row.clear();
                continue;
            }
            cell.append(c);
            i++;
        }
        row.add(oneLine(cell.toString()));
        if (seen >= offset && out.size() < count && !isBlankRow(row)) {
            out.add(List.copyOf(row));
        }
        return out;
    }

    private static boolean isBlankRow(List<String> row) {
        return row.stream().allMatch(String::isBlank);
    }

    private static char sniffDelimiter(String text) {
        int end = text.indexOf('\n');
        String head = end > 0 ? text.substring(0, end) : text;
        char best = ',';
        int bestCount = count(head, ',');
        for (char cand : new char[] {';', '\t'}) {
            int n = count(head, cand);
            if (n > bestCount) {
                best = cand;
                bestCount = n;
            }
        }
        return best;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** 行数估算：只统计头部区间，末尾换行不计为空行。 */
    private static int countRows(Path file) {
        try {
            String text = readHead(file);
            int rows = 0;
            boolean pending = false;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    rows++;
                    pending = false;
                } else {
                    pending = true;
                }
            }
            return pending ? rows + 1 : rows;
        } catch (IOException e) {
            return 0;
        }
    }

    /** 读取头部字节并按 BOM/严格 UTF-8/GBK 顺序嗅探编码（国内 Excel 导出的 csv 常是 GBK）。 */
    private static String readHead(Path file) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file); ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[64 * 1024];
            int n;
            while (buf.size() < MAX_CSV_BYTES && (n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, Math.min(n, MAX_CSV_BYTES - buf.size()));
            }
            bytes = buf.toByteArray();
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return utf8.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    // ---- 渲染小工具 ----

    private static String joinRow(List<String> row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(row.get(i));
        }
        return sb.toString();
    }

    private static String oneLine(String value) {
        String v = value.replace('\n', ' ').replace('\r', ' ').strip();
        return v.length() <= MAX_CELL_CHARS ? v : v.substring(0, MAX_CELL_CHARS) + "…";
    }

    private static String cap(String text) {
        return text.length() <= MAX_OUTPUT_CHARS
                ? text
                : text.substring(0, MAX_OUTPUT_CHARS) + "\n…（输出过长已截断，请缩小 rows）";
    }

    private static String name(Path file) {
        Path n = file.getFileName();
        return n == null ? "" : n.toString();
    }

    /** SAX 回调不便抛受检异常，用它在数据取够时提前终止遍历。 */
    private static final class StopScan extends RuntimeException {
        private StopScan() {
            super(null, null, false, false);
        }
    }
}
