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

package com.gettyio.jwork.agent.ui.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gettyio.jwork.agent.core.JworkPaths;
import com.gettyio.jwork.agent.core.chat.ChatSegmentCodec;
import com.gettyio.jwork.agent.core.chat.ImageAttachment;
import com.gettyio.jwork.agent.core.chat.SessionArtifactDelivery;
import com.gettyio.jwork.agent.core.chat.StoredAttachment;
import com.gettyio.jwork.agent.core.chat.ToolCallRecordCodec;
import com.gettyio.jwork.agent.core.fs.CanonicalPath;
import com.gettyio.jwork.agent.domain.util.HttpMedia;
import com.gettyio.jwork.agent.domain.util.TimeFormats;
import com.gettyio.jwork.agent.ui.chat.payload.WebChatFile;
import com.gettyio.jwork.agent.ui.chat.payload.WebChatImage;
import com.gettyio.jwork.agent.ui.chat.payload.WebChatMessage;
import com.gettyio.jwork.agent.ui.chat.payload.WebChatSegment;
import com.gettyio.jwork.agent.ui.chat.payload.WebChatTool;
import com.gettyio.jwork.agent.ui.chat.render.MarkdownRenderer;
import com.gettyio.jwork.agent.ui.i18n.I18n;
import com.gettyio.jwork.agent.ui.util.UiFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天消息 → WebView {@code chat.js} 侧 JSON 载荷的构建器：把 {@link StoredAttachment} 图片读成
 * {@code data:} URI、把助手 Markdown 里的本地图片引用内联为 {@code data:} URI、把工具调用列解码为结构化记录，
 * 组装为 {@link WebChatMessage} 等桥接 DTO（详见 {@code ui.chat.payload} 包），最终序列化为
 * 合法 JSON 数组交 {@code ChatAPI.renderHistory/prependOlder}。
 * <p>Java 只推原始文本与结构化数据，Markdown→HTML 由前端 marked+DOMPurify 负责。图片内联失败/超限则丢弃该图，
 * 绝不因一张坏图阻断整页渲染。</p>
 *
 * @author gogym
 */
public final class ChatMessagePayload {

