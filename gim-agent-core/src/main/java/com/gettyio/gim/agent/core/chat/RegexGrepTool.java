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

package com.gettyio.gim.agent.core.chat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * grep_files 的本地化替代：正则（不是字面量）、可分页、可大小写与上下文、可只列文件名或计数。
 *
 * <p>为什么要替代：框架内置实现把 pattern 当 {@code Pattern.quote} 后的字面量搜（{@code
 * LocalFilesystem.javaSearch}），描述也照实写着 "literal text pattern"——于是 {@code get.*Name}
 * 这种模型最常用的写法一律搜不到；搜不到就退化成把整个文件读进上下文再肉眼找，正好是最贵的那条路。
 * 另外它只有「逐条匹配」一档，问「哪些文件里有」也会回一堆行。</p>
 *
 * <p>引擎取舍：工作区内逐文件用 JDK 正则扫，不引外部依赖——ripgrep 并未随包分发，个人工作区里
 * 纯 Java 的耗时完全够；换来的是本地与 Docker 沙箱同一份语义（枚举与读取都走 {@link
 * AbstractFilesystem}，路径白名单由它兜）。代价是不吃 {@code .gitignore}，所以自己挡掉
 * {@code node_modules}/{@code .git}/{@code target} 这类噪音目录。</p>
 *
 * <p>注册方式与 {@link OverwritableWriteFileTool} 同：build 后以同名覆盖框架内置，工具名与
 * {@code pattern/path/glob/limit} 入参名不变，保持 {@code readOnly} 因而照旧不进 HITL 确认链。</p>
 *
 * @author gogym
 */
public final class RegexGrepTool {

    private static final Logger log = LoggerFactory.getLogger(RegexGrepTool.class);

    /** 被覆盖的工具名（与框架 FilesystemTool.grepFiles 注册名一致）。 */
    public static final String NAME = "grep_files";

    /** 缺省返回条数上限（与框架一致，免得一次搜出满屏）。 */
    public static final int DEFAULT_LIMIT = 100;

    /** 条数硬上限（与框架一致）。 */
    public static final int MAX_LIMIT = 1_000;

    /** 单文件大小上限：超过就当产物/日志跳过——搜索不是读大文件的入口。 */
    public static final long MAX_FILE_BYTES = 2_000_000L;

    /** 一次搜索最多探明的文件数：先到上限就先给结论，避免整棵目录树被拖慢。 */
    public static final int MAX_FILES_SCANNED = 5_000;

    /** 上下文行数上限（before/after 各自钳制到这里）。 */
    public static final int MAX_CONTEXT_LINES = 20;

    /** 噪音目录：不吃 .gitignore，就把这些常见产物目录挡掉。 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", ".hg", ".idea", ".vscode", "node_modules", "target", "build",
            "dist", "out", "__pycache__", ".venv", "venv", ".tox", ".gradle", ".m2", "logs");

    /** type → 后缀集合（对齐 rg --type 的常见档，模型按习惯写 java/py/json 即可）。 */
    private static final Map<String, List<String>> TYPE_SUFFIXES = Map.ofEntries(
            Map.entry("java", List.of(".java")),
            Map.entry("py", List.of(".py")),
            Map.entry("js", List.of(".js", ".jsx", ".mjs")),
            Map.entry("ts", List.of(".ts", ".tsx")),
            Map.entry("vue", List.of(".vue")),
            Map.entry("json", List.of(".json")),
            Map.entry("yaml", List.of(".yaml", ".yml")),
            Map.entry("md", List.of(".md", ".markdown")),
            Map.entry("sql", List.of(".sql")),
            Map.entry("sh", List.of(".sh", ".bash")),
            Map.entry("html", List.of(".html", ".htm")),
            Map.entry("css", List.of(".css", ".scss", ".less")),
            Map.entry("xml", List.of(".xml", ".svg")),
            Map.entry("csv", List.of(".csv", ".tsv")),
            Map.entry("txt", List.of(".txt")));

    private static final String MODE_CONTENT = "content";
    private static final String MODE_FILES = "files_with_matches";
    private static final String MODE_COUNT = "count";

    private final AbstractFilesystem filesystem;

    public RegexGrepTool(AbstractFilesystem filesystem) {
        this.filesystem = filesystem;
    }

