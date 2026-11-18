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

import com.gettyio.gim.agent.core.doc.TabularInspector;
import com.gettyio.gim.agent.domain.util.FileKinds;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * read_file 的本地化替代：行号真的带上、单次输出有顶、二进制不再 base64 灌进上下文。
 *
 * <p>为什么要替代：框架 {@code FilesystemTool.readFile} 的描述写着 "with line numbers"，实现里
 * 一个行号都没拼（{@code LocalFilesystem.read} 只 sed 出原文），模型据此定位不到「第几行」，
 * 只能整段重写；非文本类型一律 {@code Base64} 全量返回且没有长度上限，读一张图或一份 xlsx
 * 就把几万字符的乱码塞进上下文，既看不懂也删不掉。</p>
 *
 * <p>这里按类型分流：纯文本补 {@code n<TAB>} 前缀并限行数与字符上限、末尾给出续读 offset；
 * pdf/docx 走 {@link FileTextExtractor} 抽正文；表格只回结构概览并把区间取数交给
 * {@code inspect_file}（整表进上下文必然撑爆窗口）；图片在当前模型能看时以多模态块附上，
 * 不能看时只报尺寸；其余二进制报大小与类型、建议用 execute 处理。</p>
 *
 * <p>注册方式与 {@link OverwritableWriteFileTool} 同：build 后以同名覆盖框架内置，工具名与
 * {@code path/offset/limit} 入参名不变；保持 {@code readOnly} 因而照旧不进 HITL 确认链。</p>
 *
 * @author gogym
 */
public final class LineNumberedReadFileTool {

    private static final Logger log = LoggerFactory.getLogger(LineNumberedReadFileTool.class);

    /** 被覆盖的工具名（与框架 FilesystemTool.readFile 注册名一致）。 */
    public static final String NAME = "read_file";

    /** 单次返回的默认行数上限：模型不传 limit 时的缺省，够读一个大函数、撑不爆一整个上下文。 */
    public static final int DEFAULT_LINE_LIMIT = 2_000;

    /** 单次返回的字符上限：行数没超但排版密的文件（压缩过的 json）同样需要截断。 */
    public static final int MAX_OUTPUT_CHARS = 60_000;

    /** 可内联为多模态块的图片字节上限：Base64 后约 5.3MB，再大provider 会直接拒。 */
    public static final int MAX_IMAGE_BYTES = 4_000_000;

    /** 判定「其实是二进制」的前缀嗅探长度：出现 NUL 即不再按文本渲染。 */
    private static final int BINARY_SNIFF_BYTES = 8_000;

    private static final String EMPTY_FILE_NOTICE =
            "System reminder: File exists but has empty contents";

    private final AbstractFilesystem filesystem;
    /** 智能体工作区根（可空）：表格类要把归一后的相对路径还原成本机绝对路径才能流式解析。 */
    private final Path workspace;
    /** 当前模型能否看图（可空视为能）：不能看时绝不发图片块，否则整轮请求被 provider 拒掉。 */
    private final BooleanSupplier visionCapable;

    public LineNumberedReadFileTool(AbstractFilesystem filesystem, Path workspace,
                                    BooleanSupplier visionCapable) {
        this.filesystem = filesystem;
        this.workspace = workspace;
        this.visionCapable = visionCapable;
    }

