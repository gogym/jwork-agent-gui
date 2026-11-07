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
import javafx.beans.property.BooleanProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 会话历史分页器：整页重放（最新一页）、滚动到顶向上补页、前端顶部回收后的游标维护。
 * <p>从 {@code MainController} 拆出的协作类：分页游标（{@code oldestLoadedId/hasMoreOlder/loadingOlder}）
 * 与历史消息载荷组装（{@link ChatMessagePayload}）内聚于此；控制器只负责装配与转发调用。</p>
 * <p>游标语义：{@code oldestLoadedId} 恒为「WebView DOM 中第一条消息的主键」——前端贴底回收
 * 顶部 DOM 后经 {@link #onTrimmed(long)} 前移；被回收段仍在库中，上滚触顶时经 {@link #loadOlder()}
 * 照常重新加载。</p>
 *
 * @author gogym
 */
public final class ChatHistoryPager {

    private final ChatService chatService;
    private final ChatWebView chatView;
    private final ChatMessagePayload payload;
    private final ChatStatusBar statusBar;
    private final BooleanProperty chatEmpty;
    private final Supplier<String> currentSessionId;
    private final BooleanSupplier busy;

    /** 已加载的最旧历史主键（游标）；0 表示无可补页 */
    private long oldestLoadedId;
    /** 还有更早历史未加载 */
    private boolean hasMoreOlder;
    /** 向上补页进行中（防抖） */
    private boolean loadingOlder;

    public ChatHistoryPager(ChatService chatService, ChatWebView chatView, ChatMessagePayload payload,
                            ChatStatusBar statusBar, BooleanProperty chatEmpty,
                            Supplier<String> currentSessionId, BooleanSupplier busy) {
        this.chatService = chatService;
        this.chatView = chatView;
        this.payload = payload;
        this.statusBar = statusBar;
        this.chatEmpty = chatEmpty;
        this.currentSessionId = currentSessionId;
        this.busy = busy;
    }

    /**
     * 从库重放当前会话的消息：只拉最新一页（{@link ChatService#HISTORY_PAGE_SIZE}）避免长会话全量上屏，
     * 滚动到顶再向上补页；逐条按角色上屏，携带主键以供删除/编辑/重生成/分支。
     */
    public void render(String sessionId) {
        // 先复位分页态：整页重放会重建 DOM 并把视口复位到最新（底部），不能让旧会话的补页态误触发
        hasMoreOlder = false;
        loadingOlder = false;
        oldestLoadedId = 0;
        chatView.clear();
        if (sessionId == null) {
            return;
        }
        List<ChatConversation> history = chatService.recentHistory(sessionId, ChatService.HISTORY_PAGE_SIZE);
        List<WebChatMessage> msgs = new ArrayList<>();
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
        chatView.renderHistory(ChatMessagePayload.toJson(msgs));
        chatView.setFlags(lastAssistantId, lastUserId);
        // 欢迎页显隐跟 Java 侧真实数据走，不依赖 JS 桥存活
        chatEmpty.set(history.isEmpty());
        hasMoreOlder = history.size() >= ChatService.HISTORY_PAGE_SIZE;
        if (!history.isEmpty() && history.get(0).getId() != null) {
            oldestLoadedId = history.get(0).getId();
        }
        statusBar.updateUsageFromSession(sessionId);
        statusBar.updateContext();
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
        List<ChatConversation> older = chatService.historyBefore(sid, oldestLoadedId, ChatService.HISTORY_PAGE_SIZE);
        hasMoreOlder = older.size() >= ChatService.HISTORY_PAGE_SIZE;
        if (older.isEmpty()) {
            loadingOlder = false;
            return;
        }
        // older 升序（旧→新），按序组载荷后整体 prepend（前端按新增高度补偿滚动）
        List<WebChatMessage> msgs = new ArrayList<>();
        for (ChatConversation c : older) {
            msgs.add(toPayload(c));
        }
        chatView.prependOlder(ChatMessagePayload.toJson(msgs));
        if (older.get(0).getId() != null) {
            oldestLoadedId = older.get(0).getId();
        }
        loadingOlder = false;
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
