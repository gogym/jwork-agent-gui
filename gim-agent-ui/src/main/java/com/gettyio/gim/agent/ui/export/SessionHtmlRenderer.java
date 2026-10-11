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

package com.gettyio.gim.agent.ui.export;

import com.gettyio.gim.agent.core.chat.ToolCallsCodec;
import com.gettyio.gim.agent.core.chat.ToolCallsCodec.ToolCallView;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.ui.chat.render.MarkdownRenderer;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 会话导出为「话题卡片」式独立 HTML：标题与元信息头 + 逐条消息的气泡卡片 + 页脚品牌，
 * 全部样式内联自包含，单文件可直接分享给他人打开。
 * <p>Markdown 由 commonmark 渲染为 HTML（{@code escapeHtml(true)} 防注入）；正文中引用的本地
 * 图片（AI 生成图 / 绝对路径）按 {@link MarkdownRenderer#localImagePath} 同一判定读入并内嵌为
 * {@code data:} URI，保证 HTML 与长图脱离本机文件依然可看。纯字符串渲染、无 JavaFX 依赖，
 * 可在后台线程调用；长图导出（PNG）复用同一份 HTML，只额外追加隐藏滚动条样式。</p>
 *
 * @author gogym
 */
public final class SessionHtmlRenderer {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 内嵌图片的单张大小上限：超过则不内嵌（原样保留路径，避免 HTML 体积失控）。 */
    private static final long MAX_EMBED_IMAGE_BYTES = 8L * 1024 * 1024;

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    /** Markdown → HTML：转义源码中的原始 HTML；图片经属性提供者尝试内嵌为 data URI。 */
    private static final HtmlRenderer HTML_RENDERER = HtmlRenderer.builder()
            .extensions(List.of(TablesExtension.create()))
            .escapeHtml(true)
            .attributeProviderFactory(context -> (node, tagName, attributes) -> {
                if (!"img".equals(tagName)) {
                    return;
                }
                String data = dataUriOf(attributes.get("src"));
                if (data != null) {
                    attributes.put("src", data);
                }
            })
            .build();

    private SessionHtmlRenderer() {
    }

    /** 渲染整份 HTML 文档；{@code session}/{@code messages} 允许为空（容错）。 */
    public static String render(ChatSession session, List<ChatConversation> messages) {
        List<ChatConversation> list = messages == null ? List.of() : messages;
        String title = session == null || session.getTitle() == null || session.getTitle().isBlank()
                ? (session == null ? "会话导出" : session.getSessionId())
                : session.getTitle().trim();
        String exportedAt = LocalDateTime.now().format(TS);

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(esc(title)).append("</title>\n")
                .append("<style>\n").append(CSS).append("</style>\n")
                .append("</head>\n<body>\n<article class=\"card\">\n");

        // 头部：标题 + 元信息
        sb.append("<header class=\"card-head\">\n")
                .append("<h1 class=\"card-title\">").append(esc(title)).append("</h1>\n")
                .append("<div class=\"card-meta\">");
        if (session != null) {
            sb.append("<span>会话：").append(esc(session.getSessionId())).append("</span>");
            if (notBlank(session.getAgentCode())) {
                sb.append("<span>智能体：").append(esc(session.getAgentCode())).append("</span>");
            }
            if (notBlank(session.getModelCode())) {
                sb.append("<span>模型：").append(esc(session.getModelCode())).append("</span>");
            }
        }
        sb.append("<span>消息数：").append(list.size()).append("</span>")
                .append("<span>导出时间：").append(exportedAt).append("</span>")
                .append("</div>\n</header>\n<main class=\"card-body\">\n");

        for (ChatConversation c : list) {
            appendMessage(sb, c);
        }

        sb.append("</main>\n")
                .append("<footer class=\"card-foot\">由 Jwork 导出 · 本地优先的个人 AI 助理</footer>\n")
                .append("</article>\n</body>\n</html>\n");
        return sb.toString();
    }

    /** 单条消息：角色徽标 + 时间 + Markdown 气泡 + 工具调用行。 */
    private static void appendMessage(StringBuilder sb, ChatConversation c) {
        String role = c.getRole() == null ? "" : c.getRole().toUpperCase(Locale.ROOT);
        String cls = switch (role) {
            case "USER" -> "msg-user";
            case "ASSISTANT" -> "msg-assistant";
            case "SYSTEM" -> "msg-system";
            case "TOOL" -> "msg-tool";
            default -> "msg-other";
        };
        sb.append("<section class=\"msg ").append(cls).append("\">\n")
                .append("<div class=\"msg-head\"><span class=\"role role-").append(cls.substring(4)).append("\">")
                .append(esc(ExportFormats.rolePlain(c.getRole()))).append("</span>");
        if (c.getCreatedAt() != null) {
            sb.append("<span class=\"time\">").append(c.getCreatedAt().format(TS)).append("</span>");
        }
        sb.append("</div>\n<div class=\"bubble md\">");
        String content = c.getContent() == null ? "" : c.getContent().trim();
        sb.append(content.isEmpty() ? "<p class=\"muted\">（空）</p>" : HTML_RENDERER.render(PARSER.parse(content)));
        sb.append("</div>\n");

        List<ToolCallView> calls = ToolCallsCodec.decode(c.getToolCalls());
        if (!calls.isEmpty()) {
            sb.append("<div class=\"tools\">");
            for (ToolCallView call : calls) {
                sb.append("<div class=\"tool\">\uD83D\uDD27 <code>").append(esc(call.name())).append("</code>");
                Map<String, Object> input = call.input();
                if (input != null && !input.isEmpty()) {
                    sb.append("<span class=\"tool-args\">").append(esc(ExportFormats.summarizeInput(input))).append("</span>");
                }
                sb.append("</div>");
            }
            sb.append("</div>\n");
        }
        sb.append("</section>\n");
    }

    /** 本地文件 → {@code data:} URI；非本地 / 不存在 / 超限 / 读取失败返回 null（保持原样）。 */
    static String dataUriOf(String destination) {
        Path path = MarkdownRenderer.localImagePath(destination);
        if (path == null) {
            return null;
        }
        try {
            if (Files.size(path) > MAX_EMBED_IMAGE_BYTES) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(path);
            return "data:" + mimeOf(path) + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private static String mimeOf(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "image/png";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 最小 HTML 转义（& < > "）：标题、工具名与入参等直接拼接文本必须经此。 */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 话题卡片样式：浅色底 + 白色圆角卡片 + 左右角色徽标气泡；纯内联，无外部资源。 */
    private static final String CSS = """
            * { box-sizing: border-box; }
            body {
                margin: 0; padding: 28px 14px; background: #EEF1F6; color: #1F2329;
                font-family: "Segoe UI", "Microsoft YaHei UI", "PingFang SC", "Hiragino Sans GB", "Noto Sans SC", sans-serif;
                font-size: 14px; line-height: 1.7; -webkit-font-smoothing: antialiased;
            }
            .card { max-width: 860px; margin: 0 auto; background: #FFFFFF; border-radius: 16px;
                    box-shadow: 0 8px 30px rgba(15, 23, 42, .10); overflow: hidden; }
            .card-head { padding: 26px 32px 18px; border-bottom: 1px solid #EDF0F4; }
            .card-title { margin: 0 0 10px; font-size: 21px; line-height: 1.4; color: #111827; }
            .card-meta { display: flex; flex-wrap: wrap; gap: 4px 16px; font-size: 12px; color: #8A94A3; }
            .card-body { padding: 8px 32px 20px; }
            .card-foot { padding: 14px 32px 18px; border-top: 1px solid #EDF0F4; font-size: 11px; color: #A6AEBB; }
            .msg { padding: 14px 0; }
            .msg + .msg { border-top: 1px dashed #EFF2F6; }
            .msg-head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 6px; }
            .role { font-size: 12px; font-weight: 600; padding: 1px 8px; border-radius: 999px; }
            .role-user { background: #E8F0FE; color: #1A56DB; }
            .role-assistant { background: #EEF2F7; color: #3F4756; }
            .role-system, .role-tool, .role-other { background: #F4F4F5; color: #71717A; }
            .time { font-size: 11px; color: #A6AEBB; }
            .bubble { border-radius: 10px; padding: 10px 14px; overflow-wrap: break-word; }
            .msg-user .bubble { background: #F4F8FF; }
            .msg-assistant .bubble { background: #F8F9FB; }
            .msg-system .bubble, .msg-tool .bubble, .msg-other .bubble { background: #FAFAFA; color: #52525B; }
            .muted { color: #A6AEBB; }
            .tools { margin-top: 6px; }
            .tool { font-size: 12px; color: #6B7280; padding: 2px 0; }
            .tool code { background: #F1F3F5; border: 1px solid #E7EAEF; border-radius: 4px; padding: 0 4px;
                         font-family: "Cascadia Mono", Consolas, "Courier New", monospace; }
            .tool-args { margin-left: 6px; color: #98A1B0; }
            .md > :first-child { margin-top: 0; }
            .md > :last-child { margin-bottom: 0; }
            .md h1, .md h2, .md h3, .md h4 { margin: 14px 0 8px; line-height: 1.4; color: #111827; }
            .md h1 { font-size: 19px; }
            .md h2 { font-size: 17px; }
            .md h3 { font-size: 15px; }
            .md h4 { font-size: 14px; }
            .md p { margin: 6px 0; }
            .md a { color: #1A56DB; text-decoration: none; }
            .md code { background: #F1F3F5; border: 1px solid #E7EAEF; border-radius: 4px; padding: 1px 5px;
                       font-family: "Cascadia Mono", Consolas, "Courier New", monospace; font-size: 12px; }
            .md pre { background: #F6F8FA; border: 1px solid #E7EAEF; border-radius: 8px;
                      padding: 12px 14px; overflow-x: auto; }
            .md pre code { background: none; border: none; padding: 0; font-size: 12.5px; line-height: 1.6; }
            .md blockquote { margin: 8px 0; padding: 4px 12px; border-left: 3px solid #C7D2E0;
                             background: #F8FAFC; color: #475569; }
            .md table { border-collapse: collapse; margin: 8px 0; width: 100%; }
            .md th, .md td { border: 1px solid #E3E8EF; padding: 6px 10px; font-size: 13px; text-align: left; }
            .md th { background: #F4F6F9; }
            .md ul, .md ol { margin: 6px 0; padding-left: 22px; }
            .md li { margin: 2px 0; }
            .md hr { border: none; border-top: 1px solid #E8ECF1; margin: 12px 0; }
            .md img { max-width: 100%; border-radius: 8px; display: block; margin: 8px 0; }
            """;
}