    @Tool(name = NAME, readOnly = true, concurrencySafe = true,
            description = "Search file contents with a REGULAR expression (java.util.regex, so"
                    + " \"get.*Name\" works; escape . ( ) when you want them literally). output_mode:"
                    + " content (default, file:line:text lines), files_with_matches (just the"
                    + " files), count (matches per file). Use this instead of reading whole files"
                    + " when looking for where something is; narrow with path/glob/type. Entries"
                    + " are capped (default " + DEFAULT_LIMIT + ", hard maximum " + MAX_LIMIT
                    + "); page with offset. Files over " + (MAX_FILE_BYTES / 1_000_000) + "MB and"
                    + " binary files are skipped, as are node_modules/.git/target style directories.")
    public String grepFiles(
            RuntimeContext runtimeContext,
            @ToolParam(name = "pattern", description = "Regular expression to search for")
                    String pattern,
            @ToolParam(name = "path", description = "Directory or file to search", required = false)
                    String path,
            @ToolParam(name = "glob", required = false,
                    description = "File name filter (e.g., \"*.java\", \"*Test.java\")") String glob,
            @ToolParam(name = "type", required = false,
                    description = "File type shortcut: java, py, js, ts, vue, json, yaml, md,"
                            + " sql, sh, html, css, xml, csv, txt") String type,
            @ToolParam(name = "output_mode", required = false,
                    description = "content (default) | files_with_matches | count")
                    String outputMode,
            @ToolParam(name = "case_insensitive", required = false,
                    description = "Case-insensitive search (default: false)")
                    Boolean caseInsensitive,
            @ToolParam(name = "context_before", required = false,
                    description = "Leading lines to show per match (default: 0, max "
                            + MAX_CONTEXT_LINES + ")") Integer contextBefore,
            @ToolParam(name = "context_after", required = false,
                    description = "Trailing lines to show per match (default: 0, max "
                            + MAX_CONTEXT_LINES + ")") Integer contextAfter,
            @ToolParam(name = "multiline", required = false,
                    description = "Let the pattern span line breaks (default: false)")
                    Boolean multiline,
            @ToolParam(name = "limit", required = false,
                    description = "Max entries to return (default: " + DEFAULT_LIMIT
                            + "; hard maximum: " + MAX_LIMIT + ")") Integer limit,
            @ToolParam(name = "offset", required = false,
                    description = "Entries to skip, for paging (default: 0)") Integer offset) {
        if (pattern == null || pattern.isEmpty()) {
            return "Error: pattern must not be empty";
        }
        Pattern compiled;
        try {
            compiled = compile(pattern, caseInsensitive, multiline);
        } catch (PatternSyntaxException e) {
            return "Error: invalid regular expression: " + e.getDescription()
                    + " (to search for text literally, escape the special characters)";
        }
        Scan scan = scan(runtimeContext,
                normalize(path == null || path.isBlank() ? "." : path, runtimeContext),
                compiled, glob, type, Boolean.TRUE.equals(multiline),
                clampContext(contextBefore), clampContext(contextAfter));
        if (scan.error != null) {
            return "Error: " + scan.error;
        }
        return render(scan, mode(outputMode),
                limit == null ? DEFAULT_LIMIT : Math.min(Math.max(1, limit), MAX_LIMIT),
                Math.max(0, offset == null ? 0 : offset), glob, type);
    }

    // ===================== 扫描 =====================

    /** 一次搜索的中转结果：命中项 + 跳过计数；跳过要说得清，否则模型把「没搜到」当成「不存在」。 */
    private static final class Scan {
        private final List<Hit> hits = new ArrayList<>();
        private String error;
        private int skippedLarge;
        private int skippedBinary;
        private boolean fileCapReached;
    }

    /** 一条命中：文件、行号、该行正文（多行模式下是折行后的匹配片段）。 */
    private record Hit(String path, int line, String text) {
    }

    private Scan scan(RuntimeContext rc, String root, Pattern pattern, String glob, String type,
                      boolean multiline, int before, int after) {
        Scan scan = new Scan();
        List<FileInfo> candidates = candidates(rc, root, scan);
        int scanned = 0;
        for (FileInfo f : candidates) {
            if (scan.error != null) {
                break;
            }
            if (f.isDirectory() || noise(f.path()) || !selected(f.path(), glob, type)) {
                continue;
            }
            if (f.size() > MAX_FILE_BYTES) {
                scan.skippedLarge++;
                continue;
            }
            if (scanned >= MAX_FILES_SCANNED) {
                scan.fileCapReached = true;
                break;
            }
            scanned++;
            String text = decode(rc, f.path(), scan);
            if (text == null) {
                continue;
            }
            collectHits(scan, f.path(), text, pattern, multiline, before, after);
        }
        return scan;
    }

