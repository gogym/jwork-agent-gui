package com.gettyio.gim.agent.ui.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gettyio.gim.agent.core.chat.ToolCallsCodec;
import com.gettyio.gim.agent.core.chat.ToolCallsCodec.ToolCallView;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.entity.ChatSession;

import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 会话与全库导出：单会话导出为可读 Markdown（角色/时间/工具行），全库导出为结构化 JSON。
 * <p>纯数据渲染，无 JavaFX 依赖，便于在设置页后台线程调用与后续测试。</p>
 */
public class ExportService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final GimDatabase db;
    private final ObjectMapper json;

    public ExportService(GimDatabase db) {
        this.db = db;
        this.json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** 导出单个会话为 Markdown 文件，返回落盘路径。 */
    public Path exportSessionMarkdown(String sessionId, Path target) {
        return write(target, renderSessionMarkdown(sessionId));
    }

    /** 渲染单个会话为 Markdown 文本。 */
    public String renderSessionMarkdown(String sessionId) {
        ChatSession session = db.sessionDao().findBySessionId(sessionId).orElse(null);
        List<ChatConversation> messages = db.conversationDao().findBySession(sessionId);
        StringBuilder sb = new StringBuilder();
        String title = session == null || session.getTitle() == null || session.getTitle().isBlank()
                ? sessionId : session.getTitle().trim();
        sb.append("# ").append(title).append("\n\n");
        if (session != null) {
            sb.append("- 会话：`").append(sessionId).append("`\n");
            if (session.getAgentCode() != null) {
                sb.append("- 智能体：`").append(session.getAgentCode()).append("`\n");
            }
            if (session.getModelCode() != null) {
                sb.append("- 模型：`").append(session.getModelCode()).append("`\n");
            }
            sb.append("- 消息数：").append(messages.size()).append("\n");
        }
        sb.append("- 导出时间：").append(LocalDateTime.now().format(TS)).append("\n\n---\n\n");
        for (ChatConversation c : messages) {
            sb.append(renderMessage(c));
        }
        return sb.toString();
    }

    private String renderMessage(ChatConversation c) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(roleLabel(c.getRole()));
        if (c.getCreatedAt() != null) {
            sb.append(" · ").append(c.getCreatedAt().format(TS));
        }
        sb.append("\n\n");
        String content = c.getContent() == null ? "" : c.getContent().trim();
        sb.append(content.isEmpty() ? "_（空）_" : content).append("\n");
        // 工具调用行
        List<ToolCallView> calls = ToolCallsCodec.decode(c.getToolCalls());
        for (ToolCallView call : calls) {
            sb.append("> 🔧 `").append(call.name()).append("`");
            Map<String, Object> input = call.input();
            if (input != null && !input.isEmpty()) {
                sb.append(" ").append(summarizeInput(input));
            }
            sb.append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private static String summarizeInput(Map<String, Object> input) {
        StringBuilder sb = new StringBuilder();
        input.forEach((k, v) -> {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            String s = String.valueOf(v);
            if (s.length() > 80) {
                s = s.substring(0, 77) + "...";
            }
            sb.append(k).append("=").append(s);
        });
        return "(" + sb + ")";
    }

    private static String roleLabel(String role) {
        if (role == null) {
            return "消息";
        }
        switch (role.toUpperCase(Locale.ROOT)) {
            case "USER":
                return "🧑 用户";
            case "ASSISTANT":
                return "🤖 助手";
            case "SYSTEM":
                return "⚙️ 系统";
            case "TOOL":
                return "🔧 工具";
            default:
                return role;
        }
    }

    /** 导出单个会话为 PDF（pdfbox 3.x）：优先加载系统中文字体，缺失回退 Helvetica 并清洗非 Latin1 字符。 */
    public Path exportSessionPdf(String sessionId, Path target) {
        List<Line> lines = buildExportLines(sessionId);
        ensureParent(target);
        try (PDDocument doc = new PDDocument()) {
            CjkFont cjk = loadCjkFont(doc);
            PDFont base = cjk != null ? cjk.font : new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = cjk != null ? cjk.font : new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            boolean cjkMode = cjk != null;

            float margin = 42f;
            float pageW = PDRectangle.A4.getWidth();
            float pageH = PDRectangle.A4.getHeight();
            float contentW = pageW - 2 * margin;

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = pageH - margin;
            try {
                for (Line line : lines) {
                    float size = switch (line.kind()) {
                        case TITLE -> 16f;
                        case HEAD -> 12f;
                        case TOOL -> 9f;
                        case META -> 10f;
                        case BODY -> 11f;
                    };
                    PDFont font = switch (line.kind()) {
                        case TITLE, HEAD -> bold;
                        default -> base;
                    };
                    float leading = size * 1.35f;
                    List<String> wrapped = wrap(cleanForPdf(line.text(), cjkMode), font, size, contentW);
                    for (String seg : wrapped) {
                        if (y - leading < margin) {
                            cs.close();
                            page = new PDPage(PDRectangle.A4);
                            doc.addPage(page);
                            cs = new PDPageContentStream(doc, page);
                            y = pageH - margin;
                        }
                        cs.beginText();
                        cs.setFont(font, size);
                        cs.newLineAtOffset(margin, y);
                        cs.showText(seg);
                        cs.endText();
                        y -= leading;
                    }
                    y += switch (line.kind()) {
                        case TITLE, HEAD -> 4f;
                        default -> 1f;
                    };
                }
            } finally {
                cs.close();
                if (cjk != null) {
                    cjk.close();
                }
            }
            doc.save(target.toFile());
            return target.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException("导出 PDF 失败: " + target, e);
        }
    }

    /** 导出单个会话为 DOCX（poi 5.x XWPF）：标题 + 元信息 + 逐条消息（角色/时间/正文/工具）。 */
    public Path exportSessionDocx(String sessionId, Path target) {
        List<Line> lines = buildExportLines(sessionId);
        ensureParent(target);
        try (XWPFDocument doc = new XWPFDocument(); java.io.OutputStream out = Files.newOutputStream(target)) {
            for (Line line : lines) {
                XWPFParagraph p = doc.createParagraph();
                XWPFRun run = p.createRun();
                run.setText(line.text());
                switch (line.kind()) {
                    case TITLE -> {
                        run.setBold(true);
                        run.setFontSize(18);
                    }
                    case HEAD -> {
                        run.setBold(true);
                        run.setFontSize(12);
                        run.setColor("3B5BDB");
                    }
                    case TOOL -> {
                        run.setFontSize(9);
                        run.setColor("868E96");
                    }
                    case META -> run.setFontSize(10);
                    case BODY -> run.setFontSize(11);
                }
            }
            doc.write(out);
            return target.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException("导出 DOCX 失败: " + target, e);
        }
    }

    // ---- 导出内容结构化（PDF/DOCX 共用） ----

    private enum Kind { TITLE, META, HEAD, BODY, TOOL }

    private record Line(String text, Kind kind) {
    }

    /** 把会话渲染为结构化行序列：标题、元信息、逐条消息（角色+时间 / 正文 / 工具行）。 */
    private List<Line> buildExportLines(String sessionId) {
        ChatSession session = db.sessionDao().findBySessionId(sessionId).orElse(null);
        List<ChatConversation> messages = db.conversationDao().findBySession(sessionId);
        List<Line> out = new ArrayList<>();
        String title = session == null || session.getTitle() == null || session.getTitle().isBlank()
                ? sessionId : session.getTitle().trim();
        out.add(new Line(title, Kind.TITLE));
        if (session != null) {
            out.add(new Line("会话：" + sessionId, Kind.META));
            if (session.getAgentCode() != null) {
                out.add(new Line("智能体：" + session.getAgentCode(), Kind.META));
            }
            if (session.getModelCode() != null) {
                out.add(new Line("模型：" + session.getModelCode(), Kind.META));
            }
            out.add(new Line("消息数：" + messages.size(), Kind.META));
        }
        out.add(new Line("导出时间：" + LocalDateTime.now().format(TS), Kind.META));
        for (ChatConversation c : messages) {
            StringBuilder head = new StringBuilder(rolePlain(c.getRole()));
            if (c.getCreatedAt() != null) {
                head.append(" · ").append(c.getCreatedAt().format(TS));
            }
            out.add(new Line(head.toString(), Kind.HEAD));
            String content = c.getContent() == null ? "" : c.getContent().trim();
            out.add(new Line(content.isEmpty() ? "（空）" : content, Kind.BODY));
            for (ToolCallView call : ToolCallsCodec.decode(c.getToolCalls())) {
                StringBuilder t = new StringBuilder("工具 · ").append(call.name());
                Map<String, Object> input = call.input();
                if (input != null && !input.isEmpty()) {
                    t.append(' ').append(summarizeInput(input));
                }
                out.add(new Line(t.toString(), Kind.TOOL));
            }
        }
        return out;
    }

    private static String rolePlain(String role) {
        if (role == null) {
            return "消息";
        }
        return switch (role.toUpperCase(Locale.ROOT)) {
            case "USER" -> "用户";
            case "ASSISTANT" -> "助手";
            case "SYSTEM" -> "系统";
            case "TOOL" -> "工具";
            default -> role;
        };
    }

    /** 已加载的中文字体及其底层字体集合（TTC 场景须在 save 前保持打开）。 */
    private record CjkFont(PDFont font, TrueTypeCollection collection) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (collection != null) {
                collection.close();
            }
        }
    }

    /** 尽力从 Windows 字体目录加载一款 CJK 字体：单文件 ttf 优先，其次 ttc 集合；全失败返回 null。 */
    private static CjkFont loadCjkFont(PDDocument doc) {
        String windir = System.getenv("WINDIR");
        if (windir == null || windir.isBlank()) {
            windir = "C:\\Windows";
        }
        Path fonts = Path.of(windir, "Fonts");
        // 单文件 ttf：PDType0Font.load(doc, file) 自管理资源，无生命周期问题
        for (String name : new String[]{"simhei.ttf", "NotoSansSC-Regular.ttf", "Deng.ttf"}) {
            File f = fonts.resolve(name).toFile();
            if (f.isFile()) {
                try {
                    return new CjkFont(PDType0Font.load(doc, f), null);
                } catch (IOException | RuntimeException ignore) {
                    // 继续尝试下一个
                }
            }
        }
        // ttc 集合：加载后须保持集合打开直到文档保存，交由 CjkFont.close 释放
        for (String name : new String[]{"msyh.ttc", "simsun.ttc", "msjh.ttc", "simfang.ttf", "simkai.ttf"}) {
            File f = fonts.resolve(name).toFile();
            if (!f.isFile()) {
                continue;
            }
            try {
                if (name.endsWith(".ttf")) {
                    return new CjkFont(PDType0Font.load(doc, f), null);
                }
                TrueTypeCollection col = new TrueTypeCollection(f);
                TrueTypeFont[] holder = new TrueTypeFont[1];
                col.processAllFonts(ttf -> {
                    if (holder[0] == null) {
                        holder[0] = ttf;
                    }
                });
                if (holder[0] != null) {
                    return new CjkFont(PDType0Font.load(doc, holder[0], true), col);
                }
                col.close();
            } catch (IOException | RuntimeException ignore) {
                // 继续尝试下一个
            }
        }
        return null;
    }

    /** 清洗 PDF 文本：丢弃增补平面（emoji 等）；非 CJK 模式（Helvetica）额外把 >0xFF 字符替换为 '?'。 */
    private static String cleanForPdf(String s, boolean cjk) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp > 0xFFFF) {
                return;
            }
            if (!cjk && cp > 0xFF) {
                sb.append('?');
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    /** 按字符宽度贪心折行：CJK 逐字断开，拉丁词优先在空格处断行。 */
    private static List<String> wrap(String text, PDFont font, float size, float maxWidth) {
        List<String> res = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            if (raw.isEmpty()) {
                res.add("");
                continue;
            }
            int len = raw.length();
            int start = 0;
            while (start < len) {
                int end = start;
                float w = 0f;
                int lastSpace = -1;
                while (end < len) {
                    int cp = raw.codePointAt(end);
                    int n = Character.charCount(cp);
                    String ch = raw.substring(end, end + n);
                    float cw;
                    try {
                        cw = font.getStringWidth(ch) / 1000f * size;
                    } catch (IOException | RuntimeException e) {
                        cw = size * 0.5f;
                    }
                    if (w + cw > maxWidth && end > start) {
                        break;
                    }
                    w += cw;
                    if (cp == ' ') {
                        lastSpace = end;
                    }
                    end += n;
                }
                if (end >= len) {
                    res.add(raw.substring(start).stripTrailing());
                    break;
                }
                int cut = (lastSpace > start) ? lastSpace : end;
                res.add(raw.substring(start, cut).stripTrailing());
                start = (lastSpace > start) ? lastSpace + 1 : end;
            }
        }
        return res;
    }

    private static void ensureParent(Path target) {
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("创建导出目录失败: " + target, e);
        }
    }

    /** 导出全库为 JSON（会话+消息+智能体+任务+技能+模型+设置），返回落盘路径。 */
    public Path exportFullJson(Path target) {
        Map<String, Object> dump = Map.of(
                "exportedAt", LocalDateTime.now().format(TS),
                "sessions", db.sessionDao().findAll(),
                "conversations", db.conversationDao().findBySessions(
                        db.sessionDao().findAll().stream().map(ChatSession::getSessionId).toList()),
                "agents", db.agentDao().findAll(),
                "tasks", db.taskDao().findAll(),
                "skills", db.skillDao().findAll(),
                "modelProviders", db.modelProviderDao().findAll(),
                "models", db.modelConfigDao().findAll(),
                "settings", db.settingDao().findAll());
        return write(target, renderJson(dump));
    }

    /** 渲染全库 JSON 文本。 */
    public String renderJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("序列化导出内容失败", e);
        }
    }

    /** 建议的导出文件名（清洗会话标题里的非法字符）。 */
    public String suggestedFileName(String base, String ext) {
        String cleaned = base == null ? "export" : base.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (cleaned.isEmpty()) {
            cleaned = "export";
        }
        if (cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 60);
        }
        return cleaned + "-" + LocalDateTime.now().format(FILE_TS) + ext;
    }

    private static Path write(Path target, String content) {
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return target.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException("写入导出文件失败: " + target, e);
        }
    }

    /** 会话是否存在可导出的消息。 */
    public Optional<ChatSession> findSession(String sessionId) {
        return db.sessionDao().findBySessionId(sessionId);
    }

    /** 全部会话（导出选择列表用），按活跃时间倒序。 */
    public List<ChatSession> findSessions() {
        return db.sessionDao().findAll();
    }
}
