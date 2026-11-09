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

package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.MessageMetadataCodec;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.enums.ConversationRole;
import com.gettyio.gim.agent.ui.chat.payload.WebChatMessage;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 会话历史分页器：整页重放（最新一页）、滚动到顶向上补页、前端顶部回收后的游标维护。
 * <p>从 {@code MainController} 拆出的协作类：分页游标（{@code oldestLoadedId/hasMoreOlder/loadingOlder}）
 * 与历史消息载荷组装（{@link ChatMessagePayload}）内聚于此；控制器只负责装配与转发调用。</p>
 * <p>游标语义：{@code oldestLoadedId} 恒为「WebView DOM 中第一条消息的主键」——前端贴底回收
 * 顶部 DOM 后经 {@link #onTrimmed(long)} 前移；被回收段仍在库中，上滚触顶时经 {@link #loadOlder()}
 * 照常重新加载。</p>
 * <p>读库与载荷组装（含附件图片读盘转 data URI、整页 JSON 序列化）交池线程执行，只在 FX 线程上屏：
 * 50 条带图历史留在 FX 线程就是切会话时的一次白屏。上屏前比对 {@link #replayEpoch}——期间又有新的
 * 整页重放则整批丢弃，慢的那页不会盖住新的。</p>
 * <p>整页重放上屏后经 {@code afterReplay} 回接在途轮（临时气泡被 {@code renderHistory} 清掉了，
 * 不等落库也要接着看）；这一步必须跟着上屏走，否则先后次序会随构建耗时漂移。</p>
 *
 * @author gogym
 */
public final class ChatHistoryPager {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryPager.class);

    private final ChatService chatService;
    private final ChatWebView chatView;
    private final ChatMessagePayload payload;
    private final ChatStatusBar statusBar;
    private final BooleanProperty chatEmpty;
    private final Supplier<String> currentSessionId;
    private final BooleanSupplier busy;
    /** 整页重放上屏后的接线（控制器桥到 {@code reattachRunningTurn}）；补页不触发。 */
    private final Consumer<String> afterReplay;
    /** 构建所在池线程；测试注入 {@link Schedulers#immediate()}，让「构建 + 上屏」可同步断言。 */
    private final Scheduler background;
    /** 上屏通道（生产为 FX 线程队列）。 */
    private final Executor fx;

    /** 已加载的最旧历史主键（游标）；0 表示无可补页 */
    private long oldestLoadedId;
    /** 还有更早历史未加载 */
    private boolean hasMoreOlder;
    /** 向上补页进行中（防抖） */
    private boolean loadingOlder;
    /** 整页重放序号：每次 {@link #render} 自增，构建回来只对号入座最新那次 */
    private long replayEpoch;

    public ChatHistoryPager(ChatService chatService, ChatWebView chatView, ChatMessagePayload payload,
                            ChatStatusBar statusBar, BooleanProperty chatEmpty,
                            Supplier<String> currentSessionId, BooleanSupplier busy,
                            Consumer<String> afterReplay) {
        this(chatService, chatView, payload, statusBar, chatEmpty, currentSessionId, busy, afterReplay,
                Schedulers.boundedElastic(), Platform::runLater);
    }

    ChatHistoryPager(ChatService chatService, ChatWebView chatView, ChatMessagePayload payload,
                     ChatStatusBar statusBar, BooleanProperty chatEmpty,
                     Supplier<String> currentSessionId, BooleanSupplier busy, Consumer<String> afterReplay,
                     Scheduler background, Executor fx) {
        this.chatService = chatService;
        this.chatView = chatView;
        this.payload = payload;
        this.statusBar = statusBar;
        this.chatEmpty = chatEmpty;
        this.currentSessionId = currentSessionId;
        this.busy = busy;
        this.afterReplay = afterReplay;
        this.background = background;
        this.fx = fx;
    }

    /** 一次整页重放的构建产物：上屏所需的全部只读数据（含归属会话与发起时的重放序号）。 */
    private record ReplayPage(String sessionId, long epoch, String json, long lastAssistantId,
                              long lastUserId, boolean empty, boolean hasMoreOlder, long oldestId) {
    }

    /** 一次向上补页的构建产物（{@code oldestId} 为 0 表示没有更早）。 */
    private record OlderPage(String json, boolean empty, boolean hasMoreOlder, long oldestId) {
    }

    /**
     * 从库重放当前会话的消息：只拉最新一页（{@link ChatService#HISTORY_PAGE_SIZE}）避免长会话全量上屏，
     * 滚动到顶再向上补页；逐条按角色上屏，携带主键以供删除/编辑/重生成/分支。
     * <p>本方法同步复位游标并清空 DOM，页面内容随后在上屏通道里补上。</p>
     */
    public void render(String sessionId) {
        // 先复位分页态：整页重放会重建 DOM 并把视口复位到最新（底部），不能让旧会话的补页态误触发
        long epoch = ++replayEpoch;
        hasMoreOlder = false;
        loadingOlder = false;
        oldestLoadedId = 0;
        chatView.clear();
        if (sessionId == null) {
            return;
        }
        Mono.fromCallable(() -> buildReplay(sessionId, epoch))
                .subscribeOn(background)
                .subscribe(page -> fx.execute(() -> paintReplay(page)),
                        err -> fx.execute(() -> {
                            log.warn("会话历史重放失败: {}", sessionId, err);
                            // 整页读不出来就退回欢迎页，别留一块空白当已加载完
                            chatEmpty.set(true);
                        }));
    }

    /** 池线程侧的整页构建：读最新一页、逐条组载荷（附件图片在这里读盘转 data URI）、序列化。 */
    private ReplayPage buildReplay(String sessionId, long epoch) {
        List<ChatConversation> history = chatService.recentHistory(sessionId, ChatService.HISTORY_PAGE_SIZE);
        List<WebChatMessage> msgs = new ArrayList<>(history.size());
        long lastAssistantId = 0;
        long lastUserId = 0;
        for (ChatConversation c : history) {
            msgs.add(toPayload(c));
            long id = c.getId() == null ? 0L : c.getId();
            if (ConversationRole.USER.getCode().equalsIgnoreCase(c.getRole())) {
                lastUserId = id;
            } else {
                lastAssistantId = id;
            }
        }
        return new ReplayPage(sessionId, epoch, ChatMessagePayload.toJson(msgs), lastAssistantId, lastUserId,
                history.isEmpty(), history.size() >= ChatService.HISTORY_PAGE_SIZE, oldestIdOf(history));
    }

    private void paintReplay(ReplayPage page) {
        if (page.epoch() != replayEpoch) {
            return;
        }
        chatView.renderHistory(page.json());
        chatView.setFlags(page.lastAssistantId(), page.lastUserId());
        // 欢迎页显隐跟 Java 侧真实数据走，不依赖 JS 桥存活
        chatEmpty.set(page.empty());
        hasMoreOlder = page.hasMoreOlder();
        oldestLoadedId = page.oldestId();
        statusBar.updateUsageFromSession(page.sessionId());
        statusBar.updateContext();
        afterReplay.accept(page.sessionId());
    }

    /**
     * 滚动到顶补页：拉取游标之前的一页历史前置到 WebView 顶部（前端保持视觉位置不跳）。
     * 补满一整页才认为可能还有更早（与首页同口径）。
     */
    public void loadOlder() {
        if (loadingOlder || !hasMoreOlder || busy.getAsBoolean()) {
            return;
        }
        String sid = currentSessionId.get();
        if (sid == null || oldestLoadedId <= 0) {
            return;
        }
        loadingOlder = true;
        long epoch = replayEpoch;
        long cursor = oldestLoadedId;
        // older 升序（旧→新），按序组载荷后整体 prepend（前端按新增高度补偿滚动）
        Mono.fromCallable(() -> buildOlder(sid, cursor))
                .subscribeOn(background)
                .subscribe(page -> fx.execute(() -> paintOlder(page, epoch, cursor)),
                        err -> fx.execute(() -> {
                            log.warn("更早历史加载失败: {}", sid, err);
                            loadingOlder = false;
                        }));
    }

    private OlderPage buildOlder(String sessionId, long cursor) {
        List<ChatConversation> older = chatService.historyBefore(sessionId, cursor, ChatService.HISTORY_PAGE_SIZE);
        List<WebChatMessage> msgs = new ArrayList<>(older.size());
        for (ChatConversation c : older) {
            msgs.add(toPayload(c));
        }
        return new OlderPage(ChatMessagePayload.toJson(msgs), older.isEmpty(),
                older.size() >= ChatService.HISTORY_PAGE_SIZE, oldestIdOf(older));
    }

    private void paintOlder(OlderPage page, long epoch, long cursor) {
        loadingOlder = false;
        // 期间换过会话（重放序号变了）或顶部 DOM 被回收过（游标前移了）：这一页的位置已不成立，丢弃等下次触顶重取
        if (epoch != replayEpoch || cursor != oldestLoadedId) {
            return;
        }
        hasMoreOlder = page.hasMoreOlder();
        if (page.empty()) {
            return;
        }
        chatView.prependOlder(page.json());
        oldestLoadedId = page.oldestId();
    }

    /**
     * 前端贴底后回收了顶部 DOM（消息仍在库中）：补页游标前移到新首条，
     * 并恢复可补页——上滚触顶时照常把被回收段重新加载回来。
     */
    public void onTrimmed(long newOldestId) {
        if (currentSessionId.get() == null || newOldestId <= 0) {
            return;
        }
        oldestLoadedId = newOldestId;
        hasMoreOlder = true;
    }

    private static long oldestIdOf(List<ChatConversation> page) {
        return page.isEmpty() || page.get(0).getId() == null ? 0L : page.get(0).getId();
    }

    /** 把一条历史消息转为 JS 气泡载荷（用户：正文+附件；助手：正文+工具解码+思考+时间+中断标记）。 */
    private WebChatMessage toPayload(ChatConversation c) {
        long id = c.getId() == null ? 0L : c.getId();
        String content = c.getContent() == null ? "" : c.getContent();
        MessageMetadataCodec.MessageMetadata meta = MessageMetadataCodec.read(c.getMetadata());
        if (ConversationRole.USER.getCode().equalsIgnoreCase(c.getRole())) {
            return payload.user(id, content, meta.attachments());
        }
        return payload.assistant(id, content, c.getToolCalls(), meta.thinking(), c.getCreatedAt(),
                meta.interrupted(), meta.segments(), c.getSessionId());
    }
}
