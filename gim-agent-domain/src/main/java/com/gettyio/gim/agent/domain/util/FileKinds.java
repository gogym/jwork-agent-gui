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

package com.gettyio.gim.agent.domain.util;

import java.util.Locale;

/**
 * 文件扩展名与用途判定单源：可提取文档 / 二进制文档 / 表格 / 图片 / Markdown 各集合只维护一份。
 *
 * @author gogym
 */
public final class FileKinds {

    public static final String PDF = ".pdf";
    public static final String DOCX = ".docx";
    public static final String XLSX = ".xlsx";
    public static final String XLSM = ".xlsm";
    public static final String CSV = ".csv";
    public static final String TSV = ".tsv";
    public static final String TAB = ".tab";
    public static final String TXT = ".txt";
    public static final String MD = ".md";
    public static final String MARKDOWN = ".markdown";
    public static final String PNG = ".png";
    public static final String JPG = ".jpg";
    public static final String JPEG = ".jpeg";
    public static final String GIF = ".gif";
    public static final String BMP = ".bmp";
    public static final String WEBP = ".webp";
    public static final String HTML = ".html";
    public static final String HTM = ".htm";
    public static final String JSON = ".json";
    public static final String LOG = ".log";
    public static final String RST = ".rst";
    public static final String PROPERTIES = ".properties";
    public static final String ZIP = ".zip";
    public static final String MSI = ".msi";

    /** 可提取纯文本的文档（附件/知识库/read_file 同口径）。 */
    public static boolean isExtractableDocument(String name) {
        String n = lower(name);
        return n.endsWith(PDF) || n.endsWith(DOCX) || n.endsWith(XLSX)
                || n.endsWith(TXT) || n.endsWith(MD) || n.endsWith(MARKDOWN)
                || n.endsWith(CSV);
    }

    /** 需走文档解析器（PDF/DOCX）的二进制文档。 */
    public static boolean isBinaryDocument(String name) {
        String n = lower(name);
        return n.endsWith(PDF) || n.endsWith(DOCX);
    }

    /** 可按行探查的表格文件。 */
    public static boolean isTabular(String name) {
        String n = lower(name);
        return n.endsWith(XLSX) || n.endsWith(XLSM)
                || n.endsWith(CSV) || n.endsWith(TSV) || n.endsWith(TAB);
    }

    /** 纯文本表格（csv/tsv/tab）。 */
    public static boolean isTextTabular(String name) {
        String n = lower(name);
        return n.endsWith(CSV) || n.endsWith(TSV) || n.endsWith(TAB);
    }

    public static boolean isMarkdown(String name) {
        String n = lower(name);
        return n.endsWith(MD) || n.endsWith(MARKDOWN);
    }

    public static boolean isImage(String name) {
        return imageMime(name) != null;
    }

    /** 图片后缀 → MIME；非图片返回 null。 */
    public static String imageMime(String name) {
        String n = lower(name);
        if (n.endsWith(PNG)) {
            return HttpMedia.IMAGE_PNG;
        }
        if (n.endsWith(JPG) || n.endsWith(JPEG)) {
            return HttpMedia.IMAGE_JPEG;
        }
        if (n.endsWith(WEBP)) {
            return HttpMedia.IMAGE_WEBP;
        }
        if (n.endsWith(GIF)) {
            return HttpMedia.IMAGE_GIF;
        }
        if (n.endsWith(BMP)) {
            return HttpMedia.IMAGE_BMP;
        }
        return null;
    }

    private static String lower(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private FileKinds() {
    }
}