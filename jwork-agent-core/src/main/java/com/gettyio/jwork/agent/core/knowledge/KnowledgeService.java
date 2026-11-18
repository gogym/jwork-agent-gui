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

package com.gettyio.jwork.agent.core.knowledge;

import com.gettyio.jwork.agent.core.chat.FileTextExtractor;
import com.gettyio.jwork.agent.core.chat.MultiAgentGateway;
import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.entity.AgentDefinition;
import com.gettyio.jwork.agent.domain.util.FileKinds;
import com.gettyio.jwork.agent.domain.util.HttpMedia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 应用级知识库管理（原生 knowledge 文件树路线）：把用户导入的资料（本地文件 / 网页 URL）抽取为纯文本、
 * 落盘到该智能体工作区的 {@code knowledge/} 目录，并据此重建 {@code knowledge/KNOWLEDGE.md} 目录索引。
 * <p>Harness 的 WorkspaceContextMiddleware 每轮会把 {@code KNOWLEDGE.md} 与 {@code knowledge/} 文件清单注入
 * 上下文，智能体再按需 {@code read}/{@code grep} 具体文件——因此这里<b>不建向量库</b>，只维护「可检索的纯文本文件
 * + 人读目录」。导入统一转 {@code .md} 纯文本（原件的 pdf/docx/xlsx 由 {@link FileTextExtractor} 抽取），
 * 保证智能体的文件工具能直接读到内容。</p>
 * <p>每份入库文件顶部嵌一行 {@code <!-- jwork:summary=… -->} 摘要标记；重建索引时回读该标记，使 KNOWLEDGE.md
 * 始终与实际文件一致（删除/重排无需手工维护目录行）。</p>
 * <p>知识按智能体隔离：目录由 {@link MultiAgentGateway#workspaceForAgent} 解析，与运行时装配口径一致。</p>
 *
 * @author gogym
 */
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private static final String KNOWLEDGE_DIR = "knowledge";
    private static final String INDEX_FILE = "KNOWLEDGE.md";
    private static final String SUMMARY_MARK = "<!-- jwork:summary=";
    /** 单份入库文本正文上限（字符）：入库文档可长，但索引回读与注入只需头部。 */
    private static final int SUMMARY_CHARS = 120;
    /** URL 导入正文截断上限（字符）：网页可能超长，防单页撑爆索引与上下文。 */
    private static final int MAX_URL_TEXT_CHARS = 200_000;
    /** URL 抓取的共享 HTTP 客户端（跟随重定向；单请求超时在请求侧设置）。 */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final JworkDatabase db;
    private final MultiAgentGateway gateway;
    /** 向量索引器；可为 {@code null}（未装配时纯文件树路线，行为与旧版一致）。 */
    private final EmbeddingIndexService embeddingIndexer;

    public KnowledgeService(JworkDatabase db, MultiAgentGateway gateway) {
        this(db, gateway, null);
    }

    public KnowledgeService(JworkDatabase db, MultiAgentGateway gateway, EmbeddingIndexService embeddingIndexer) {
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
        indexVectors(agentCode, target, content);
        log.info("知识入库: agent={}, file={}, summaryLen={}", agentCode, target.getFileName(), trimmedSummary.length());
        return target.getFileName().toString();
    }

    /**
     * 从 URL 导入网页资料：抓取 → 去标签转纯文本 → 走与文件导入一致的链路
     * （命名清洗、摘要派生、落盘 .md、重建索引、向量索引）。
     *
     * @param summary 一句话摘要；留空则自动取正文首个有效行
     * @return 入库后的文件名（.md）
     * @throws IllegalArgumentException 非 http/https 地址、抓取非 2xx 或正文抽取为空
     */
    public String importFromUrl(String agentCode, String url, String summary) {
        String target = url == null ? "" : url.trim();
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            throw new IllegalArgumentException("仅支持 http/https 地址：" + target);
        }
        String text = htmlToText(fetch(target));
        if (text.isBlank()) {
            throw new IllegalArgumentException("未能从该网页抽取到正文内容（可能是纯脚本渲染页面）");
        }
        if (text.length() > MAX_URL_TEXT_CHARS) {
            text = text.substring(0, MAX_URL_TEXT_CHARS);
        }
        Path dir = knowledgeDirFor(agentCode);
        Path file = uniquePath(dir, sanitizeBase(urlBaseName(target)));
        String trimmedSummary = summary == null ? "" : summary.replaceAll("\\s+", " ").trim();
        if (trimmedSummary.isEmpty()) {
            trimmedSummary = deriveSummary(text);
        }
        String content = buildDoc(target, trimmedSummary, text);
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入知识文件失败", e);
        }
        rebuildIndex(agentCode);
        indexVectors(agentCode, file, content);
        log.info("网页入库: agent={}, url={}, file={}", agentCode, target, file.getFileName());
        return file.getFileName().toString();
    }

    /** 导入/重建后向量索引：索引文本取「落盘全文」口径（与重建索引 readDoc 一致，保证增量指纹可比）；embedding 未配置或调用失败仅记日志，不阻断文件树入库。 */
    private void indexVectors(String agentCode, Path docFile, String docContent) {
        if (embeddingIndexer == null) {
            return;
        }
        String name = docFile.getFileName().toString();
        try {
            int chunks = embeddingIndexer.indexDocument(agentCode, name, docContent);
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
        if (n.endsWith(FileKinds.PDF)) {
            return HttpMedia.APPLICATION_PDF;
        }
        if (n.endsWith(FileKinds.CSV)) {
            return HttpMedia.TEXT_CSV;
        }
        if (n.endsWith(FileKinds.MD) || n.endsWith(FileKinds.MARKDOWN)) {
            return HttpMedia.TEXT_MARKDOWN;
        }
        if (n.endsWith(FileKinds.TXT)) {
            return HttpMedia.TEXT_PLAIN;
        }
        return null;
    }

    // ---- URL 导入 ----

    /** 抓取网页文本（非 2xx / IO 失败 / 中断均以明确异常外抛，由 UI 层呈现）。 */
    private static String fetch(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的 URL：" + url, e);
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Jwork Desktop)")
                    .header(HttpMedia.ACCEPT, "text/html,application/xhtml+xml,text/plain")
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("抓取失败：HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("抓取被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("抓取失败：" + e.getMessage(), e);
        }
    }

    /**
     * 粗略 HTML→文本：去 script/style 等、边标签转行、剥标签、解码常见实体、折叠空白。
     * 与 web_fetch 工具同口径的轻量实现（core 不依赖 tools 模块，此处独立维护）。
     */
    private static String htmlToText(String html) {
        if (html == null) {
            return "";
        }
        String s = html
                .replaceAll("(?is)<(script|style|noscript|svg|template)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)</(p|div|li|h[1-6]|tr|section|article)>", "\n")
                .replaceAll("(?is)<[^>]+>", " ");
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /** URL 基名：主机名（点折为连字符，避免与扩展名裁剪混淆）+ 路径；解析失败回退 url。 */
    private static String urlBaseName(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost() == null ? "url" : u.getHost().replace('.', '-');
            String path = u.getPath() == null ? "" : u.getPath();
            return host + path;
        } catch (IllegalArgumentException e) {
            return "url";
        }
    }
}
