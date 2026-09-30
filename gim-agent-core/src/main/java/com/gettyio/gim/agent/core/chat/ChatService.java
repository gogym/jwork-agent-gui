package com.gettyio.gim.agent.core.chat;

import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.enums.ConversationRole;
import com.gettyio.gim.agent.domain.enums.SessionStatus;
import com.gettyio.gim.agent.domain.enums.StreamEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
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

    public ChatService(AgentGateway gateway, GimDatabase db) {
        this.gateway = gateway;
        this.db = db;
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
     * 发起一轮流式对话：落盘用户消息 → 流式应答 → 落盘助手消息。
     * <p>agentCode 决定由哪个智能体应答（人格/模型见 {@link MultiAgentGateway}）。</p>
     */
    public Flux<StreamEvent> chat(String sessionId, String agentCode, String userText) {
        ensureSession(sessionId, agentCode, userText);
        persistMessage(sessionId, agentCode, ConversationRole.USER, userText);

        StringBuilder assistantBuf = new StringBuilder();
        return gateway.stream(agentCode, sessionId, userText)
                .doOnNext(event -> {
                    if (event.getType() == StreamEventType.TEXT_DELTA && event.getText() != null) {
                        assistantBuf.append(event.getText());
                    }
                })
                .doOnComplete(() -> persistAssistant(sessionId, agentCode, assistantBuf.toString()));
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

    private void persistAssistant(String sessionId, String agentCode, String assistantText) {
        if (assistantText == null || assistantText.isEmpty()) {
            return;
        }
        persistMessage(sessionId, agentCode, ConversationRole.ASSISTANT, assistantText);
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

    /** 删除会话及其全部消息 */
    public void deleteSession(String sessionId) {
        db.conversationDao().deleteBySession(sessionId);
        db.sessionDao().deleteBySessionId(sessionId);
        log.info("删除会话: {}", sessionId);
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
