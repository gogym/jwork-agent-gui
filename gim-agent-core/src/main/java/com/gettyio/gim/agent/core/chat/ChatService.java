package com.gettyio.gim.agent.core.chat;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.enums.ConversationRole;
import com.gettyio.gim.agent.domain.enums.SessionStatus;
import com.gettyio.gim.agent.domain.enums.StreamEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 对话服务：在流式网关两侧补齐 SQLite 落盘（会话 + 消息），实现重启不丢历史。
 * <p>chat-history.jsonl 由 Harness 工作区自行维护，这里负责应用级会话记录。</p>
 * <p>会话只与智能体关联（{@code sessions.agent_code}）。</p>
 */
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 会话标题长度上限：自动取名与手动重命名共用一个口径（UI 也拿它限输入框） */
    public static final int TITLE_MAX_LEN = 30;

    private final AgentGateway gateway;
    private final GimDatabase db;
    private final GimPaths gimPaths;

    public ChatService(AgentGateway gateway, GimDatabase db, GimPaths gimPaths) {
        this.gateway = gateway;
        this.db = db;
        this.gimPaths = gimPaths;
    }

    /**
     * 主动消息运行（定时任务语义）：落盘系统指令 → 非流式应答 → 落盘助手消息。
     * <p>自主会话无 UI，敏感工具在无确认通道时被网关默认拒绝。</p>
     *
     * @return 助手应答文本（可能为空）
     */
    public String proactiveRun(String sessionId, String agentCode, String prompt) {
        ensureSession(sessionId, agentCode, prompt);
        persistMessage(sessionId, agentCode, ConversationRole.SYSTEM, prompt);
        String reply = gateway.ask(agentCode, sessionId, prompt).block();
        persistAssistant(sessionId, agentCode, reply);
        return reply;
    }

    /**
     * 发起一轮流式对话：落盘用户消息（含图片附件）→ 流式应答 → 落盘助手消息。
     * <p>{@link ChatRequest} 携带消息级模型覆盖与图片附件；无附件时行为与纯文本对话一致。</p>
     */
    public Flux<StreamEvent> chat(ChatRequest req) {
        String sessionId = req.getSessionId();
        String agentCode = req.getAgentCode();
        ensureSession(sessionId, agentCode, req.getText());
        persistUser(req);

        StringBuilder assistantBuf = new StringBuilder();
        return gateway.stream(req)
                .doOnNext(event -> {
                    if (event.getType() == StreamEventType.TEXT_DELTA && event.getText() != null) {
                        assistantBuf.append(event.getText());
                    }
                })
                .doOnComplete(() -> persistAssistant(sessionId, agentCode, req.getModelCode(), assistantBuf.toString()));
    }

    private void ensureSession(String sessionId, String agentCode, String firstUserText) {
        java.util.Optional<ChatSession> existing = db.sessionDao().findBySessionId(sessionId);
        if (existing.isEmpty()) {
            ChatSession session = new ChatSession();
            session.setSessionId(sessionId);
            session.setAgentCode(agentCode);
            session.setTitle(deriveTitle(firstUserText));
            session.setStatus(SessionStatus.ACTIVE.name());
            session.setMessageCount(0);
            db.sessionDao().insert(session);
            log.info("新建会话: {} ({})", sessionId, session.getTitle());
        } else if ("新会话".equals(existing.get().getTitle())) {
            // 首条消息后以内容作为标题
            db.sessionDao().updateTitle(sessionId, deriveTitle(firstUserText));
        }
    }

    private void persistMessage(String sessionId, String agentCode, ConversationRole role, String content) {
        ChatConversation c = new ChatConversation();
        c.setSessionId(sessionId);
        c.setAgentCode(agentCode);
        c.setRole(role.getCode());
        c.setContent(content);
        c.setCreatedAt(LocalDateTime.now());
        db.conversationDao().insert(c);
        db.sessionDao().incrementMessageCount(sessionId, 1);
    }

    /** 落盘用户消息：正文入 content，本轮模型入 model_code，图片附件落盘后清单入 metadata。 */
    private void persistUser(ChatRequest req) {
        List<StoredAttachment> stored = storeImages(req.getSessionId(), req.getImages());
        ChatConversation c = new ChatConversation();
        c.setSessionId(req.getSessionId());
        c.setAgentCode(req.getAgentCode());
        c.setRole(ConversationRole.USER.getCode());
        c.setContent(req.getText() == null ? "" : req.getText());
        c.setModelCode(req.getModelCode());
        c.setMetadata(AttachmentCodec.write(stored));
        c.setCreatedAt(LocalDateTime.now());
        db.conversationDao().insert(c);
        db.sessionDao().incrementMessageCount(req.getSessionId(), 1);
    }

    /** 把图片附件字节写入 {@code attachments/<sessionId>/<uuid>.<ext>}，返回可持久化的引用清单。 */
    private List<StoredAttachment> storeImages(String sessionId, List<ImageAttachment> images) {
        List<StoredAttachment> out = new ArrayList<>();
        if (images == null || images.isEmpty()) {
            return out;
        }
        for (ImageAttachment img : images) {
            if (img == null || img.base64Data() == null || img.base64Data().isBlank()) {
                continue;
            }
            try {
                byte[] bytes = Base64.getDecoder().decode(img.base64Data());
                String fileName = UUID.randomUUID() + extensionOf(img);
                Path dir = gimPaths.attachmentsDir().resolve(sessionId);
                Files.createDirectories(dir);
                Files.write(dir.resolve(fileName), bytes);
                out.add(new StoredAttachment(img.fileName(), img.mediaType(), sessionId + "/" + fileName));
            } catch (IOException | RuntimeException e) {
                log.warn("图片附件落盘失败，跳过该附件: {}", img.fileName(), e);
            }
        }
        return out;
    }

    /** 优先用文件名后缀作扩展名，缺失时从 MIME 推导，兜底 .png。 */
    private static String extensionOf(ImageAttachment img) {
        String name = img.fileName();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot);
                if (ext.length() <= 6) {
                    return ext;
                }
            }
        }
        String mime = img.mediaType();
        if (mime != null && mime.contains("/")) {
            return "." + mime.substring(mime.indexOf('/') + 1).split("[;+]")[0];
        }
        return ".png";
    }

    private void persistAssistant(String sessionId, String agentCode, String assistantText) {
        persistAssistant(sessionId, agentCode, null, assistantText);
    }

    private void persistAssistant(String sessionId, String agentCode, String modelCode, String assistantText) {
        if (assistantText == null || assistantText.isEmpty()) {
            return;
        }
        ChatConversation c = new ChatConversation();
        c.setSessionId(sessionId);
        c.setAgentCode(agentCode);
        c.setRole(ConversationRole.ASSISTANT.getCode());
        c.setContent(assistantText);
        c.setModelCode(modelCode);
        c.setCreatedAt(LocalDateTime.now());
        db.conversationDao().insert(c);
        db.sessionDao().incrementMessageCount(sessionId, 1);
    }

    private static String deriveTitle(String text) {
        if (text == null || text.isBlank()) {
            return "新会话";
        }
        String t = text.trim();
        return t.length() <= TITLE_MAX_LEN ? t : t.substring(0, TITLE_MAX_LEN) + "…";
    }

    /** 读取历史消息（用于会话切换后重放） */
    public java.util.List<ChatConversation> history(String sessionId) {
        return db.conversationDao().findBySession(sessionId);
    }

    /** 会话列表（按最近更新排序） */
    public List<ChatSession> listSessions() {
        return db.sessionDao().findAll();
    }

    /** 删除会话及其全部消息，并连带清理其图片附件目录（best-effort） */
    public void deleteSession(String sessionId) {
        db.conversationDao().deleteBySession(sessionId);
        db.sessionDao().deleteBySessionId(sessionId);
        deleteAttachments(sessionId);
        log.info("删除会话: {}", sessionId);
    }

    private void deleteAttachments(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Path dir = gimPaths.attachmentsDir().resolve(sessionId);
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 单个文件删除失败不阻断整体清理
                }
            });
        } catch (IOException ignored) {
            // 目录不存在或不可访问：本就无附件可清理
        }
    }

    /**
     * 重命名会话：去首尾空白，超长截断到 {@link #TITLE_MAX_LEN}。
     * <p>空白标题直接忽略，免得会话在列表里变成一行看不见的空白；也不刷新 updated_at，
     * 列表按最近更新排序，改个名字不该调整会话位置。</p>
     */
    public void renameSession(String sessionId, String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        String t = title.trim();
        if (t.length() > TITLE_MAX_LEN) {
            t = t.substring(0, TITLE_MAX_LEN);
        }
        db.sessionDao().renameTitle(sessionId, t);
        log.info("重命名会话: {} -> {}", sessionId, t);
    }

    /** 新建空会话，立即落盘并返回 sessionId */
    public String createSession(String agentCode) {
        String sessionId = UUID.randomUUID().toString();
        ChatSession session = new ChatSession();
        session.setSessionId(sessionId);
        session.setAgentCode(agentCode);
        session.setTitle("新会话");
        session.setStatus(SessionStatus.ACTIVE.name());
        session.setMessageCount(0);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        db.sessionDao().insert(session);
        return sessionId;
    }
}
