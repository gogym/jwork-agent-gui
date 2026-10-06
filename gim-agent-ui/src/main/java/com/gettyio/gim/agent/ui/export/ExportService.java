package com.gettyio.gim.agent.ui.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gettyio.gim.agent.core.chat.ToolCallsCodec;
import com.gettyio.gim.agent.core.chat.ToolCallsCodec.ToolCallView;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.entity.ChatSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
