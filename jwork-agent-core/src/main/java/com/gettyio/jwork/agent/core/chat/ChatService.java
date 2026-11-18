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

package com.gettyio.jwork.agent.core.chat;

import com.gettyio.jwork.agent.core.JworkPaths;
import com.gettyio.jwork.agent.core.capability.CapabilityCodec;
import com.gettyio.jwork.agent.core.doc.TabularInspector;
import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.entity.AgentDefinition;
import com.gettyio.jwork.agent.domain.entity.ChatConversation;
import com.gettyio.jwork.agent.domain.entity.ChatSession;
import com.gettyio.jwork.agent.domain.entity.SessionGroup;
import com.gettyio.jwork.agent.domain.entity.SkillPackage;
import com.gettyio.jwork.agent.domain.enums.ConversationRole;
import com.gettyio.jwork.agent.domain.enums.SessionStatus;
import com.gettyio.jwork.agent.domain.enums.StreamEventType;
import com.gettyio.jwork.agent.domain.util.FileKinds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 对话服务：在流式网关两侧补齐 SQLite 落盘（会话 + 消息），实现重启不丢历史。
 * <p>chat-history.jsonl 由 Harness 工作区自行维护，这里负责应用级会话记录。</p>
 * <p>会话只与智能体关联（{@code sessions.agent_code}）。</p>
 *
 * @author gogym
 */
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 会话标题长度上限：自动取名与手动重命名共用一个口径（UI 也拿它限输入框） */
    public static final int TITLE_MAX_LEN = 30;

    /** 分组名长度上限：与标题同口径（UI 也拿它限输入框） */
    public static final int GROUP_NAME_MAX_LEN = 30;

    /** 单个文档附件的字节上限（50MB）：超过不落盘不抽取。 */
    private static final long MAX_DOC_BYTES = 50L * 1024 * 1024;

    /** 不展开正文的字节线（20MB）：超过只给路径，让模型按需用工具/脚本取数。 */
    private static final long BODY_DUMP_MAX_BYTES = 20L * 1024 * 1024;

    /** 摘要模式下非表格文档的正文摘录长度（字符）。 */
    private static final int SUMMARY_HEAD_CHARS = 3_000;

    /** 附件注入模式设置键：{@code summary}（默认）/ {@code full}（历史行为，全文注入）。 */
    public static final String SETTING_ATTACHMENT_MODE = "chat.attachment.mode";

    /** 摘要注入：结构 + 路径 + 取数指引。 */
    public static final String ATTACHMENT_MODE_SUMMARY = "summary";

    /** 全文注入：抽到的正文整份进上下文（小文档、需要逐字对照时用）。 */
    public static final String ATTACHMENT_MODE_FULL = "full";

    /** 自主运行静默上限（分钟）设置键：定时任务无人可中断，连接卡死时必须有界限。 */
    public static final String SETTING_PROACTIVE_IDLE_TIMEOUT = "agent.task.idleTimeoutMinutes";

    /**
     * 自主运行缺省静默上限（分钟）：计时口径是「两次事件之间的间隔」而非整轮总时长，
     * 所以长任务照常跑，只有真正断流才判卡死。
     */
    public static final long DEFAULT_PROACTIVE_IDLE_TIMEOUT_MINUTES = 15;

    /**
     * 在途应答写检查点的间隔：进程被杀或崩溃后，一轮生成最多丢这一段间隔里的产出
     * （原先只有收到终态信号才落盘，进程没了整轮就没了）。
     * <p>每次覆盖写的是整段已生成内容，所以取「够小以少丢、够大以不挡流式」的折中；
     * SQLite WAL 下这条写入不在界面线程上。</p>
     */
    private static final Duration DRAFT_CHECKPOINT_INTERVAL = Duration.ofSeconds(3);

    private final AgentGateway gateway;
    private final JworkDatabase db;
    private final JworkPaths jworkPaths;
    private final EffectiveModelResolver modelResolver;

    /**
     * 检查点间隔的实例副本：默认取 {@link #DRAFT_CHECKPOINT_INTERVAL}，
     * 单测把它调小才能在毫秒级观察到「跑到一半落了草稿」（见 {@link #setDraftCheckpointInterval}）。
     */
    private volatile Duration draftCheckpointInterval = DRAFT_CHECKPOINT_INTERVAL;

    /**
     * 会话销毁时的附带清理（组合根注入）：核心模块不反向依赖工具层，
     * 会话级授权记忆由这里回调清除。
     */
    private volatile Consumer<String> sessionCleanup;

    public ChatService(AgentGateway gateway, JworkDatabase db, JworkPaths jworkPaths,
                       EffectiveModelResolver modelResolver) {
        this.gateway = gateway;
        this.db = db;
        this.jworkPaths = jworkPaths;
        this.modelResolver = modelResolver;
    }

    /** 注入会话销毁附带清理钩子（传 null 表示不需要）。 */
    public void setSessionCleanup(Consumer<String> sessionCleanup) {
        this.sessionCleanup = sessionCleanup;
    }

    /** 调检查点间隔（仅供单测把「跑到一半落草稿」压到毫秒级观察；传 null 回退默认）。 */
    void setDraftCheckpointInterval(Duration interval) {
        this.draftCheckpointInterval = interval == null ? DRAFT_CHECKPOINT_INTERVAL : interval;
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
     * <p>整轮按「两次事件之间的间隔」计静默上限（缺省
     * {@value #DEFAULT_PROACTIVE_IDLE_TIMEOUT_MINUTES} 分钟，经 {@value #SETTING_PROACTIVE_IDLE_TIMEOUT} 调整）：
     * 断流即中断抛错，已生成的半截应答照常落盘。</p>
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
        Duration idleLimit = Duration.ofMinutes(proactiveIdleTimeoutMinutes());
        try {
            withIdleLimit(gateway.stream(req), idleLimit)
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
     * 给没有 UI 的一轮加静默上限：两次事件之间超过 {@code idleLimit} 即中断（Reactor 顺带取消上游，
     * 不会让这条后台线程永远等下去，定时任务的调度线程池就是这样被一个个占死的），
     * 并把 {@link java.util.concurrent.TimeoutException} 换成一句人话。
     * <p>只包自主运行这一条链：交互对话有停止按钮，套上限反而会把用户自己的长任务掐断。</p>
     */
    static Flux<StreamEvent> withIdleLimit(Flux<StreamEvent> source, Duration idleLimit) {
        return source.timeout(idleLimit)
                .onErrorMap(java.util.concurrent.TimeoutException.class, e -> new IllegalStateException(
                        "本轮超过 " + (idleLimit.toMinutes() >= 1
                                ? idleLimit.toMinutes() + " 分钟" : idleLimit.toSeconds() + " 秒")
                                + "无响应，已中断", e));
    }

    /** 自主运行的静默上限（分钟）：设置键非正数或不可解析时回退 {@value #DEFAULT_PROACTIVE_IDLE_TIMEOUT_MINUTES}。 */
    private long proactiveIdleTimeoutMinutes() {
        Optional<String> raw = db.settingDao().getValue(SETTING_PROACTIVE_IDLE_TIMEOUT);
        if (raw.isPresent()) {
            try {
                long minutes = Long.parseLong(raw.get().trim());
                if (minutes > 0) {
                    return minutes;
                }
            } catch (NumberFormatException ignored) {
                // 脏值按缺省处理，不让一次手改设置打断所有定时任务
            }
        }
        return DEFAULT_PROACTIVE_IDLE_TIMEOUT_MINUTES;
    }

    /**
     * 直插一轮本地合成的对话（图像生成等不经过模型链路的产物）：用户消息 + 助手消息各落一条，
     * 不触发模型调用；返回助手消息主键。调用方随后按会话重放即带真实主键上屏。
     */
    public long insertLocalExchange(String sessionId, String agentCode, String userText, String assistantMarkdown) {
        ensureSession(sessionId, agentCode, userText);
        // 一问一答同事务：中途失败只留半个来回的话，会话里会出现没有回应、或回应没有提问的气泡
        return db.callInTransaction(() -> {
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
        });
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
            truncate(sessionId, () -> db.conversationDao().deleteById(lastAssistantId));
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
        truncate(sessionId, () -> db.conversationDao().deleteFromId(sessionId, userConversationId));
        return chat(req);
    }

    /** 删除单条消息，并同步回退会话的消息计数。 */
    public void deleteMessage(String sessionId, long conversationId) {
        truncate(sessionId, () -> db.conversationDao().deleteById(conversationId));
        log.info("删除消息: session={} id={}", sessionId, conversationId);
    }

    /**
     * 删消息与回退计数同事务：默认每语句一个连接，删成功而计数没回退（或反之）就把
     * {@code message_count} 永久写歪——侧栏会话列表、用量统计都读它。
     *
     * @param delete 删除动作，返回实际删除行数
     */
    private void truncate(String sessionId, Supplier<Integer> delete) {
        db.runInTransaction(() -> {
            int removed = delete.get();
            if (removed > 0) {
                db.sessionDao().incrementMessageCount(sessionId, -removed);
            }
        });
    }

    /** 对已落盘用户消息的会话发起流式应答并落盘助手消息；{@code chat}/{@code regenerate} 共用的流式主体。 */
    private Flux<StreamEvent> streamAssistant(ChatRequest req, String sessionId, String agentCode, String effectiveModel) {
        StringBuilder assistantBuf = new StringBuilder();
        // 本轮思考过程（THINKING_DELTA）累积，收尾写入 metadata.thinking 供旧版扁平重放
        StringBuilder thinkingBuf = new StringBuilder();
        AtomicReference<TokenUsage> usageRef = new AtomicReference<>(TokenUsage.ZERO);
        // 本轮工具调用详情（TOOL_END 事件）按发生顺序累积，收尾时写入 tool_calls 列供历史折叠重放
        List<ToolCallRecordCodec.ToolCallRecord> toolCalls = Collections.synchronizedList(new ArrayList<>());
        // 时序分段：本轮真相的唯一来源，流式与重放共用同一段序（见 Segments）
        Segments segs = new Segments();
        // 三路终态（完成/出错/取消）都会尝试落盘，settled 保证只落一次（取消与完成可能竞速）；
        // 出错与取消（用户停止）都带 interrupted 标记，半截应答不再丢失
        AtomicBoolean settled = new AtomicBoolean();
        // 下一次检查点的墙钟：事件串行到达，用 AtomicLong 只为在 lambda 里可写，不承担并发语义
        Duration interval = draftCheckpointInterval;
        AtomicLong nextDraftNs = new AtomicLong(System.nanoTime() + interval.toNanos());
        Consumer<Boolean> settle = interrupted -> persistAssistant(sessionId, agentCode, effectiveModel,
                assistantBuf.toString(), thinkingBuf.toString(), usageRef.get(), interrupted, settled,
                toolCalls, segs.finish());
        return gateway.stream(req)
                .doOnNext(event -> {
                    if (event.getType() == StreamEventType.TEXT_DELTA && event.getText() != null) {
                        assistantBuf.append(event.getText());
                        segs.text(event.getText());
                    } else if (event.getType() == StreamEventType.THINKING_DELTA && event.getText() != null) {
                        thinkingBuf.append(event.getText());
                        segs.thinking(event.getText());
                    } else if (event.getType() == StreamEventType.USAGE && event.getUsage() != null) {
                        usageRef.set(event.getUsage());
                    } else if (event.getType() == StreamEventType.TOOL_END) {
                        ToolCallRecordCodec.ToolCallRecord record = toRecord(event);
                        toolCalls.add(record);
                        segs.tools(record);
                    } else if (event.getType() == StreamEventType.NOTICE) {
                        segs.notice(event.getToolName(), event.getText());
                    }
                    long now = System.nanoTime();
                    if (now >= nextDraftNs.get()) {
                        nextDraftNs.set(now + interval.toNanos());
                        draft(sessionId, agentCode, effectiveModel, assistantBuf.toString(),
                                thinkingBuf.toString(), usageRef.get(), toolCalls, segs.peek());
                    }
                })
                .doOnComplete(() -> settle.accept(false))
                .doOnError(e -> settle.accept(true))
                .doOnCancel(() -> settle.accept(true));
    }

    /**
     * 把在途应答的当前半截覆盖写进草稿表，供进程被杀/崩溃后重启时提升为一条中断消息。
     * <p>写失败只记日志：检查点是保险，不能反过来打断正在跑的这轮应答。</p>
     */
    private void draft(String sessionId, String agentCode, String modelCode, String text, String thinking,
                       TokenUsage usage, List<ToolCallRecordCodec.ToolCallRecord> toolCalls,
                       List<ChatSegmentCodec.ChatSegment> segments) {
        if (text.isEmpty() && thinking.isBlank() && toolCalls.isEmpty()) {
            // 还没产出可恢复的东西，别在库里留一条空气泡的草稿
            return;
        }
        try {
            db.draftDao().save(assistantRow(sessionId, agentCode, modelCode, text, thinking, usage,
                    true, toolCalls, segments));
        } catch (RuntimeException e) {
            log.warn("在途应答检查点写入失败: session={}", sessionId, e);
        }
    }

    /**
     * 提升上次未跑完的在途应答：每条草稿变成一条 {@code interrupted} 助手消息，接在它所在会话的
     * 历史末尾。须在启动工序里调用——那时不可能还有正在生成的轮次，提升不会与实时缓冲撞车。
     *
     * @return 实际提升的条数
     */
    public int recoverInterruptedDrafts() {
        int recovered = 0;
        for (ChatConversation item : db.draftDao().findAll()) {
            String sessionId = item.getSessionId();
            try {
                if (db.sessionDao().findBySessionId(sessionId).isEmpty()) {
                    // 会话已被删除：半截应答无人认领，清掉草稿别让它每启动都重来一遍
                    db.draftDao().delete(sessionId);
                    continue;
                }
                db.runInTransaction(() -> {
                    db.conversationDao().insert(item);
                    db.sessionDao().incrementMessageCount(sessionId, 1);
                    db.draftDao().delete(sessionId);
                });
                recovered++;
            } catch (RuntimeException e) {
                log.warn("在途应答提升失败: session={}", sessionId, e);
            }
        }
        if (recovered > 0) {
            log.info("上次未跑完的应答已恢复为中断消息: {} 条", recovered);
        }
        return recovered;
    }

    /**
     * 时序分段累加器：与尾段同类型则合并（文本/思考拼接、工具累积），否则新开一段。
     * 思考段按墙钟记录耗时——该段一结束（切到别的类型或本轮收尾）就把毫秒数补记上去，
     * 渲染层据此只露「思考 · Ns」一行，点开才给全文。
     * <p>事件流串行到达，合并逻辑无需额外同步。</p>
     */
    private static final class Segments {

        private final List<ChatSegmentCodec.ChatSegment> list = new ArrayList<>();
        /** 在途思考段的起点（纳秒）；0 表示当前没有思考段在累积。 */
        private long thinkingStartNs;

        void text(String value) {
            closeThinking();
            append(ChatSegmentCodec.ChatSegment.ofText(value));
        }

        void thinking(String value) {
            if (thinkingStartNs == 0) {
                thinkingStartNs = System.nanoTime();
            }
            append(ChatSegmentCodec.ChatSegment.ofThinking(value));
        }

        void tools(ToolCallRecordCodec.ToolCallRecord record) {
            closeThinking();
            append(ChatSegmentCodec.ChatSegment.ofTools(List.of(record)));
        }

        void notice(String code, String args) {
            closeThinking();
            if (code != null && !code.isBlank()) {
                append(ChatSegmentCodec.ChatSegment.ofNotice(code, args));
            }
        }

        /** 本轮终态：补记末段思考耗时后给出不可变快照。 */
        List<ChatSegmentCodec.ChatSegment> finish() {
            closeThinking();
            return List.copyOf(list);
        }

        /**
         * 在途快照（写检查点用）：段序与 {@link #finish()} 一致，但<b>不结束</b>在途思考段——
         * 本轮还在跑，不能因为一次落盘就把同一段思考断开重计。
         * <p>末段若还是正在累积的思考（{@code thinkingStartNs != 0} 时必然如此），
         * 按已用时补一个时长再快照，免得提升上来的半截露出一条无耗时的思考条。</p>
         */
        List<ChatSegmentCodec.ChatSegment> peek() {
            if (thinkingStartNs == 0 || list.isEmpty()) {
                return List.copyOf(list);
            }
            int last = list.size() - 1;
            List<ChatSegmentCodec.ChatSegment> copy = new ArrayList<>(list);
            copy.set(last, copy.get(last).withDuration((System.nanoTime() - thinkingStartNs) / 1_000_000L));
            return List.copyOf(copy);
        }

        /** 结束在途思考段：思考总是紧邻其后的那一段，故只需回看末段。 */
        private void closeThinking() {
            if (thinkingStartNs == 0) {
                return;
            }
            long millis = (System.nanoTime() - thinkingStartNs) / 1_000_000L;
            thinkingStartNs = 0;
            int last = list.size() - 1;
            if (last >= 0 && ChatSegmentCodec.ChatSegment.THINKING.equals(list.get(last).type())) {
                list.set(last, list.get(last).withDuration(millis));
            }
        }

        private void append(ChatSegmentCodec.ChatSegment next) {
            if (list.isEmpty()) {
                list.add(next);
                return;
            }
            ChatSegmentCodec.ChatSegment last = list.get(list.size() - 1);
            // 旁白一段一事，永不合并：相邻两条的入参拼在一起会读不回原意
            if (!last.type().equals(next.type()) || ChatSegmentCodec.ChatSegment.NOTICE.equals(next.type())) {
                list.add(next);
                return;
            }
            if (ChatSegmentCodec.ChatSegment.TOOLS.equals(last.type())) {
                List<ToolCallRecordCodec.ToolCallRecord> merged = new ArrayList<>(last.tools());
                merged.addAll(next.tools());
                list.set(list.size() - 1, ChatSegmentCodec.ChatSegment.ofTools(merged));
            } else {
                list.set(list.size() - 1, new ChatSegmentCodec.ChatSegment(
                        last.type(), last.text() + next.text(), null, last.durationMillis(), last.noticeCode()));
            }
        }
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

    /** 内置工具集 schema 体量经验估值（token）：约 15 个内置工具×约 90 token，仅供分类估算。 */
    private static final int BUILTIN_TOOL_TOKENS = 1400;
    /** 每个 MCP 服务的工具 schema 体量经验估值（token）：约 5 工具×约 100 token；真实 schema 需连接后才拿得到。 */
    private static final int MCP_SERVER_TOOL_TOKENS = 500;
    /** 分类估算的字符→token 系数：与 UI 占用条兜底粗估同口径。 */
    private static final double BREAKDOWN_CHARS_PER_TOKEN = 2.5;

    /**
     * 上下文占用的分类估算（供占用浮层分段条/图例）：系统提示词读智能体定义与工作区注入文档，
     * 工具按内置经验值 + 声明的 MCP 服务数叠加，技能按白名单内名称+描述体量，消息按历史字符粗估。
     * <p>全程只读库与磁盘小文件，不装配运行体、不连 MCP、不耗模型；调用方应在后台线程调用。
     * 各项为独立粗估，UI 展示前按占用条总量等比缩放以保证百分比自洽。</p>
     *
     * @param agentCode    会话归属智能体；null 时系统/工具/技能项按 0 估
     * @param historyChars 会话历史总字符数（调用方扫描历史时已算好，避免重复读库）
     */
    public ContextBreakdown contextBreakdown(String agentCode, long historyChars) {
        AgentDefinition def = agentCode == null ? null
                : db.agentDao().findByCode(agentCode).orElse(null);
        Path ws = gateway.workspaceForAgent(agentCode);
        String prompt = def == null ? null : def.getSystemPrompt();
        long sysChars = prompt != null && !prompt.isBlank() ? prompt.length() : readChars(ws, "AGENTS.md");
        // 工作区注入文档（与 WorkspaceContextMiddleware 同源）：知识索引与长期记忆
        sysChars += readChars(ws, "KNOWLEDGE.md") + readChars(ws, "MEMORY.md");
        int mcpCount = def == null ? 0 : CapabilityCodec.readCodes(def.getMcpServersJson()).size();
        List<String> skillCodes = def == null ? List.of() : CapabilityCodec.readCodes(def.getSkillsJson());
        long skillChars = 0;
        for (String code : skillCodes) {
            SkillPackage sp = db.skillDao().findByCode(code).orElse(null);
            if (sp != null) {
                skillChars += (sp.getSkillName() == null ? 0 : sp.getSkillName().length())
                        + (sp.getDescription() == null ? 0 : sp.getDescription().length());
            }
        }
        return new ContextBreakdown(
                (int) Math.round(sysChars / BREAKDOWN_CHARS_PER_TOKEN),
                BUILTIN_TOOL_TOKENS + mcpCount * MCP_SERVER_TOOL_TOKENS,
                (int) Math.round(skillChars / BREAKDOWN_CHARS_PER_TOKEN),
                skillCodes.size(),
                (int) Math.round(historyChars / BREAKDOWN_CHARS_PER_TOKEN));
    }

    /** 读工作区注入文档的字符数；无工作区/文件不存在/读失败均按 0（估算不值得为此报错）。 */
    private static long readChars(Path workspace, String fileName) {
        if (workspace == null) {
            return 0;
        }
        try {
            Path f = workspace.resolve(fileName);
            return Files.exists(f) ? Files.readString(f).length() : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
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
        db.runInTransaction(() -> {
            db.conversationDao().insert(c);
            db.sessionDao().incrementMessageCount(sessionId, 1);
        });
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
        db.runInTransaction(() -> {
            db.conversationDao().insert(c);
            db.sessionDao().incrementMessageCount(req.getSessionId(), 1);
        });
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

    /**
     * 落盘文档原件到 {@code attachments/<sessionId>/} 并生成注入块，返回引用清单与模型输入块。
     * <p>存储名保留原件名（加序号前缀防重）：模型要靠文件名辨认「文件1 / 文件2」，
     * 全 UUID 名会让两个附件在它眼里长成同一个样子，也无从按路径取数。</p>
     */
    private DocPrep prepareDocs(String sessionId, List<FileAttachment> files) {
        List<StoredAttachment> stored = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            return new DocPrep(stored, "");
        }
        StringBuilder block = new StringBuilder();
        Path dir = jworkPaths.attachmentsDir().resolve(sessionId);
        int index = 0;
        for (FileAttachment f : files) {
            if (f == null || f.base64Data() == null || f.base64Data().isBlank()) {
                continue;
            }
            if (f.sizeBytes() > MAX_DOC_BYTES) {
                log.warn("文档附件超过 50MB 上限，跳过: {}", f.fileName());
                continue;
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(f.base64Data());
            } catch (IllegalArgumentException e) {
                log.warn("文档附件 Base64 解码失败，跳过: {}", f.fileName());
                continue;
            }
            index++;
            String relPath = null;
            Path target = null;
            try {
                Files.createDirectories(dir);
                target = uniqueTarget(dir, storeName(index, f));
                Files.write(target, bytes);
                relPath = sessionId + "/" + target.getFileName();
            } catch (IOException | RuntimeException e) {
                log.warn("文档附件落盘失败: {}", f.fileName(), e);
                target = null;
            }
            stored.add(new StoredAttachment(f.fileName(), f.mediaType(), relPath,
                    StoredAttachment.KIND_FILE, f.sizeBytes()));
            block.append(attachmentBlock(f, target, bytes)).append("\n\n");
        }
        String trimmed = block.toString().trim();
        return new DocPrep(stored, trimmed);
    }

    /**
     * 单个附件的注入块：路径 + 结构摘要（表格）或正文（小文档/全文模式）。
     * <p>默认只给「看得见的形状」，把取数交给 {@code inspect_file} 与脚本——几十万行的导出表
     * 整份塞进上下文，后面每一步都在为这段一次性文本付窗口与注意力。</p>
     */
    private String attachmentBlock(FileAttachment f, Path target, byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("【附件】").append(f.fileName()).append("（").append(readableBytes(f.sizeBytes())).append("）\n");
        if (target == null) {
            sb.append("（原件落盘失败，仅以下内容可用）\n");
            appendBody(sb, f, bytes, SUMMARY_HEAD_CHARS);
            return sb.toString();
        }
        sb.append("本机路径：").append(target.toAbsolutePath()).append('\n');
        boolean tabular = TabularInspector.isTabular(f.fileName());
        if (tabular) {
            String overview = TabularInspector.overview(target.toAbsolutePath(), TabularInspector.SAMPLE_ROWS);
            if (overview != null && !overview.isBlank()) {
                sb.append(overview);
            } else {
                sb.append("（未能读取表格结构，可能是加密或损坏）\n");
            }
            if (fullTextMode()) {
                appendBody(sb, f, bytes, Integer.MAX_VALUE);
            } else {
                sb.append("分片取数：inspect_file(path=上述路径, sheet=工作表名或序号, offset=起始行(0基), rows=行数)；")
                        .append("全量统计或产出文件请用 execute 跑 Python(pandas/openpyxl) 读取该路径。\n");
            }
            return sb.toString();
        }
        if (f.sizeBytes() > BODY_DUMP_MAX_BYTES) {
            sb.append("文件较大，未展开正文；请用 read_file 分段读取上述路径，或用 execute 跑脚本处理。\n");
            return sb.toString();
        }
        appendBody(sb, f, bytes, fullTextMode() ? Integer.MAX_VALUE : SUMMARY_HEAD_CHARS);
        return sb.toString();
    }

    /** 附件是否走全文注入：实时读 AppSetting，改设置不重启即生效；非法值按摘要处理。 */
    private boolean fullTextMode() {
        return db.settingDao().getValue(SETTING_ATTACHMENT_MODE)
                .map(v -> ATTACHMENT_MODE_FULL.equalsIgnoreCase(v.trim()))
                .orElse(false);
    }

    /** 追加抽取正文：短文档整份给，长文档只给头部并说明怎么读全文。 */
    private static void appendBody(StringBuilder sb, FileAttachment f, byte[] bytes, int limitChars) {
        if (limitChars <= 0) {
            return;
        }
        String text = FileTextExtractor.extract(f.fileName(), f.mediaType(), bytes);
        if (text == null || text.isBlank()) {
            sb.append("（未能抽取文本）\n");
            return;
        }
        if (text.length() <= limitChars) {
            sb.append(text).append('\n');
            return;
        }
        sb.append(text, 0, limitChars).append('\n');
        sb.append("…（正文共 ").append(text.length()).append(" 字符，未全部展开；整份内容请用 read_file 读取上述路径）\n");
    }

    /** 存储文件名：{@code <序号>-<净化后的原名><扩展名>}，保留原名可辨识（附件常被称作「文件1」「文件2」）。 */
    private static String storeName(int index, FileAttachment f) {
        String name = f.fileName() == null ? "" : f.fileName().trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String safe = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (safe.length() > 60) {
            safe = safe.substring(0, 60);
        }
        if (safe.isBlank()) {
            safe = "file";
        }
        return index + "-" + safe + docExtension(f);
    }

    /** 同名不覆盖：已存在则在原名后追加短随机后缀（多轮对话里同名附件各自独立）。 */
    private static Path uniqueTarget(Path dir, String fileName) {
        Path target = dir.resolve(fileName);
        if (!Files.exists(target)) {
            return target;
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        return dir.resolve(base + "-" + UUID.randomUUID().toString().substring(0, 8) + ext);
    }

    /** 字节数的可读形式（附件块里给模型看规模用）。 */
    private static String readableBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return Math.round(bytes / 1024.0) + "KB";
        }
        return String.format(java.util.Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024));
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
                Path dir = jworkPaths.attachmentsDir().resolve(sessionId);
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
        return FileKinds.PNG;
    }

    private void persistAssistant(String sessionId, String agentCode, String modelCode, String assistantText, String thinking,
                                  TokenUsage usage, boolean interrupted, List<ToolCallRecordCodec.ToolCallRecord> toolCalls) {
        persistAssistant(sessionId, agentCode, modelCode, assistantText, thinking, usage, interrupted,
                new AtomicBoolean(), toolCalls, null);
    }

    private void persistAssistant(String sessionId, String agentCode, String modelCode, String assistantText, String thinking,
                                  TokenUsage usage, boolean interrupted, AtomicBoolean settled,
                                  List<ToolCallRecordCodec.ToolCallRecord> toolCalls,
                                  List<ChatSegmentCodec.ChatSegment> segments) {
        if (settled != null && !settled.compareAndSet(false, true)) {
            return;
        }
        boolean hasTools = toolCalls != null && !toolCalls.isEmpty();
        boolean hasThinking = thinking != null && !thinking.isBlank();
        if ((assistantText == null || assistantText.isEmpty()) && !hasTools && !hasThinking) {
            // 一个字都没生成就被中断且无工具调用、无思考：不落空行，会话里不出现空气泡
            return;
        }
        ChatConversation c = assistantRow(sessionId, agentCode, modelCode, assistantText, thinking, usage,
                interrupted, toolCalls, segments);
        db.runInTransaction(() -> {
            db.conversationDao().insert(c);
            db.sessionDao().incrementMessageCount(sessionId, 1);
            // 草稿与正式行同事务消失：只提交行而草稿没删掉，下次启动会把这轮再提升一遍，气泡重复
            db.draftDao().delete(sessionId);
        });
    }

    /**
     * 助手轮的行对象：终态正式行与在途检查点共用一套构造，重启提升上来的半截才和正常收尾长得不差。
     * <p>{@code interrupted} 在检查点路径上恒为 true——草稿按定义就是没跑完的一轮。</p>
     */
    private ChatConversation assistantRow(String sessionId, String agentCode, String modelCode, String assistantText,
                                          String thinking, TokenUsage usage, boolean interrupted,
                                          List<ToolCallRecordCodec.ToolCallRecord> toolCalls,
                                          List<ChatSegmentCodec.ChatSegment> segments) {
        List<ToolCallRecordCodec.ToolCallRecord> toolSnapshot =
                toolCalls == null ? List.of() : new ArrayList<>(toolCalls);
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
            // 轮末上下文真实体量（最后一次调用的 prompt 体量）：上下文占用条以它为准，不再被整轮累加值放大
            c.setLastInputTokens(usage.lastInputTokens());
            c.setLlmCalls(usage.calls());
            c.setLlmElapsedMillis(usage.elapsedMillis());
        }
        c.setCreatedAt(LocalDateTime.now());
        // 附件/中断/思考/时序分段统一入 metadata（无思考的普通完成轮与原先一致，列留空）。
        // 分段有多少存多少：单段轮次同样要落，否则重放与流式各画一套布局，收尾一瞬间整块跳位。
        List<ChatSegmentCodec.ChatSegment> segmentsToStore = segments == null ? List.of() : List.copyOf(segments);
        c.setMetadata(MessageMetadataCodec.write(List.of(), interrupted, thinking, segmentsToStore));
        return c;
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
    public java.util.List<com.gettyio.jwork.agent.domain.entity.MessageHit> searchContent(String keyword, int limit) {
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
        // 消息、会话、在途草稿同事务：只删掉一半会留下无主的半截应答，下次启动被提升进气泡列表
        db.runInTransaction(() -> {
            db.conversationDao().deleteBySession(sessionId);
            db.draftDao().delete(sessionId);
            db.sessionDao().deleteBySessionId(sessionId);
        });
        deleteAttachments(sessionId);
        Consumer<String> cleanup = this.sessionCleanup;
        if (cleanup != null) {
            cleanup.accept(sessionId);
        }
        log.info("删除会话: {}", sessionId);
    }

    private void deleteAttachments(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Path dir = jworkPaths.attachmentsDir().resolve(sessionId);
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
        copy.setApprovalMode(src.getApprovalMode());
        copy.setStatus(SessionStatus.ACTIVE.name());
        copy.setMessageCount(0);
        copy.setParentSessionId(sessionId);

        List<ChatConversation> messages = db.conversationDao().findBySession(sessionId);
        List<ChatConversation> carried = prefixThrough(messages, messageId);
        // 会话头、逐条消息、计数同事务：中途失败留下半条分支（缺头、或消息数与内容不符）比失败更难收拾
        db.runInTransaction(() -> {
            db.sessionDao().insert(copy);
            for (ChatConversation m : carried) {
                copyMessage(newId, m);
            }
            if (!carried.isEmpty()) {
                db.sessionDao().incrementMessageCount(newId, carried.size());
            }
        });
        log.info("分支会话: {} -> {}（复制 {}/{} 条消息，截止 id={}）",
                sessionId, newId, carried.size(), messages.size(), messageId);
        return newId;
    }

    /** 源会话消息里截止到 {@code messageId}（含）的前缀；非正数或找不到该主键即全量复制。 */
    private static List<ChatConversation> prefixThrough(List<ChatConversation> messages, long messageId) {
        if (messageId <= 0) {
            return messages;
        }
        for (int i = 0; i < messages.size(); i++) {
            Long id = messages.get(i).getId();
            if (id != null && id == messageId) {
                return messages.subList(0, i + 1);
            }
        }
        return messages;
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
        c.setLastInputTokens(m.getLastInputTokens());
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
