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

package com.gettyio.jwork.agent.ui.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gettyio.jwork.agent.core.chat.ToolCallsCodec;
import com.gettyio.jwork.agent.core.chat.ToolCallsCodec.ToolCallView;
import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.entity.AppSetting;
import com.gettyio.jwork.agent.domain.entity.ChatConversation;
import com.gettyio.jwork.agent.domain.entity.ChatSession;
import com.gettyio.jwork.agent.domain.entity.ModelProviderConfig;
import com.gettyio.jwork.agent.domain.security.SecretStore;
import com.gettyio.jwork.agent.domain.util.TimeFormats;
import com.gettyio.jwork.agent.ui.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 会话与全库导出：单会话导出为可读 Markdown（角色/时间/工具行）或「话题卡片」式独立 HTML
 * （{@link SessionHtmlRenderer}，.html 文件与长图快照共用同一份内容），全库导出为结构化 JSON。
 * <p>纯数据渲染，无 JavaFX 依赖，便于在设置页后台线程调用与后续测试。</p>
 * <p>全库 JSON 导出会抹掉密钥类字段：导出件常被人贴进聊天、工单或网盘，而 {@code settings}
 * 里的值是密文，与随数据目录同放的 {@code secret.key} 配对即可还原——等于连带钥匙一起送人。</p>
 *
 * @author gogym
 */
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    /** 密钥字段在导出件里的替身值：保留「这项配过」这一事实，抹掉可用于认证的内容。 */
    static final String REDACTED = "[redacted]";

    /**
     * 键名（去掉 {@code .}/{@code _} 等分隔符后）含这些片段即按密钥处理，即便值是历史遗留的明文
     * （未经密钥库封存，前缀规则认不出）。必须先去分隔符：真实键名写的是 {@code web.search.api.key}，
     * 按原样匹配 "api_key" 会整类漏掉。
     */
    private static final List<String> SECRET_KEY_HINTS =
            List.of("apikey", "password", "passphrase", "secret", "token");

    private static final DateTimeFormatter TS = TimeFormats.DISPLAY_TIMESTAMP;
    private static final DateTimeFormatter FILE_TS = TimeFormats.FILE_TIMESTAMP;

    private final JworkDatabase db;
    private final SecretStore secrets;
    private final ObjectMapper json;

    public ExportService(JworkDatabase db, SecretStore secrets) {
        this.db = db;
        this.secrets = secrets;
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
            sb.append("- ").append(I18n.t("export.meta.session", "`" + sessionId + "`")).append("\n");
            if (session.getAgentCode() != null) {
                sb.append("- ").append(I18n.t("export.meta.agent", "`" + session.getAgentCode() + "`")).append("\n");
            }
            if (session.getModelCode() != null) {
                sb.append("- ").append(I18n.t("export.meta.model", "`" + session.getModelCode() + "`")).append("\n");
            }
            sb.append("- ").append(I18n.t("export.meta.messageCount", String.valueOf(messages.size())))
                    .append("\n");
        }
        sb.append("- ").append(I18n.t("export.meta.exportedAt", LocalDateTime.now().format(TS)))
                .append("\n\n---\n\n");
        for (ChatConversation c : messages) {
            sb.append(renderMessage(c));
        }
        return sb.toString();
    }

    /** 导出单个会话为独立 HTML 文件（话题卡片式，样式自包含、本地图片内嵌），返回落盘路径。 */
    public Path exportSessionHtml(String sessionId, Path target) {
        return write(target, renderSessionHtml(sessionId));
    }

    /** 渲染单个会话为独立 HTML 文本；.html 导出与长图（PNG 快照）共用同一份内容。 */
    public String renderSessionHtml(String sessionId) {
        ChatSession session = db.sessionDao().findBySessionId(sessionId).orElse(null);
        List<ChatConversation> messages = db.conversationDao().findBySession(sessionId);
        return SessionHtmlRenderer.render(session, messages);
    }

    private String renderMessage(ChatConversation c) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(ExportFormats.roleLabelMd(c.getRole()));
        if (c.getCreatedAt() != null) {
            sb.append(" · ").append(c.getCreatedAt().format(TS));
        }
        sb.append("\n\n");
        String content = c.getContent() == null ? "" : c.getContent().trim();
        sb.append(content.isEmpty() ? "_" + I18n.t("export.body.empty") + "_" : content).append("\n");
        // 工具调用行
        List<ToolCallView> calls = ToolCallsCodec.decode(c.getToolCalls());
        for (ToolCallView call : calls) {
            sb.append("> 🔧 `").append(call.name()).append("`");
            Map<String, Object> input = call.input();
            if (input != null && !input.isEmpty()) {
                sb.append(" ").append(ExportFormats.summarizeInput(input));
            }
            sb.append("\n");
        }
        sb.append("\n");
        return sb.toString();
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
            throw new UncheckedIOException(I18n.t("export.error.pdf", target), e);
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
            throw new UncheckedIOException(I18n.t("export.error.docx", target), e);
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
            out.add(new Line(I18n.t("export.meta.session", sessionId), Kind.META));
            if (session.getAgentCode() != null) {
                out.add(new Line(I18n.t("export.meta.agent", session.getAgentCode()), Kind.META));
            }
            if (session.getModelCode() != null) {
                out.add(new Line(I18n.t("export.meta.model", session.getModelCode()), Kind.META));
            }
            out.add(new Line(I18n.t("export.meta.messageCount", String.valueOf(messages.size())), Kind.META));
        }
        out.add(new Line(I18n.t("export.meta.exportedAt", LocalDateTime.now().format(TS)), Kind.META));
        for (ChatConversation c : messages) {
            StringBuilder head = new StringBuilder(ExportFormats.rolePlain(c.getRole()));
            if (c.getCreatedAt() != null) {
                head.append(" · ").append(c.getCreatedAt().format(TS));
            }
            out.add(new Line(head.toString(), Kind.HEAD));
            String content = c.getContent() == null ? "" : c.getContent().trim();
            out.add(new Line(content.isEmpty() ? I18n.t("export.body.empty") : content, Kind.BODY));
            for (ToolCallView call : ToolCallsCodec.decode(c.getToolCalls())) {
                StringBuilder t = new StringBuilder(I18n.t("export.line.tool", call.name()));
                Map<String, Object> input = call.input();
                if (input != null && !input.isEmpty()) {
                    t.append(' ').append(ExportFormats.summarizeInput(input));
                }
                out.add(new Line(t.toString(), Kind.TOOL));
            }
        }
        return out;
    }

    /** 已加载的中文字体及其底层字体集合（TTC 场景须在 save 前保持打开）。 */
    private record CjkFont(PDFont font, TrueTypeCollection collection) implements AutoCloseable {
        /** 释放 TTC 字体集合（单文件 ttf 无集合可为 null）。 */
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
            throw new UncheckedIOException(I18n.t("export.error.dir", target), e);
        }
    }

    /**
     * 导出全库为 JSON（会话+消息+智能体+任务+技能+模型+设置），返回落盘路径。
     * <p>密钥字段就地抹掉，被抹项列在 {@code redactedSecrets} 里——导出件因此<b>不是</b>可回灌的
     * 备份，模型 Key 与 WebDAV 凭据需在导入后重新录入。</p>
     */
    public Path exportFullJson(Path target) {
        List<AppSetting> settings = db.settingDao().findAll();
        List<ModelProviderConfig> providers = db.modelProviderDao().findAll();
        List<String> redactedSettingKeys = redactSettings(settings);
        List<String> redactedProviders = redactProviderKeys(providers);
        if (!redactedSettingKeys.isEmpty() || !redactedProviders.isEmpty()) {
            log.info("全库导出抹去 {} 项设置密钥、{} 个提供商 API Key: {}",
                    redactedSettingKeys.size(), redactedProviders.size(), redactedSettingKeys);
        }
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("exportedAt", LocalDateTime.now().format(TS));
        dump.put("sessions", db.sessionDao().findAll());
        dump.put("conversations", db.conversationDao().findBySessions(
                db.sessionDao().findAll().stream().map(ChatSession::getSessionId).toList()));
        dump.put("agents", db.agentDao().findAll());
        dump.put("tasks", db.taskDao().findAll());
        dump.put("skills", db.skillDao().findAll());
        dump.put("modelProviders", providers);
        dump.put("models", db.modelConfigDao().findAll());
        dump.put("settings", settings);
        dump.put("redactedSecrets", Map.of("settings", redactedSettingKeys,
                "modelProviders", redactedProviders));
        return write(target, renderJson(dump));
    }

    /** 就地抹掉设置行的密钥值：密文一律抹（配着本机 secret.key 就能解开），明文只抹键名像密钥的。 */
    private List<String> redactSettings(List<AppSetting> settings) {
        List<String> keys = new ArrayList<>();
        for (AppSetting s : settings) {
            String value = s.getSettingValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            if (secrets.canHandle(value) || looksSecret(s.getSettingKey())) {
                s.setSettingValue(REDACTED);
                keys.add(s.getSettingKey());
            }
        }
        return keys;
    }

    /** 就地抹掉各提供商的 API Key。 */
    private static List<String> redactProviderKeys(List<ModelProviderConfig> providers) {
        List<String> codes = new ArrayList<>();
        for (ModelProviderConfig p : providers) {
            if (p.getApiKey() != null && !p.getApiKey().isBlank()) {
                p.setApiKey(REDACTED);
                codes.add(p.getProviderCode());
            }
        }
        return codes;
    }

    private static boolean looksSecret(String key) {
        if (key == null) {
            return false;
        }
        String flat = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SECRET_KEY_HINTS.stream().anyMatch(flat::contains);
    }

    /** 渲染全库 JSON 文本。 */
    public String renderJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(I18n.t("export.error.json"), e);
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
            throw new UncheckedIOException(I18n.t("export.error.write", target), e);
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