    private static final Logger log = LoggerFactory.getLogger(ChatMessagePayload.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME = TimeFormats.MINUTE_TIMESTAMP;
    /** 单张内联图片字节上限（8MB）：与附件条口径一致，超限丢弃避免撑爆脚本字符串。 */
    private static final long MAX_EMBED_BYTES = 8L * 1024 * 1024;
    /** Markdown 图片语法：{@code ![alt](dest)}，dest 可含尖括号包裹的含空格路径。 */
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[[^\\]]*]\\(\\s*<?([^)\\s>]*?)>?\\s*\\)");

    private final JworkPaths jworkPaths;

    public ChatMessagePayload(JworkPaths jworkPaths) {
        this.jworkPaths = jworkPaths;
    }

    /** 序列化消息列表为 JS 数组字面量文本（空列表返回 {@code []}）。 */
    public static String toJson(List<WebChatMessage> messages) {
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

    /**
     * 桥接 DTO（消息/对比列头、页脚/文案包等）→ JSON 文本，供 executeScript 结构化参数。
     * <p>序列化失败返回 {@code null} 而不是字面量 {@code "null"}：拼进脚本后页面会抛
     * {@code TypeError}（{@code ChatAPI.appendMessage(null)} 之类），一次脏数据会连累同批
     * 下发的后续渲染。调用方见 {@code null} 即放弃本次调用。</p>
     */
    public static String jsValue(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("聊天载荷序列化失败，放弃该次桥下发: {}", o == null ? "null" : o.getClass().getSimpleName(), e);
            return null;
        }
    }

    // ---------- 用户消息 ----------

    /** 历史重放：用户消息（附件按落盘路径读回，图片转 data URI、文件转文件行）。 */
    public WebChatMessage user(long id, String content, List<StoredAttachment> attachments) {
        List<WebChatImage> images = new ArrayList<>();
        List<WebChatFile> files = new ArrayList<>();
        if (attachments != null) {
            for (StoredAttachment a : attachments) {
                if (a.isFile()) {
                    files.add(fileRow(a));
                } else {
                    WebChatImage img = imageDataUri(a);
                    if (img != null) {
                        images.add(img);
                    }
                }
            }
        }
        return new WebChatMessage(id, "user", content, images, files, null, null, null, null);
    }

    /** 发送即时上屏：用户消息（图片来自待发 Base64，文件来自预览引用，尚无主键）。 */
    public WebChatMessage userLive(long id, String content, List<ImageAttachment> images, List<StoredAttachment> files) {
        List<WebChatImage> img = new ArrayList<>();
        if (images != null) {
            for (ImageAttachment a : images) {
                if (a == null || a.base64Data() == null) {
                    continue;
                }
                img.add(new WebChatImage("data:" + a.mediaType() + ";base64," + a.base64Data(), null));
            }
        }
        List<WebChatFile> fileRows = new ArrayList<>();
        if (files != null) {
            for (StoredAttachment a : files) {
                fileRows.add(fileRow(a));
            }
        }
        return new WebChatMessage(id, "user", content, img, fileRows, null, null, null, null);
    }

    // ---------- 助手消息 ----------

    /**
     * 历史重放 / 收尾：助手消息（Markdown 源文、思考全文、工具调用列原文、中断标记）。
     * 正文里的本地图片引用内联为 data URI；{@code toolCallsJson} 解码为结构化记录。
     * <p>带时序分段时正文按段拆开交 {@code segments}，前端还原「思考卡 → 正文 → 工具组」
     * 交错布局；中断标记追加到最后一个正文段尾（与旧口径一致：无正文时不追加）。</p>
     */
    public WebChatMessage assistant(long id, String content, String toolCallsJson, String thinking,
                                    LocalDateTime createdAt, boolean interrupted) {
        return assistant(id, content, toolCallsJson, thinking, createdAt, interrupted, null);
    }

    /**
     * 同上，带时序分段（来自 metadata.segments，无则退化为旧版扁平渲染）。
     */
    public WebChatMessage assistant(long id, String content, String toolCallsJson, String thinking,
                                    LocalDateTime createdAt, boolean interrupted,
                                    List<ChatSegmentCodec.ChatSegment> segments) {
        return assistant(id, content, toolCallsJson, thinking, createdAt, interrupted, segments, null);
    }

    /**
     * 同上，并给出所属会话（用于反查 {@code deliver_artifact} 交付到本机的产物卡）。
     *
     * @param sessionId 会话 ID；为空则不出产物卡（新会话尚未落库的临时气泡）
     */
    public WebChatMessage assistant(long id, String content, String toolCallsJson, String thinking,
                                    LocalDateTime createdAt, boolean interrupted,
                                    List<ChatSegmentCodec.ChatSegment> segments, String sessionId) {
        String original = content == null ? "" : content;
        List<ToolCallRecordCodec.ToolCallRecord> records = ToolCallRecordCodec.decode(toolCallsJson);
        List<WebChatTool> tools = new ArrayList<>();
        for (ToolCallRecordCodec.ToolCallRecord r : records) {
            tools.add(new WebChatTool(r.name(), r.input(), r.output(), r.ok(), r.durationMillis()));
        }
        List<WebChatFile> artifacts = artifactRows(records, sessionId);
        String time = createdAt == null ? null : TIME.format(createdAt);
        String thinkingText = (thinking == null || thinking.isBlank()) ? null : thinking;
        List<WebChatSegment> segs = buildSegments(segments, interrupted ? "\n" + I18n.t("chat.interrupted") : null);
        if (segs.isEmpty()) {
            // 旧版扁平渲染：思考/工具卡在气泡外，正文整段渲染
            String body = original;
            if (interrupted && !body.isEmpty()) {
                body = body + "\n" + I18n.t("chat.interrupted");
            }
            body = inlineLocalImages(body);
            String rawContent = original.equals(body) ? null : original;
            return new WebChatMessage(id, "assistant", body, List.of(), artifacts, tools, thinkingText, time, rawContent);
        }
        String inlined = String.join("", segs.stream()
                .filter(s -> "text".equals(s.type()) && s.text() != null)
                .map(WebChatSegment::text)
                .toList());
        // 任一正文段内联了本地图片（或带中断标记）时附上原文供复制
        String rawContent = !inlined.equals(original) || interrupted ? original : null;
        return new WebChatMessage(id, "assistant", original, List.of(), artifacts, tools, thinkingText, time,
                rawContent, segs);
    }

    // ---------- 产物卡 ----------

    /** 框架内置交付工具名：其调用记录即「已交到本机」的凭据，UI 据此反查文件出卡片。 */
    static final String DELIVER_TOOL = "deliver_artifact";

    /** 入参里提取 fileName / filePath（截断的 JSON 也能拿到先出现的字段）。 */
    private static final Pattern DELIVER_NAME = Pattern.compile("\"fileName\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DELIVER_PATH = Pattern.compile("\"filePath\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * 从工具调用记录还原产物卡：只认成功的 {@code deliver_artifact}，且文件确实在
     * {@code generated/sessions/<会话ID>/} 下落了盘——交付记录说的是历史，文件可能已被清理。
     */
    List<WebChatFile> artifactRows(List<ToolCallRecordCodec.ToolCallRecord> records, String sessionId) {
        List<WebChatFile> out = new ArrayList<>();
        if (records.isEmpty() || sessionId == null || sessionId.isBlank()) {
            return out;
        }
        Path dir = SessionArtifactDelivery.dirOf(jworkPaths.deliveriesDir(), sessionId);
        Path deliveryDir = CanonicalPath.real(dir);
        List<String> seen = new ArrayList<>();
        for (ToolCallRecordCodec.ToolCallRecord r : records) {
            if (!DELIVER_TOOL.equals(r.name()) || !r.ok()) {
                continue;
            }
            String fileName = deliverName(r.input());
            if (fileName == null) {
                continue;
            }
            // 文件名出自模型写下的 deliver_artifact 参数：按规范形判归属，否则目录里一个链接
            // 就能把「会话交付物」指到盘上任意文件，产物卡显示的却不是真正被打开的那个
            Path file = CanonicalPath.real(dir.resolve(fileName));
            if (!file.startsWith(deliveryDir) || !Files.isRegularFile(file)) {
                continue;
            }
            String key = file.toString();
            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);
            long size;
            try {
                size = Files.size(file);
            } catch (IOException e) {
                continue;
            }
            out.add(new WebChatFile(file.getFileName().toString(), UiFormat.humanSize(size), key));
        }
        return out;
    }

    /** 交付名：{@code fileName} 优先，缺省取 {@code filePath} 末段；两者都解析不到返回 null（不出卡）。 */
    private static String deliverName(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        Matcher name = DELIVER_NAME.matcher(input);
        if (name.find()) {
            return basename(name.group(1));
        }
        Matcher path = DELIVER_PATH.matcher(input);
        return path.find() ? basename(path.group(1)) : null;
    }

    private static String basename(String raw) {
        String s = raw.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        String out = slash >= 0 ? s.substring(slash + 1) : s;
        return out.isBlank() ? null : out;
    }


    /**
     * 把时序分段映射为 JS 载荷：正文段做本地图片内联，思考段带上耗时供「思考 · Ns」呈现，
     * 旁白段按当前语言解析成文案。中断标记落到最后一个正文段尾。
     * 无输入分段或去空段后为空时返回空列表（前端退化为旧版扁平渲染）。
     */
    private List<WebChatSegment> buildSegments(List<ChatSegmentCodec.ChatSegment> segments, String interruptNote) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        List<WebChatSegment> out = new ArrayList<>();
        for (ChatSegmentCodec.ChatSegment s : segments) {
            if (s == null) {
                continue;
            }
            if (ChatSegmentCodec.ChatSegment.TOOLS.equals(s.type())) {
                if (!s.tools().isEmpty()) {
                    List<WebChatTool> mapped = new ArrayList<>(s.tools().size());
                    for (ToolCallRecordCodec.ToolCallRecord r : s.tools()) {
                        mapped.add(new WebChatTool(r.name(), r.input(), r.output(), r.ok(), r.durationMillis()));
                    }
                    out.add(new WebChatSegment("tools", null, mapped, null));
                }
            } else if (ChatSegmentCodec.ChatSegment.NOTICE.equals(s.type())) {
                out.add(new WebChatSegment("notice", UiFormat.noticeText(s.noticeCode(), s.text()), null, null));
            } else if (s.text() != null && !s.text().isEmpty()) {
                Long millis = ChatSegmentCodec.ChatSegment.THINKING.equals(s.type()) ? s.durationMillis() : null;
                out.add(new WebChatSegment(s.type(), inlineLocalImages(s.text()), null, millis));
            }
        }
        if (interruptNote != null) {
            for (int i = out.size() - 1; i >= 0; i--) {
                if ("text".equals(out.get(i).type())) {
                    WebChatSegment t = out.get(i);
                    out.set(i, new WebChatSegment("text", t.text() + interruptNote, null, t.ms()));
                    break;
                }
            }
        }
        return out;
    }

    // ---------- 内部 ----------

    private WebChatFile fileRow(StoredAttachment a) {
        return new WebChatFile(a.name(),
                a.sizeBytes() > 0 ? UiFormat.humanSize(a.sizeBytes()) : "",
                a.path() != null && !a.path().isBlank()
                        ? jworkPaths.attachmentsDir().resolve(a.path()).toString() : null);
    }

    /** 落盘图片 → data URI 载荷；读失败/超限返回 {@code null}（丢弃该图）。 */
    private WebChatImage imageDataUri(StoredAttachment a) {
        if (a.path() == null || a.path().isBlank()) {
            return null;
        }
        Path p = jworkPaths.attachmentsDir().resolve(a.path());
        if (!Files.exists(p)) {
            return null;
        }
        try {
            if (Files.size(p) > MAX_EMBED_BYTES) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(p);
            return new WebChatImage(
                    "data:" + mimeOf(a, p) + ";base64," + Base64.getEncoder().encodeToString(bytes),
                    p.toString());
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
        return HttpMedia.IMAGE_PNG;
    }
}
