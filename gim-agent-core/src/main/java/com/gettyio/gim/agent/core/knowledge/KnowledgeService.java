package com.gettyio.gim.agent.core.knowledge;

import com.gettyio.gim.agent.core.chat.FileTextExtractor;
import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 应用级知识库管理（原生 knowledge 文件树路线）：把用户导入的资料抽取为纯文本、落盘到该智能体工作区的
 * {@code knowledge/} 目录，并据此重建 {@code knowledge/KNOWLEDGE.md} 目录索引。
 * <p>Harness 的 WorkspaceContextMiddleware 每轮会把 {@code KNOWLEDGE.md} 与 {@code knowledge/} 文件清单注入
 * 上下文，智能体再按需 {@code read}/{@code grep} 具体文件——因此这里<b>不建向量库</b>，只维护「可检索的纯文本文件
 * + 人读目录」。导入统一转 {@code .md} 纯文本（原件的 pdf/docx/xlsx 由 {@link FileTextExtractor} 抽取），
 * 保证智能体的文件工具能直接读到内容。</p>
 * <p>每份入库文件顶部嵌一行 {@code <!-- jwork:summary=… -->} 摘要标记；重建索引时回读该标记，使 KNOWLEDGE.md
 * 始终与实际文件一致（删除/重排无需手工维护目录行）。</p>
 * <p>知识按智能体隔离：目录由 {@link MultiAgentGateway#workspaceForAgent} 解析，与运行时装配口径一致。</p>
 */
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private static final String KNOWLEDGE_DIR = "knowledge";
    private static final String INDEX_FILE = "KNOWLEDGE.md";
    private static final String SUMMARY_MARK = "<!-- jwork:summary=";
    /** 单份入库文本正文上限（字符）：入库文档可长，但索引回读与注入只需头部。 */
    private static final int SUMMARY_CHARS = 120;

    private final GimDatabase db;
    private final MultiAgentGateway gateway;
    /** 向量索引器；可为 {@code null}（未装配时纯文件树路线，行为与旧版一致）。 */
    private final EmbeddingIndexService embeddingIndexer;

    public KnowledgeService(GimDatabase db, MultiAgentGateway gateway) {
        this(db, gateway, null);
    }

    public KnowledgeService(GimDatabase db, MultiAgentGateway gateway, EmbeddingIndexService embeddingIndexer) {
        this.db = db;
        this.gateway = gateway;
        this.embeddingIndexer = embeddingIndexer;
    }

    /** 向量索引器（供知识页重建索引/读索引状态）；未装配时为 {@code null}。 */
    public EmbeddingIndexService embeddingIndexer() {
        return embeddingIndexer;
    }

    /** 知识文件元信息：展示名、摘要、字节数、最后修改时间（epoch ms）。 */
    public record DocInfo(String name, String summary, long sizeBytes, long lastModifiedMs) {
    }

    /** 全部智能体定义（供知识页选择器）。 */
    public List<AgentDefinition> listAgents() {
        return db.agentDao().findAll();
    }

    /** 该智能体的 knowledge/ 目录（不存在则创建）。 */
    public Path knowledgeDirFor(String agentCode) {
        Path dir = gateway.workspaceForAgent(agentCode).resolve(KNOWLEDGE_DIR);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("创建知识目录失败", e);
        }
        return dir;
    }

    /** 列出已入库知识文件（除 KNOWLEDGE.md 外），按名升序。 */
    public List<DocInfo> listDocs(String agentCode) {
        Path dir = knowledgeDirFor(agentCode);
        List<DocInfo> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> !INDEX_FILE.equals(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(p -> out.add(toInfo(p)));
        } catch (IOException e) {
            log.warn("列出知识文件失败: {}", dir, e);
        }
        return out;
    }

    /** 读取 KNOWLEDGE.md 目录索引；不存在则生成初始骨架。 */
    public String readIndex(String agentCode) {
        Path index = knowledgeDirFor(agentCode).resolve(INDEX_FILE);
        try {
            if (Files.exists(index)) {
                return Files.readString(index, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("读取 KNOWLEDGE.md 失败: {}", index, e);
        }
        return initialIndex();
    }

    /**
     * 导入一份资料：抽取纯文本→写入 {@code knowledge/<去重基名>.md}→重建索引。
     *
     * @param summary 一句话摘要；留空则自动取正文首个有效行
     * @return 入库后的文件名（.md）
     * @throws IllegalArgumentException 文件类型不支持或正文抽取为空
     */
    public String importDocument(String agentCode, String fileName, String mime, byte[] bytes, String summary) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        if (!FileTextExtractor.supports(fileName, mime)) {
            throw new IllegalArgumentException("暂不支持该文件类型（可抽取 pdf/docx/xlsx/txt/md/markdown/csv）");
        }
        String text = FileTextExtractor.extract(fileName, mime, bytes);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("未能从该文件抽取到文本内容");
        }
        String base = sanitizeBase(fileName);
        Path dir = knowledgeDirFor(agentCode);
        Path target = uniquePath(dir, base);
        String trimmedSummary = summary == null ? "" : summary.replaceAll("\\s+", " ").trim();
        if (trimmedSummary.isEmpty()) {
            trimmedSummary = deriveSummary(text);
        }
        String content = buildDoc(fileName, trimmedSummary, text);
        try {
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入知识文件失败", e);
        }
        rebuildIndex(agentCode);
        indexVectors(agentCode, target, text);
        log.info("知识入库: agent={}, file={}, summaryLen={}", agentCode, target.getFileName(), trimmedSummary.length());
        return target.getFileName().toString();
    }

    /** 导入/重建后向量索引：embedding 未配置或调用失败仅记日志，不阻断文件树入库。 */
    private void indexVectors(String agentCode, Path docFile, String text) {
        if (embeddingIndexer == null) {
            return;
        }
        String name = docFile.getFileName().toString();
        try {
            int chunks = embeddingIndexer.indexDocument(agentCode, name, text);
            if (chunks < 0) {
                log.info("embedding 未配置，跳过向量索引: agent={}, doc={}", agentCode, name);
            }
        } catch (RuntimeException e) {
            log.warn("向量索引失败（文件树路线不受影响）: agent={}, doc={}", agentCode, name, e);
        }
    }

    /** 从磁盘路径导入（读取字节后转交 {@link #importDocument}）。 */
    public String importFromPath(String agentCode, Path src, String summary) {
        try {
            byte[] bytes = Files.readAllBytes(src);
            return importDocument(agentCode, src.getFileName().toString(), guessMime(src.getFileName().toString()),
                    bytes, summary);
        } catch (IOException e) {
            throw new UncheckedIOException("读取源文件失败", e);
        }
    }

    /** 删除某份知识文件并重建索引；文件名校验防目录穿越。 */
    public void deleteDoc(String agentCode, String name) {
        Path file = safeDocFile(agentCode, name);
        if (file == null) {
            return;
        }
        try {
            if (Files.deleteIfExists(file)) {
                rebuildIndex(agentCode);
                if (embeddingIndexer != null) {
                    embeddingIndexer.removeDocument(agentCode, name);
                }
                log.info("删除知识文件: agent={}, file={}", agentCode, name);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("删除知识文件失败", e);
        }
    }

    /** 读取某份知识文件的完整内容（供 UI 预览）。 */
    public String readDoc(String agentCode, String name) {
        Path file = safeDocFile(agentCode, name);
        if (file == null) {
            return "";
        }
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            log.warn("读取知识文件失败: {}", file, e);
            return "";
        }
    }

    /** 依当前 knowledge/ 文件重建 KNOWLEDGE.md：头部说明 + 逐条「文件名 — 摘要」。 */
    public void rebuildIndex(String agentCode) {
        Path dir = knowledgeDirFor(agentCode);
        StringBuilder sb = new StringBuilder(initialIndex());
        for (DocInfo info : listDocs(agentCode)) {
            sb.append("- ").append(info.name());
            if (info.summary() != null && !info.summary().isBlank()) {
                sb.append(" — ").append(info.summary());
            }
            sb.append('\n');
        }
        try {
            Files.writeString(dir.resolve(INDEX_FILE), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("重建 KNOWLEDGE.md 失败: {}", dir, e);
        }
    }

    // ---- 内部 ----

    private DocInfo toInfo(Path p) {
        String summary = "";
        long size = 0;
        long modified = 0;
        try {
            size = Files.size(p);
            modified = Files.getLastModifiedTime(p).toMillis();
            summary = readSummaryMarker(p);
        } catch (IOException e) {
            log.warn("读取知识文件元信息失败: {}", p, e);
        }
        return new DocInfo(p.getFileName().toString(), summary, size, modified);
    }

    /** 回读文件头部的摘要标记；缺失则回退首个正文有效行。 */
    private static String readSummaryMarker(Path p) {
        try {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            int head = Math.min(lines.size(), 20);
            for (int i = 0; i < head; i++) {
                int at = lines.get(i).indexOf(SUMMARY_MARK);
                if (at >= 0) {
                    String s = lines.get(i).substring(at + SUMMARY_MARK.length());
                    int end = s.indexOf("-->");
                    return end >= 0 ? s.substring(0, end).trim() : s.trim();
                }
            }
        } catch (IOException ignored) {
            // 回退空摘要
        }
        return "";
    }

    private static String buildDoc(String originalName, String summary, String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(originalName == null ? "知识资料" : originalName).append('\n');
        sb.append(SUMMARY_MARK).append(summary).append(" -->\n\n");
        sb.append(text.strip()).append('\n');
        return sb.toString();
    }

    private static String initialIndex() {
        return """
                # 知识库目录

                本目录（knowledge/）存放可长期参考的资料；智能体按需读取以下文件获取细节。

                ## 文件索引
                """;
    }

    /** 从抽取文本取首个有效行作为摘要（压空白、截断）。 */
    private static String deriveSummary(String text) {
        for (String line : text.split("\\R")) {
            String t = line.replaceAll("[#>*\\-\\s]+", " ").trim();
            if (t.length() >= 2) {
                return t.length() > SUMMARY_CHARS ? t.substring(0, SUMMARY_CHARS) + "…" : t;
            }
        }
        return "";
    }

    /** 保留字母/数字/CJK，其余折叠为连字符，去首尾；空则回退 knowledge。 */
    private static String sanitizeBase(String fileName) {
        String name = fileName == null ? "" : fileName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.deleteCharAt(sb.length() - 1);
        }
        String base = sb.toString().trim();
        return base.isEmpty() ? "knowledge" : base;
    }

    /** 在目录内为基名找一个不冲突的 {@code <base>.md} 路径（存在则追加 -1、-2…）。 */
    private static Path uniquePath(Path dir, String base) {
        Path candidate = dir.resolve(base + ".md");
        int n = 1;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(base + "-" + n + ".md");
            n++;
        }
        return candidate;
    }

    /** 仅接受 knowledge/ 下的裸文件（排除索引文件本身），非法名拒绝。 */
    private Path safeDocFile(String agentCode, String name) {
        if (name == null || name.isBlank() || INDEX_FILE.equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
            return null;
        }
        return knowledgeDirFor(agentCode).resolve(name);
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (n.endsWith(".csv")) {
            return "text/csv";
        }
        if (n.endsWith(".md") || n.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (n.endsWith(".txt")) {
            return "text/plain";
        }
        return null;
    }
}
