package com.gettyio.gim.agent.ui.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.core.chat.ImageAttachment;
import com.gettyio.gim.agent.core.chat.StoredAttachment;
import com.gettyio.gim.agent.core.chat.ToolCallRecordCodec;
import com.gettyio.gim.agent.ui.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天消息 → WebView {@code chat.js} 侧 JSON 载荷的构建器：把 {@link StoredAttachment} 图片读成
 * {@code data:} URI、把助手 Markdown 里的本地图片引用内联为 {@code data:} URI、把工具调用列解码为结构化记录，
 * 最终序列化为合法 JSON 数组交 {@code ChatAPI.renderHistory/prependOlder}。
 * <p>Java 只推原始文本与结构化数据，Markdown→HTML 由前端 marked+DOMPurify 负责。图片内联失败/超限则丢弃该图，
 * 绝不因一张坏图阻断整页渲染。</p>
 */
public final class ChatMessagePayload {

    private static final Logger log = LoggerFactory.getLogger(ChatMessagePayload.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** 单张内联图片字节上限（8MB）：与附件条口径一致，超限丢弃避免撑爆脚本字符串。 */
    private static final long MAX_EMBED_BYTES = 8L * 1024 * 1024;
    /** Markdown 图片语法：{@code ![alt](dest)}，dest 可含尖括号包裹的含空格路径。 */
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[[^\\]]*]\\(\\s*<?([^)\\s>]*?)>?\\s*\\)");

    private final GimPaths gimPaths;

    public ChatMessagePayload(GimPaths gimPaths) {
        this.gimPaths = gimPaths;
    }

    /** 序列化消息列表为 JS 数组字面量文本（空列表返回 {@code []}）。 */
    public static String toJson(List<Map<String, Object>> messages) {
        try {
            return MAPPER.writeValueAsString(messages);
        } catch (Exception e) {
            log.warn("聊天载荷序列化失败，按空列表处理", e);
            return "[]";
        }
    }

    /** 字符串 → JS 字面量（供流式增量等单值参数安全传入 executeScript）。 */
    public static String jsString(String s) {
        try {
            return MAPPER.writeValueAsString(s == null ? "" : s);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    /** 对象 → JSON 文本（供对比卡列头、页脚等结构化参数）。 */
    public static String jsValue(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "null";
        }
    }

    // ---------- 用户消息 ----------

    /** 历史重放：用户消息（附件按落盘路径读回，图片转 data URI、文件转文件行）。 */
    public Map<String, Object> user(long id, String content, List<StoredAttachment> attachments) {
        List<Map<String, Object>> images = new ArrayList<>();
        List<Map<String, Object>> files = new ArrayList<>();
        if (attachments != null) {
            for (StoredAttachment a : attachments) {
                if (a.isFile()) {
                    files.add(fileRow(a));
                } else {
                    Map<String, Object> img = imageDataUri(a);
                    if (img != null) {
                        images.add(img);
                    }
                }
            }
        }
        return message(id, "user", content, images, files, null, null, null, false);
    }

    /** 发送即时上屏：用户消息（图片来自待发 Base64，文件来自预览引用，尚无主键）。 */
    public Map<String, Object> userLive(long id, String content, List<ImageAttachment> images, List<StoredAttachment> files) {
        List<Map<String, Object>> img = new ArrayList<>();
        if (images != null) {
            for (ImageAttachment a : images) {
                if (a == null || a.base64Data() == null) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("dataUri", "data:" + a.mediaType() + ";base64," + a.base64Data());
                img.add(m);
            }
        }
        List<Map<String, Object>> fileRows = new ArrayList<>();
        if (files != null) {
            for (StoredAttachment a : files) {
                fileRows.add(fileRow(a));
            }
        }
        return message(id, "user", content, img, fileRows, null, null, null, false);
    }

    // ---------- 助手消息 ----------

    /**
     * 历史重放 / 收尾：助手消息（Markdown 源文、思考全文、工具调用列原文、中断标记）。
     * 正文里的本地图片引用内联为 data URI；{@code toolCallsJson} 解码为结构化记录。
     */
    public Map<String, Object> assistant(long id, String content, String toolCallsJson, String thinking,
                                         LocalDateTime createdAt, boolean interrupted) {
        String original = content == null ? "" : content;
        String body = original;
        if (interrupted && !body.isEmpty()) {
            body = body + "\n" + I18n.t("chat.interrupted");
        }
        body = inlineLocalImages(body);
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolCallRecordCodec.ToolCallRecord r : ToolCallRecordCodec.decode(toolCallsJson)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.name());
            m.put("input", r.input());
            m.put("output", r.output());
            m.put("ok", r.ok());
            m.put("durationMs", r.durationMillis());
            tools.add(m);
        }
        Map<String, Object> msg = message(id, "assistant", body, List.of(), List.of(), tools,
                (thinking == null || thinking.isBlank()) ? null : thinking,
                createdAt == null ? null : TIME.format(createdAt), false);
        // 复制取原文 Markdown（本地图片已内联为 data URI 的正文不适合复制）；仅当与展示正文不同才附上
        if (!original.equals(body)) {
            msg.put("rawContent", original);
        }
        return msg;
    }

    // ---------- 内部 ----------

    private Map<String, Object> message(long id, String role, String content,
                                        List<Map<String, Object>> images, List<Map<String, Object>> files,
                                        List<Map<String, Object>> tools, String thinking, String time,
                                        boolean interrupted) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("role", role);
        m.put("content", content == null ? "" : content);
        if (images != null && !images.isEmpty()) {
            m.put("images", images);
        }
        if (files != null && !files.isEmpty()) {
            m.put("files", files);
        }
        if (tools != null && !tools.isEmpty()) {
            m.put("tools", tools);
        }
        if (thinking != null) {
            m.put("thinking", thinking);
        }
        if (time != null) {
            m.put("time", time);
        }
        if (interrupted) {
            m.put("interrupted", true);
        }
        return m;
    }

    private Map<String, Object> fileRow(StoredAttachment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.name());
        m.put("size", a.sizeBytes() > 0 ? humanSize(a.sizeBytes()) : "");
        if (a.path() != null && !a.path().isBlank()) {
            m.put("path", gimPaths.attachmentsDir().resolve(a.path()).toString());
        }
        return m;
    }

    /** 落盘图片 → data URI 载荷；读失败/超限返回 {@code null}（丢弃该图）。 */
    private Map<String, Object> imageDataUri(StoredAttachment a) {
        if (a.path() == null || a.path().isBlank()) {
            return null;
        }
        Path p = gimPaths.attachmentsDir().resolve(a.path());
        if (!Files.exists(p)) {
            return null;
        }
        try {
            if (Files.size(p) > MAX_EMBED_BYTES) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(p);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dataUri", "data:" + mimeOf(a, p) + ";base64," + Base64.getEncoder().encodeToString(bytes));
            m.put("path", p.toString());
            return m;
        } catch (Exception e) {
            log.warn("附件图片内联失败: {}", a.path(), e);
            return null;
        }
    }

    /** 把助手 Markdown 里指向本地文件的图片引用替换为 data URI（非本地/读失败保持原样）。 */
    String inlineLocalImages(String markdown) {
        if (markdown == null || markdown.isEmpty() || markdown.indexOf("![") < 0) {
            return markdown;
        }
        Matcher mt = MD_IMAGE.matcher(markdown);
        StringBuilder sb = new StringBuilder();
        while (mt.find()) {
            String dest = mt.group(1);
            Path local = MarkdownRenderer.localImagePath(dest);
            String replacement = mt.group(0);
            if (local != null && Files.exists(local)) {
                try {
                    if (Files.size(local) <= MAX_EMBED_BYTES) {
                        byte[] bytes = Files.readAllBytes(local);
                        String uri = "data:" + mimeOfPath(local) + ";base64," + Base64.getEncoder().encodeToString(bytes);
                        replacement = mt.group(0).replace(dest, uri);
                    }
                } catch (Exception e) {
                    // 读取失败保持原引用
                }
            }
            mt.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        mt.appendTail(sb);
        return sb.toString();
    }

    private static String mimeOf(StoredAttachment a, Path p) {
        if (a.mime() != null && !a.mime().isBlank()) {
            return a.mime();
        }
        return mimeOfPath(p);
    }

    private static String mimeOfPath(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        if (n.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (n.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "image/png";
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
    }
}