    @Tool(name = NAME, readOnly = true, concurrencySafe = true,
            description = "Read a file. Text is returned line-numbered (\"offset\"/\"limit\" are"
                    + " 0-based line positions; limit defaults to " + DEFAULT_LINE_LIMIT
                    + " lines and output is capped, so read further with the offset shown at the"
                    + " end). PDF and DOCX return extracted text. Spreadsheets (.xlsx/.csv) return"
                    + " a sheet overview only - use inspect_file for a row range or execute for"
                    + " full statistics. Images are attached only if the current model can see"
                    + " them; any other binary file is described, never dumped.")
    public ToolResultBlock readFile(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "File path to read") String path,
            @ToolParam(name = "offset", required = false,
                    description = "Start line (0-indexed). Default: 0 (from beginning)")
                    Integer offset,
            @ToolParam(name = "limit", required = false,
                    description = "Max lines to return. Default: " + DEFAULT_LINE_LIMIT)
                    Integer limit) {
        if (path == null || path.isBlank()) {
            return ToolResultBlock.text("Error: path must not be empty");
        }
        String target = normalize(path, runtimeContext);
        byte[] bytes;
        try {
            bytes = readBytes(runtimeContext, target);
        } catch (SecurityException | IllegalArgumentException e) {
            log.warn("read_file 失败: {}", target, e);
            return ToolResultBlock.text("Error: " + e.getMessage());
        }
        if (bytes == null) {
            return ToolResultBlock.text("Error: file not found: " + path);
        }
        String name = fileName(target);
        String mime = imageMimeType(name);
        if (mime != null) {
            return readImage(name, mime, bytes);
        }
        if (TabularInspector.isTabular(name)) {
            return ToolResultBlock.text(readTabular(path, target, name));
        }
        if (isDocument(name)) {
            return ToolResultBlock.text(FileTextExtractor.extract(name, null, bytes));
        }
        if (looksBinary(bytes)) {
            return ToolResultBlock.text(describeBinary(path, name, bytes));
        }
        return ToolResultBlock.text(readText(new String(bytes, StandardCharsets.UTF_8),
                offset == null ? 0 : offset, limit == null ? DEFAULT_LINE_LIMIT : limit));
    }

    // ===================== 分流实现 =====================

    /** 文本：真行号 + 行/字符双上限 + 续读位置；行号 1 基，与模型报给用户的「第 N 行」一致。 */
    private static String readText(String content, int offset, int limit) {
        if (content.isBlank()) {
            return EMPTY_FILE_NOTICE;
        }
        String[] lines = content.split("\n", -1);
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            // 文件尾的换行不是空行，去掉免得行号比实际多出一格
            lines = java.util.Arrays.copyOf(lines, lines.length - 1);
        }
        if (offset >= lines.length) {
            return "Error: line offset " + offset + " exceeds file length (" + lines.length
                    + " lines)";
        }
        int from = Math.max(0, offset);
        int count = limit > 0 ? limit : DEFAULT_LINE_LIMIT;
        int to = Math.min(from + count, lines.length);
        StringBuilder out = new StringBuilder();
        int used = 0;
        int shown = from;
        boolean charCapped = false;
        for (int i = from; i < to; i++) {
            String line = (i + 1) + "\t" + lines[i];
            used += line.length() + 1;
            if (used > MAX_OUTPUT_CHARS) {
                charCapped = true;
                break;
            }
            if (shown > from) {
                out.append('\n');
            }
            out.append(line);
            shown = i + 1;
        }
        if (charCapped && out.length() == 0) {
            // 单行就吃掉整个配额（minified js/json）：至少把这一行的开头给出去
            return lines[from].substring(0, Math.min(lines[from].length(), MAX_OUTPUT_CHARS))
                    + "\n[Line " + (from + 1) + " is longer than " + MAX_OUTPUT_CHARS
                    + " chars; process it with execute instead of reading it whole.]";
        }
        if (shown < lines.length) {
            out.append("\n[Showing lines ").append(from + 1).append('-').append(shown)
                    .append(" of ").append(lines.length).append(charCapped
                            ? " (character limit of " + MAX_OUTPUT_CHARS + " reached)" : "")
                    .append(". Continue with offset=").append(shown).append(".]");
        }
        return out.toString();
    }

    /** 表格：只回结构与规模，区间取数交给 inspect_file——整表进上下文必撑爆窗口。 */
    private String readTabular(String path, String target, String name) {
        StringBuilder out = new StringBuilder();
        out.append("Spreadsheet ").append(name).append(" is not read as text.")
                .append(" Use inspect_file(path=\"").append(path)
                .append("\") for the sheet list and header sample, or")
                .append(" inspect_file(path=..., sheet=..., offset=..., rows=...) for a row")
                .append(" range; for anything over a few hundred rows run Python with execute")
                .append(" against this path instead of reading the whole sheet in here.");
        Path local = localPath(target);
        if (local != null) {
            String overview = TabularInspector.overview(local, TabularInspector.SAMPLE_ROWS);
            if (overview != null && !overview.isBlank()) {
                out.append("\n\n").append(overview);
            }
        }
        return out.toString();
    }

    /** 图片：模型能看就附多模态块，不能看就只报类型与大小（Base64 一律不进上下文）。 */
    private ToolResultBlock readImage(String name, String mime, byte[] bytes) {
        String head = "Image file " + name + " (" + mime + ", " + bytes.length + " bytes).";
        if (bytes.length > MAX_IMAGE_BYTES) {
            return ToolResultBlock.text(head + " Too large to attach; shrink it with execute"
                    + " (e.g. PIL thumbnail) and read the result.");
        }
        if (!supportsVision()) {
            return ToolResultBlock.text(head + " The current model cannot receive images, so its"
                    + " pixels are not included. Switch to a vision-capable model to look at it,"
                    + " or verify it programmatically with execute.");
        }
        List<ContentBlock> blocks = new ArrayList<>(2);
        blocks.add(TextBlock.builder().text(head).build());
        blocks.add(ImageBlock.builder()
                .source(new Base64Source(mime, Base64.getEncoder().encodeToString(bytes)))
                .build());
        return ToolResultBlock.of(blocks);
    }

    /** 其余二进制：说清是什么、多大，指向能真正处理它的 execute。 */
    private static String describeBinary(String path, String name, byte[] bytes) {
        return "Binary file " + path + " (" + bytes.length + " bytes, type " + name
                + ") is not shown as text. Read it with execute (a script that parses it) or"
                + " inspect_file if it is a spreadsheet.";
    }

    // ===================== 辅助 =====================

    private boolean supportsVision() {
        BooleanSupplier gate = visionCapable;
        if (gate == null) {
            return true;
        }
        try {
            return gate.getAsBoolean();
        } catch (RuntimeException e) {
            log.debug("视觉能力判定失败，按不支持处理", e);
            return false;
        }
    }

    /** 读取字节；文件不存在返回 null（框架 downloadFiles 对缺失只给 error，这里归一成一个信号）。 */
    private byte[] readBytes(RuntimeContext runtimeContext, String target) {
        List<FileDownloadResponse> rs =
                filesystem.downloadFiles(runtimeContext, List.of(target));
        if (rs.isEmpty()) {
            return null;
        }
        FileDownloadResponse r = rs.get(0);
        if (!r.isSuccess() || r.content() == null) {
            log.debug("read_file 读取失败: {} -> {}", target, r.error());
            return null;
        }
        return r.content();
    }

    /** 归一后的路径还原为本机绝对路径；不在本机（沙箱/远端）则返回 null。 */
    private Path localPath(String target) {
        try {
            Path p = Paths.get(target);
            Path abs = p.isAbsolute() ? p
                    : (workspace == null ? null : workspace.resolve(p).normalize());
            return abs != null && Files.isRegularFile(abs) ? abs : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** 取路径末段文件名：分流只看后缀，不需要解析整条路径。 */
    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** 该后缀是否属于要抽正文的文档（pdf/docx；xlsx 已由表格分支接走）。 */
    private static boolean isDocument(String name) {
        return FileKinds.isBinaryDocument(name);
    }

    /** 图片后缀 → MIME；非图片返回 null。 */
    private static String imageMimeType(String name) {
        return FileKinds.imageMime(name);
    }

    /** 按内容判定二进制：前若干字节里出现 NUL 即不按文本渲染（后缀会说谎，扩展名不可信）。 */
    private static boolean looksBinary(byte[] bytes) {
        int n = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** 与框架一致的路径归一：模型常按提示里的绝对路径回读，需剥掉当前模式的工作区前缀与命名空间。 */
    private static String normalize(String path, RuntimeContext runtimeContext) {
        WorkspacePathNormalizer normalizer =
                runtimeContext == null ? null : runtimeContext.get(WorkspacePathNormalizer.class);
        return normalizer == null ? path : normalizer.normalize(path, runtimeContext);
    }
}
