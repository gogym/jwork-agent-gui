package com.gettyio.gim.agent.core.chat;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.entity.SessionGroup;
import com.gettyio.gim.agent.domain.enums.ConversationRole;
import com.gettyio.gim.agent.domain.enums.SessionStatus;
import com.gettyio.gim.agent.domain.enums.StreamEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话服务：在流式网关两侧补齐 SQLite 落盘（会话 + 消息），实现重启不丢历史。
 * <p>chat-history.jsonl 由 Harness 工作区自行维护，这里负责应用级会话记录。</p>
 * <p>会话只与智能体关联（{@code sessions.agent_code}）。</p>
 */
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 会话标题长度上限：自动取名与手动重命名共用一个口径（UI 也拿它限输入框） */
    public static final int TITLE_MAX_LEN = 30;

    /** 分组名长度上限：与标题同口径（UI 也拿它限输入框） */
    public static final int GROUP_NAME_MAX_LEN = 30;

    /** 单个文档附件的字节上限（10MB）：超过不落盘不抽取。 */
    private static final long MAX_DOC_BYTES = 10L * 1024 * 1024;

    private final AgentGateway gateway;
    private final GimDatabase db;
    private final GimPaths gimPaths;
    private final EffectiveModelResolver modelResolver;

    public ChatService(AgentGateway gateway, GimDatabase db, GimPaths gimPaths,
                       EffectiveModelResolver modelResolver) {
        this.gateway = gateway;
        this.db = db;
        this.gimPaths = gimPaths;
        this.modelResolver = modelResolver;
    }

    /**
     * 主动消息运行（默认只读档）：等价于 {@code proactiveRun(sessionId, agentCode, prompt, EXPLORE, false, null, null)}。
     *
     * @return 助手应答文本（可能为空）
     */
    public String proactiveRun(String sessionId, String agentCode, String prompt) {
        return proactiveRun(sessionId, agentCode, prompt,
                io.agentscope.core.permission.PermissionMode.EXPLORE, false, null, null);
    }

    /**
     * 主动消息运行（定时任务语义）：落盘系统指令 → 非流式应答 → 落盘助手消息（含本轮 token 用量）。
     * <p>自主会话无 UI：{@code permissionMode} 传 {@code EXPLORE}（只读档）时读类工具正常、
     * 副作用工具被明确拒绝；传 {@code ACCEPT_EDITS} 且 {@code autoApprove=true} 时（无人值守）
     * 确认事件仍然触发拦截链评估，但 ASK 不弹窗直接放行。</p>
     *
     * @param providerCode 模型覆盖-提供方编码（与 modelCode 成对生效，空则用智能体默认）
     * @param modelCode    模型覆盖-模型编码（与 providerCode 成对生效，空则用智能体默认）
     * @return 助手应答文本（可能为空）
     */
    public String proactiveRun(String sessionId, String agentCode, String prompt,
                               io.agentscope.core.permission.PermissionMode permissionMode,
                               boolean autoApprove, String providerCode, String modelCode) {
        ensureSession(sessionId, agentCode, prompt);
        persistMessage(sessionId, agentCode, ConversationRole.SYSTEM, prompt);
        ChatRequest req = ChatRequest.builder()
                .agentCode(agentCode)
                .sessionId(sessionId)
                .text(prompt)
                .providerCode(providerCode)
                .modelCode(modelCode)
                .permissionMode(permissionMode)
                .autoApprove(autoApprove)
                .build();
        StringBuilder buf = new StringBuilder();
        StringBuilder thinkingBuf = new StringBuilder();
        AtomicReference<TokenUsage> usage = new AtomicReference<>(TokenUsage.ZERO);
        List<ToolCallRecordCodec.ToolCallRecord> toolCalls = Collections.synchronizedList(new ArrayList<>());
        try {
            gateway.stream(req)
                    .doOnNext(event -> {
                        if (event.getType() == StreamEventType.TEXT_DELTA && event.getText() != null) {
                            buf.append(event.getText());
                        } else if (event.getType() == StreamEventType.THINKING_DELTA && event.getText() != null) {
                            thinkingBuf.append(event.getText());
                        } else if (event.getType() == StreamEventType.USAGE && event.getUsage() != null) {
                            usage.set(event.getUsage());
                        } else if (event.getType() == StreamEventType.TOOL_END) {
                            toolCalls.add(toRecord(event));
                        }
                    })
                    .blockLast();
        } catch (RuntimeException e) {
            // 自主会话出错也要保住已生成的半截应答，落盘后继续上抛给调度侧记失败
            persistAssistant(sessionId, agentCode, effectiveModelCode(req), buf.toString(), thinkingBuf.toString(),
                    usage.get(), true, toolCalls);
            throw e;
        }
        persistAssistant(sessionId, agentCode, effectiveModelCode(req), buf.toString(), thinkingBuf.toString(),
                usage.get(), false, toolCalls);
        return buf.toString();
    }

    /**
     * 直插一轮本地合成的对话（图像生成等不经过模型链路的产物）：用户消息 + 助手消息各落一条，
     * 不触发模型调用；返回助手消息主键。调用方随后按会话重放即带真实主键上屏。
     */
    public long insertLocalExchange(String sessionId, String agentCode, String userText, String assistantMarkdown) {
        ensureSession(sessionId, agentCode, userText);
        persistMessage(sessionId, agentCode, ConversationRole.USER, userText == null ? "" : userText);
        ChatConversation c = new ChatConversation();
        c.setSessionId(sessionId);
        c.setAgentCode(agentCode);
        c.setRole(ConversationRole.ASSISTANT.getCode());
        c.setContent(assistantMarkdown == null ? "" : assistantMarkdown);
        c.setCreatedAt(LocalDateTime.now());
        db.conversationDao().insert(c);
        db.sessionDao().incrementMessageCount(sessionId, 1);
        return c.getId();
    }

    /**
     * 发起一轮流式对话：落盘用户消息（含图片附件）→ 流式应答 → 落盘助手消息（含本轮累计 token 用量）。
     * <p>{@link ChatRequest} 携带消息级模型覆盖与图片附件；无附件时行为与纯文本对话一致。</p>
     */
    public Flux<StreamEvent> chat(ChatRequest req) {
        // defer + boundedElastic：把文档落盘与正文抽取（pdf/poi，可能耗时）从 FX 线程挪到订阅线程，避免界面卡死
        return Flux.defer(() -> {
            String sessionId = req.getSessionId();
            String agentCode = req.getAgentCode();
            ensureSession(sessionId, agentCode, req.getText());
            String effectiveModel = effectiveModelCode(req);
            DocPrep prep = prepareDocs(sessionId, req.getFiles());
            persistUser(req, prep.stored(), effectiveModel);
            return streamAssistant(prep.augment(req), sessionId, agentCode, effectiveModel);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 重新生成：删除会话最后一条助手消息，就同一用户请求重新流式应答（不再重复落盘用户消息）。
     * <p>调用方用最后一条用户消息的原文重建 {@link ChatRequest} 传入；若会话尚无助手消息，
     * 等价于直接发起一轮应答。</p>
     */
    public Flux<StreamEvent> regenerate(ChatRequest req) {
        String sessionId = req.getSessionId();
        String agentCode = req.getAgentCode();
        Long lastAssistantId = db.conversationDao().findLastAssistantId(sessionId);
        if (lastAssistantId != null) {
            db.conversationDao().deleteById(lastAssistantId);
            db.sessionDao().incrementMessageCount(sessionId, -1);
        }
        return streamAssistant(req, sessionId, agentCode, effectiveModelCode(req));
    }

    /**
     * 编辑重发：删除指定用户消息及其之后的全部消息（截断语义），再以新请求重新发起一轮对话。
     *
     * @param userConversationId 被编辑的那条用户消息主键
     */
    public Flux<StreamEvent> editAndResend(ChatRequest req, long userConversationId) {
        String sessionId = req.getSessionId();
        int removed = db.conversationDao().deleteFromId(sessionId, userConversationId);
        if (removed > 0) {
            db.sessionDao().incrementMessageCount(sessionId, -removed);
        }
        return chat(req);
    }

    /** 删除单条消息，并同步回退会话的消息计数（best-effort）。 */
    public void deleteMessage(String sessionId, long conversationId) {
        int removed = db.conversationDao().deleteById(conversationId);
        if (removed > 0) {
            db.sessionDao().incrementMessageCount(sessionId, -removed);
        }
        log.info("删除消息: session={} id={}", sessionId, conversationId);
    }

    /** 对已落盘用户消息的会话发起流式应答并落盘助手消息；{@code chat}/{@code regenerate} 共用的流式主体。 */
    private Flux<StreamEvent> streamAssistant(ChatRequest req, String sessionId, String agentCode, String effectiveModel) {
        StringBuilder assistantBuf = new StringBuilder();
        // 本轮思考过程（THINKING_DELTA）累积，收尾写入 metadata.thinking 供历史折叠重放
        StringBuilder thinkingBuf = new StringBuilder();
        AtomicReference<TokenUsage> usageRef = new AtomicReference<>(TokenUsage.ZERO);
        // 本轮工具调用详情（TOOL_END 事件）按发生顺序累积，收尾时写入 tool_calls 列供历史折叠重放
        List<ToolCallRecordCodec.ToolCallRecord> toolCalls = Collections.synchronizedList(new ArrayList<>());
        // 三路终态（完成/出错/取消）都会尝试落盘，settled 保证只落一次；
        // 出错与取消（用户停止）都带 interrupted 标记，半截应答不再丢失
        AtomicBoolean settled = new AtomicBoolean();
        return gateway.stream(req)
                .doOnNext(event -> {
                    if (event.getType() == StreamEventType.TEXT_DELTA && event.getText() != null) {
                        assistantBuf.append(event.getText());
                    } else if (event.getType() == StreamEventType.THINKING_DELTA && event.getText() != null) {
                        thinkingBuf.append(event.getText());
                    } else if (event.getType() == StreamEventType.USAGE && event.getUsage() != null) {
                        usageRef.set(event.getUsage());
                    } else if (event.getType() == StreamEventType.TOOL_END) {
                        toolCalls.add(toRecord(event));
                    }
                })
                .doOnComplete(() -> persistAssistant(sessionId, agentCode, effectiveModel,
                        assistantBuf.toString(), thinkingBuf.toString(), usageRef.get(), false, settled, toolCalls))
                .doOnError(e -> persistAssistant(sessionId, agentCode, effectiveModel,
                        assistantBuf.toString(), thinkingBuf.toString(), usageRef.get(), true, settled, toolCalls))
                .doOnCancel(() -> persistAssistant(sessionId, agentCode, effectiveModel,
                        assistantBuf.toString(), thinkingBuf.toString(), usageRef.get(), true, settled, toolCalls));
    }

    /** 把一条 {@link StreamEventType#TOOL_END} 事件转为落盘视图。 */
    private static ToolCallRecordCodec.ToolCallRecord toRecord(StreamEvent event) {
        return new ToolCallRecordCodec.ToolCallRecord(event.getToolName(), event.getToolInput(),
                event.getToolOutput(), event.isToolOk(), event.getToolDurationMillis());
    }

    /** 中断指定会话在途的生成：网关侧打断 Agent 循环，UI 侧取消订阅后走 doOnCancel 落盘半截内容。 */
    public void stop(String agentCode, String sessionId) {
        gateway.stop(agentCode, sessionId);
    }

    /**
     * 手动压缩指定会话的模型上下文（LLM 摘要式）：委托网关按智能体路由执行，结果持久化到会话状态。
     * <p>生成中的会话会被网关拒绝；空 Mono 表示无对应运行时或不支持压缩。</p>
     */
    public Mono<ContextCompactionResult> compact(String agentCode, String sessionId) {
        return gateway.compact(agentCode, sessionId);
    }

    /** 解析本轮实际使用的模型编码；解析器缺失或解析失败时回退消息级覆盖值。 */
    private String effectiveModelCode(ChatRequest req) {
        if (modelResolver == null) {
            return req.getModelCode();
        }
        try {
            String code = modelResolver.resolveModelCode(req.getAgentCode(), req.getProviderCode(), req.getModelCode());
            return code != null ? code : req.getModelCode();
        } catch (RuntimeException e) {
            log.warn("生效模型解析失败，回退消息级覆盖值: agent={}", req.getAgentCode(), e);
            return req.getModelCode();
        }
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

    /** 落盘用户消息：正文入 content（不含抽取的文档块），生效模型入 model_code，图片+文档附件清单入 metadata。 */
    private void persistUser(ChatRequest req, List<StoredAttachment> files, String effectiveModel) {
        List<StoredAttachment> stored = new ArrayList<>(storeImages(req.getSessionId(), req.getImages()));
        if (files != null) {
            stored.addAll(files);
        }
        ChatConversation c = new ChatConversation();
        c.setSessionId(req.getSessionId());
        c.setAgentCode(req.getAgentCode());
        c.setRole(ConversationRole.USER.getCode());
        c.setContent(req.getText() == null ? "" : req.getText());
        c.setModelCode(effectiveModel != null ? effectiveModel : req.getModelCode());
        c.setMetadata(MessageMetadataCodec.write(stored, false, null));
        c.setCreatedAt(LocalDateTime.now());
        db.conversationDao().insert(c);
        db.sessionDao().incrementMessageCount(req.getSessionId(), 1);
    }

    /** 一轮文档附件的预处理结果：落盘后的引用清单，以及拼给模型的抽取文本块。 */
    private record DocPrep(List<StoredAttachment> stored, String block) {
        /** 把抽取块追加到用户原文后，得到真正发给模型的请求；无文档则原样返回。 */
        ChatRequest augment(ChatRequest req) {
            if (block == null || block.isEmpty()) {
                return req;
            }
            String base = req.getText() == null ? "" : req.getText();
            String merged = base.isEmpty() ? block : base + "\n\n" + block;
            return req.toBuilder().text(merged).build();
        }
    }

    /** 落盘文档原件到 {@code attachments/<sessionId>/} 并抽取正文，返回引用清单与模型输入块。 */
    private DocPrep prepareDocs(String sessionId, List<FileAttachment> files) {
        List<StoredAttachment> stored = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            return new DocPrep(stored, "");
        }
        StringBuilder block = new StringBuilder();
        for (FileAttachment f : files) {
            if (f == null || f.base64Data() == null || f.base64Data().isBlank()) {
                continue;
            }
            if (f.sizeBytes() > MAX_DOC_BYTES) {
                log.warn("文档附件超过 10MB 上限，跳过: {}", f.fileName());
                continue;
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(f.base64Data());
            } catch (IllegalArgumentException e) {
                log.warn("文档附件 Base64 解码失败，跳过: {}", f.fileName());
                continue;
            }
            String relPath = null;
            try {
                String fileName = UUID.randomUUID() + docExtension(f);
                Path dir = gimPaths.attachmentsDir().resolve(sessionId);
                Files.createDirectories(dir);
                Files.write(dir.resolve(fileName), bytes);
                relPath = sessionId + "/" + fileName;
            } catch (IOException | RuntimeException e) {
                log.warn("文档附件落盘失败: {}", f.fileName(), e);
            }
            stored.add(new StoredAttachment(f.fileName(), f.mediaType(), relPath,
                    StoredAttachment.KIND_FILE, f.sizeBytes()));
            String text = FileTextExtractor.extract(f.fileName(), f.mediaType(), bytes);
            block.append("【附件文档：").append(f.fileName()).append("】\n");
            block.append(text == null || text.isBlank() ? "（未能抽取文本）" : text).append("\n\n");
        }
        String trimmed = block.toString().trim();
        return new DocPrep(stored, trimmed);
    }

    /** 文档原件落盘用的扩展名：优先取文件名后缀，兜底 .bin。 */
    private static String docExtension(FileAttachment f) {
        String name = f.fileName();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot);
                if (ext.length() <= 8) {
                    return ext;
                }
            }
        }
        return ".bin";
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

    private void persistAssistant(String sessionId, String agentCode, String modelCode, String assistantText, String thinking,
                                  TokenUsage usage, boolean interrupted, List<ToolCallRecordCodec.ToolCallRecord> toolCalls) {
        persistAssistant(sessionId, agentCode, modelCode, assistantText, thinking, usage, interrupted, new AtomicBoolean(), toolCalls);
    }

    private void persistAssistant(String sessionId, String agentCode, String modelCode, String assistantText, String thinking,
                                  TokenUsage usage, boolean interrupted, AtomicBoolean settled,
                                  List<ToolCallRecordCodec.ToolCallRecord> toolCalls) {
        if (settled != null && !settled.compareAndSet(false, true)) {
            return;
        }
        List<ToolCallRecordCodec.ToolCallRecord> toolSnapshot =
                toolCalls == null ? List.of() : new ArrayList<>(toolCalls);
        boolean hasThinking = thinking != null && !thinking.isBlank();
        if ((assistantText == null || assistantText.isEmpty()) && toolSnapshot.isEmpty() && !hasThinking) {
            // 一个字都没生成就被中断且无工具调用、无思考：不落空行，会话里不出现空气泡
            return;
        }
        ChatConversation c = new ChatConversation();
        c.setSessionId(sessionId);
        c.setAgentCode(agentCode);
        c.setRole(ConversationRole.ASSISTANT.getCode());
        c.setContent(assistantText == null ? "" : assistantText);
        c.setModelCode(modelCode);
        // 仅当本轮确有工具调用时才写 tool_calls 列，否则留 null
        if (!toolSnapshot.isEmpty()) {
            c.setToolCalls(ToolCallRecordCodec.encode(toolSnapshot));
        }
        // 仅当本轮确有模型用量回报时才写列，否则留 null（区分「0」与「未采集」）
        if (usage != null && !usage.isEmpty()) {
            c.setTokensInput(usage.inputTokens());
            c.setTokensOutput(usage.outputTokens());
            c.setTokensCached(usage.cachedTokens());
            c.setLlmCalls(usage.calls());
            c.setLlmElapsedMillis(usage.elapsedMillis());
        }
        c.setCreatedAt(LocalDateTime.now());
        // 附件/中断/思考统一入 metadata（无思考的普通完成轮与原先一致，列留空）
        c.setMetadata(MessageMetadataCodec.write(List.of(), interrupted, thinking));
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

    /** 历史重放分页大小：切换会话只渲染最新一页，滚动到顶再加载更早，避免长会话全量上屏 */
    public static final int HISTORY_PAGE_SIZE = 50;

    /** 分页读历史首页：最新 {@code limit} 条（按 id 升序返回） */
    public java.util.List<ChatConversation> recentHistory(String sessionId, int limit) {
        return db.conversationDao().findRecentBySession(sessionId, limit);
    }

    /** 分页读历史上页：{@code beforeId} 之前的 {@code limit} 条（按 id 升序返回） */
    public java.util.List<ChatConversation> historyBefore(String sessionId, long beforeId, int limit) {
        return db.conversationDao().findPageBefore(sessionId, beforeId, limit);
    }

    /** 消息全文检索（排除系统消息）：供搜索面板的「消息内容」分组。 */
    public java.util.List<com.gettyio.gim.agent.domain.entity.MessageHit> searchContent(String keyword, int limit) {
        return db.conversationDao().searchContent(keyword, limit);
    }

    /**
     * 取指定模型每千 token 的单价 {@code {输入, 输出}}；模型不存在或未标注价格时返回 {@code null}，
     * 调用层据此只显示 token 不显示金额。
     */
    public double[] modelCostPer1k(String modelCode) {
        if (modelCode == null || modelCode.isBlank()) {
            return null;
        }
        return db.modelConfigDao().findByCode(modelCode)
                .filter(m -> m.getCostPer1kInput() != null || m.getCostPer1kOutput() != null)
                .map(m -> new double[]{
                        m.getCostPer1kInput() == null ? 0d : m.getCostPer1kInput(),
                        m.getCostPer1kOutput() == null ? 0d : m.getCostPer1kOutput()})
                .orElse(null);
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

    /** 置顶/取消置顶：不刷新 updated_at（排序靠 DAO 的 {@code pinned DESC}）。 */
    public void setPinned(String sessionId, boolean pinned) {
        db.sessionDao().updatePinned(sessionId, pinned);
        log.info("{}会话: {}", pinned ? "置顶" : "取消置顶", sessionId);
    }

    /** 收藏/取消收藏：仅作为侧栏星标标记，不影响排序。 */
    public void setFavorite(String sessionId, boolean favorite) {
        db.sessionDao().updateFavorite(sessionId, favorite);
        log.info("{}会话: {}", favorite ? "收藏" : "取消收藏", sessionId);
    }

    /**
     * 分支（分叉）：把源会话连同其全部消息复制为一个新的独立会话，返回新 sessionId。
     * <p>新会话记录 {@code parent_session_id} 溯源，标题加「（分支）」；消息按原序重放，
     * 图片/文档引用仍指向源会话的附件目录（原件未清理时缩略图照常可见）。侧栏只到会话粒度，
     * 故分支点即源会话的最新状态。</p>
     */
    public String branchSession(String sessionId) {
        return forkSession(sessionId, 0L);
    }

    /**
     * 从指定消息分支：复制源会话至 {@code messageId}（含该条）为止的消息到新独立会话，返回新 sessionId。
     * <p>新会话记录 {@code parent_session_id} 溯源，标题加「（分支）」，源会话原样保留；
     * {@code messageId} 传非正数即 {@link #branchSession} 的整会话语义。
     * 图片/文档引用仍指向源会话的附件目录（原件未清理时缩略图照常可见）。</p>
     *
     * @param messageId 截止的 conversations 主键（含）；找不到该主键时退化全量复制
     */
    public String forkSession(String sessionId, long messageId) {
        ChatSession src = db.sessionDao().findBySessionId(sessionId).orElse(null);
        if (src == null) {
            return sessionId;
        }
        String newId = UUID.randomUUID().toString();
        ChatSession copy = new ChatSession();
        copy.setSessionId(newId);
        copy.setAgentCode(src.getAgentCode());
        copy.setGroupId(src.getGroupId());
        copy.setTitle(deriveBranchTitle(src.getTitle()));
        copy.setModelCode(src.getModelCode());
        copy.setStatus(SessionStatus.ACTIVE.name());
        copy.setMessageCount(0);
        copy.setParentSessionId(sessionId);
        db.sessionDao().insert(copy);

        List<ChatConversation> messages = db.conversationDao().findBySession(sessionId);
        int copied = 0;
        for (ChatConversation m : messages) {
            copyMessage(newId, m);
            copied++;
            if (messageId > 0 && m.getId() != null && m.getId() == messageId) {
                break;
            }
        }
        if (copied > 0) {
            db.sessionDao().incrementMessageCount(newId, copied);
        }
        log.info("分支会话: {} -> {}（复制 {}/{} 条消息，截止 id={}）",
                sessionId, newId, copied, messages.size(), messageId);
        return newId;
    }

    /** 单条消息跨会话复制：只拷会话内容与用量/元数据，主键与原时间戳不随行。 */
    private void copyMessage(String newId, ChatConversation m) {
        ChatConversation c = new ChatConversation();
        c.setSessionId(newId);
        c.setAgentCode(m.getAgentCode());
        c.setRole(m.getRole());
        c.setContent(m.getContent());
        c.setTokensInput(m.getTokensInput());
        c.setTokensOutput(m.getTokensOutput());
        c.setTokensCached(m.getTokensCached());
        c.setLlmCalls(m.getLlmCalls());
        c.setLlmElapsedMillis(m.getLlmElapsedMillis());
        c.setModelCode(m.getModelCode());
        c.setToolCalls(m.getToolCalls());
        c.setMetadata(m.getMetadata());
        c.setCreatedAt(LocalDateTime.now());
        db.conversationDao().insert(c);
    }

    /** 分支标题：原标题去空白后追加「（分支）」，仍受 {@link #TITLE_MAX_LEN} 截断。 */
    private static String deriveBranchTitle(String base) {
        String t = base == null ? "" : base.trim();
        if (t.isEmpty()) {
            t = "新会话";
        }
        String suffix = "（分支）";
        int max = TITLE_MAX_LEN - suffix.length();
        if (t.length() > max) {
            t = t.substring(0, max);
        }
        return t + suffix;
    }

    // ===================== 会话分组（纯 UI 组织层，归属到智能体） =====================

    /**
     * 在指定智能体下新建分组：trim+截断到 {@link #GROUP_NAME_MAX_LEN}，空名回退「新分组」，立即落盘并返回 groupId。
     */
    public String createGroup(String agentCode, String name) {
        String groupId = UUID.randomUUID().toString();
        SessionGroup group = new SessionGroup();
        group.setGroupId(groupId);
        group.setAgentCode(agentCode);
        group.setName(normalizeGroupName(name));
        db.sessionGroupDao().insert(group);
        log.info("新建分组: {} ({}) agent={}", groupId, group.getName(), agentCode);
        return groupId;
    }

    /** 重命名分组：去首尾空白、超长截断后写库。 */
    public void renameGroup(String groupId, String name) {
        db.sessionGroupDao().rename(groupId, normalizeGroupName(name));
        log.info("重命名分组: {} -> {}", groupId, name);
    }

    /**
     * 删除分组：先把组内会话退回未分组（{@code clearGroup}），再删分组定义。
     * <p>绝不删会话——分组只是组织层，删组等于把里面的东西放回原处。</p>
     */
    public void deleteGroup(String groupId) {
        db.sessionDao().clearGroup(groupId);
        db.sessionGroupDao().deleteByGroupId(groupId);
        log.info("删除分组: {}（组内会话已退回未分组）", groupId);
    }

    /** 移动会话到分组：{@code groupId} 传 null 即移出分组。不刷新 updated_at，避免打乱最近排序。 */
    public void moveSessionToGroup(String sessionId, String groupId) {
        db.sessionDao().updateGroup(sessionId, groupId);
    }

    /** 某智能体下的全部分组（按创建顺序）。 */
    public List<SessionGroup> listGroups(String agentCode) {
        return db.sessionGroupDao().findByAgent(agentCode);
    }

    /** 分组名规范化：空白回退「新分组」，超长截断到 {@link #GROUP_NAME_MAX_LEN}。 */
    private static String normalizeGroupName(String name) {
        if (name == null || name.isBlank()) {
            return "新分组";
        }
        String t = name.trim();
        return t.length() > GROUP_NAME_MAX_LEN ? t.substring(0, GROUP_NAME_MAX_LEN) : t;
    }
}