    /** 候选文件：按目录枚举；path 直接指到文件时退化为单文件扫描（glob 走不出结果）。 */
    private List<FileInfo> candidates(RuntimeContext rc, String root, Scan scan) {
        List<FileInfo> out = new ArrayList<>();
        try {
            GlobResult r = filesystem.glob(rc, "**/*", root);
            if (r.isSuccess() && r.matches() != null) {
                out.addAll(r.matches());
            }
            if (!out.isEmpty() || root.isBlank() || ".".equals(root)) {
                return out;
            }
            byte[] probe = bytes(rc, root);
            if (probe != null) {
                out.add(FileInfo.ofFile(root, probe.length, ""));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            scan.error = e.getMessage() == null ? "path is not readable" : e.getMessage();
        }
        return out;
    }

    private void collectHits(Scan scan, String file, String text, Pattern pattern,
                             boolean multiline, int before, int after) {
        if (multiline) {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                scan.hits.add(new Hit(file, 1 + countBreaks(text, m.start()), flatten(m.group())));
            }
            return;
        }
        String[] lines = splitLines(text);
        for (int i = 0; i < lines.length; i++) {
            if (!pattern.matcher(lines[i]).find()) {
                continue;
            }
            for (int j = Math.max(0, i - before); j <= Math.min(lines.length - 1, i + after); j++) {
                scan.hits.add(new Hit(file, j + 1, lines[j]));
            }
        }
    }

    /** 读取并解码单个文件；二进制或读失败时计数并返回 null。 */
    private String decode(RuntimeContext rc, String file, Scan scan) {
        byte[] bytes = bytes(rc, file);
        if (bytes == null) {
            return null;
        }
        String text = utf8(bytes);
        if (text == null) {
            scan.skippedBinary++;
        }
        return text;
    }

    // ===================== 渲染 =====================

    private String render(Scan scan, String mode, int cap, int skip, String glob, String type) {
        List<String> rows = switch (mode) {
            case MODE_FILES -> distinctFiles(scan);
            case MODE_COUNT -> counts(scan);
            default -> contents(scan);
        };
        StringBuilder out = new StringBuilder();
        if (rows.isEmpty()) {
            out.append("No matches found").append(filterNote(glob, type)).append('.');
        } else {
            int from = Math.min(skip, rows.size());
            int to = Math.min(from + cap, rows.size());
            for (int i = from; i < to; i++) {
                out.append(rows.get(i)).append('\n');
            }
            if (to < rows.size() || scan.fileCapReached) {
                out.append("[Showing ").append(from + 1).append('-').append(to).append(" of ")
                        .append(rows.size()).append(' ')
                        .append(MODE_FILES.equals(mode) || MODE_COUNT.equals(mode)
                                ? "files" : "matches");
                if (scan.fileCapReached) {
                    out.append(" (only the first ").append(MAX_FILES_SCANNED)
                            .append(" candidate files were scanned; narrow the path)");
                }
                out.append(". Continue with offset=").append(to)
                        .append(" or raise limit (hard maximum: ").append(MAX_LIMIT).append(").]");
                out.append('\n');
            }
        }
        return out.append(omissions(scan)).toString();
    }

    private static List<String> contents(Scan scan) {
        List<String> rows = new ArrayList<>(scan.hits.size());
        for (Hit h : scan.hits) {
            rows.add(h.path() + ":" + h.line() + ":" + h.text());
        }
        return rows;
    }

    private static List<String> distinctFiles(Scan scan) {
        Set<String> seen = new LinkedHashSet<>();
        for (Hit h : scan.hits) {
            seen.add(h.path());
        }
        return new ArrayList<>(seen);
    }

    private static List<String> counts(Scan scan) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Hit h : scan.hits) {
            counts.merge(h.path(), 1, Integer::sum);
        }
        List<String> rows = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            rows.add(e.getKey() + ": " + e.getValue());
        }
        return rows;
    }

    /** 漏扫说明：把「真没有」与「被大小/二进制上限挡住」分开讲。 */
    private static String omissions(Scan scan) {
        StringBuilder sb = new StringBuilder();
        if (scan.skippedLarge > 0) {
            sb.append("\n[").append(scan.skippedLarge).append(" file(s) over ")
                    .append(MAX_FILE_BYTES / 1_000_000).append("MB not searched]");
        }
        if (scan.skippedBinary > 0) {
            sb.append("\n[").append(scan.skippedBinary).append(" binary file(s) not searched]");
        }
        return sb.toString();
    }

    private static String filterNote(String glob, String type) {
        StringBuilder sb = new StringBuilder();
        if (glob != null && !glob.isBlank()) {
            sb.append(" for files matching ").append(glob.trim());
        }
        if (type != null && !type.isBlank()) {
            sb.append(sb.isEmpty() ? " of type " : " / type ").append(type.trim());
        }
        return sb.toString();
    }

    // ===================== 判定辅助 =====================

    private static Pattern compile(String pattern, Boolean caseInsensitive, Boolean multiline) {
        int flags = 0;
        if (Boolean.TRUE.equals(caseInsensitive)) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        if (Boolean.TRUE.equals(multiline)) {
            flags |= Pattern.MULTILINE | Pattern.DOTALL;
        }
        return Pattern.compile(pattern, flags);
    }

    private static int clampContext(Integer value) {
        if (value == null || value <= 0) {
            return 0;
        }
        return Math.min(value, MAX_CONTEXT_LINES);
    }

    private static String mode(String requested) {
        String m = requested == null ? MODE_CONTENT : requested.trim().toLowerCase(Locale.ROOT);
        if (m.isEmpty()) {
            return MODE_CONTENT;
        }
        if (m.startsWith("file")) {
            return MODE_FILES;
        }
        if (m.startsWith("count")) {
            return MODE_COUNT;
        }
        return MODE_CONTENT;
    }

    /** 路径里是否含噪音目录段：按分隔符切段比对，免得误伤 mynode_modules 之类的名字。 */
    private static boolean noise(String path) {
        for (String seg : path.split("[/\\\\]")) {
            if (SKIP_DIRS.contains(seg)) {
                return true;
            }
        }
        return false;
    }

    /** type / glob 过滤：type 走后缀表，glob 主要比对末段文件名（与框架 javaSearch 同口径）。 */
    private static boolean selected(String path, String glob, String type) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (type != null && !type.isBlank()) {
            String key = type.trim().toLowerCase(Locale.ROOT);
            List<String> suffixes = TYPE_SUFFIXES.get(key);
            boolean hit = suffixes == null
                    ? lower.endsWith("." + key)
                    : suffixes.stream().anyMatch(lower::endsWith);
            if (!hit) {
                return false;
            }
        }
        return glob == null || glob.isBlank() || matchesGlob(lower, glob.trim().toLowerCase(Locale.ROOT));
    }

    /** 极简 glob 比对：星号、问号与开头的双星通配够用，不为此引第三方依赖。 */
    private static boolean matchesGlob(String lowerPath, String glob) {
        String g = glob.startsWith("**/") ? glob.substring(3) : glob;
        String name = lowerPath.substring(lowerPath.lastIndexOf('/') + 1);
        if (g.contains("/")) {
            return matchesOne(lowerPath, g);
        }
        return matchesOne(name, g) || (g.startsWith("**") && matchesOne(lowerPath, g));
    }

    private static boolean matchesOne(String candidate, String glob) {
        String expr = glob.startsWith("**/") ? glob.substring(3) : glob;
        StringBuilder regex = new StringBuilder(expr.length() * 2);
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> {
                    if ("\\.()[]{}+$|^".indexOf(c) >= 0) {
                        regex.append(Pattern.quote(String.valueOf(c)));
                    } else {
                        regex.append(c);
                    }
                }
            }
        }
        return Pattern.compile(regex.toString()).matcher(candidate).matches();
    }

    /** 去掉文件尾换行造出的假空行，让行号与模型看到的行数对齐。 */
    private static String[] splitLines(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            lines = Arrays.copyOf(lines, lines.length - 1);
        }
        return lines;
    }

    private byte[] bytes(RuntimeContext rc, String target) {
        try {
            List<FileDownloadResponse> rs = filesystem.downloadFiles(rc, List.of(target));
            if (rs.isEmpty() || !rs.get(0).isSuccess() || rs.get(0).content() == null) {
                return null;
            }
            return rs.get(0).content();
        } catch (SecurityException | IllegalArgumentException e) {
            log.debug("grep 读取失败: {}", target, e);
            return null;
        }
    }

    /** 严格 UTF-8 解码；含 NUL 或解不出的一律按二进制返回 null（不把乱码当命中）。 */
    private static String utf8(byte[] bytes) {
        int sniff = Math.min(bytes.length, 8_000);
        for (int i = 0; i < sniff; i++) {
            if (bytes[i] == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static int countBreaks(String text, int index) {
        int n = 0;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** 多行命中折成一行显示，保住 path:line:text 这个模型熟悉的形状。 */
    private static String flatten(String match) {
        String one = match.replace("\r", "").replace("\n", "\\n");
        return one.length() > 300 ? one.substring(0, 300) : one;
    }

    /** 与框架一致的路径归一：模型常按提示里的绝对路径给 path，需剥掉当前模式的工作区前缀。 */
    private static String normalize(String path, RuntimeContext runtimeContext) {
        WorkspacePathNormalizer normalizer =
                runtimeContext == null ? null : runtimeContext.get(WorkspacePathNormalizer.class);
        return normalizer == null ? path : normalizer.normalize(path, runtimeContext);
    }
}
