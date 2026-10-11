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

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatHistoryPager} 单测：整页重放（最新一页 + 标志复位）、滚动到顶补页、
 * 前端顶部回收后的游标前移与可补页恢复（paging07 语义）。
 * <p>协作对象（ChatService/ChatWebView/ChatStatusBar）用 mock 替身，只验证协调行为与参数；
 * 载荷字段组装由 {@link ChatMessagePayload} 自身负责（此处用真实实例 + mock GimPaths，
 * 普通文本消息不触碰磁盘路径）。chatEmpty 用普通 {@code SimpleBooleanProperty}，无需 JavaFX 工具包。</p>
 *
 * @author gogym
 */
class ChatHistoryPagerTest {

    private ChatService chatService;
    private ChatWebView chatView;
    private ChatStatusBar statusBar;
    private BooleanProperty chatEmpty;
    private ChatHistoryPager pager;
    /** 当前会话 id（可变引用，模拟切会话/无会话） */
    private String[] session;
    /** 忙碌态（可变引用，模拟流式进行中） */
    private boolean[] busy;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        chatView = mock(ChatWebView.class);
        statusBar = mock(ChatStatusBar.class);
        chatEmpty = new SimpleBooleanProperty(true);
        session = new String[]{"s1"};
        busy = new boolean[]{false};
        ChatMessagePayload payload = new ChatMessagePayload(mock(GimPaths.class));
        pager = new ChatHistoryPager(chatService, chatView, payload, statusBar, chatEmpty,
                () -> session[0], () -> busy[0]);
    }

    /** 构造一页会话：id 从 firstId 递增，偶数下标 user / 奇数下标 assistant（升序，同库返回）。 */
    private static List<ChatConversation> page(long firstId, int count) {
        List<ChatConversation> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ChatConversation c = new ChatConversation();
            c.setId(firstId + i);
            c.setRole(i % 2 == 0 ? "user" : "assistant");
            c.setContent("m" + (firstId + i));
            list.add(c);
        }
        return list;
    }

    @Test
    @DisplayName("render：只拉最新一页上屏，角色分类复位气泡标志，欢迎页按数据显隐")
    void renderLoadsLatestPageAndSetsFlags() {
        when(chatService.recentHistory("s1", ChatService.HISTORY_PAGE_SIZE)).thenReturn(page(101, 50));

        pager.render("s1");

        verify(chatView).clear();
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(chatView).renderHistory(json.capture());
        assertTrue(json.getValue().contains("\"id\":101,"));
        assertTrue(json.getValue().contains("\"id\":150,"));
        // 末条 150 为 assistant、其前 149 为 user：标志按角色分类（末助手=重生成、末用户=编辑入口）
        verify(chatView).setFlags(150L, 149L);
        assertFalse(chatEmpty.get());
        verify(statusBar).updateUsageFromSession("s1");
        verify(statusBar).updateContext();

        // 游标应为首页最旧 101：补页按它请求更早一段
        when(chatService.historyBefore("s1", 101L, ChatService.HISTORY_PAGE_SIZE)).thenReturn(List.of());
        pager.loadOlder();
        verify(chatService).historyBefore("s1", 101L, ChatService.HISTORY_PAGE_SIZE);
    }

    @Test
    @DisplayName("render：空历史显示欢迎页，且不触发向上补页")
    void renderEmptyHistoryHidesChat() {
        when(chatService.recentHistory("s1", ChatService.HISTORY_PAGE_SIZE)).thenReturn(List.of());

        pager.render("s1");

        verify(chatView).renderHistory("[]");
        assertTrue(chatEmpty.get());
        pager.loadOlder();
        verify(chatService, never()).historyBefore(anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("render(null)：只清空不拉历史（会话未选中态）")
    void renderNullSessionJustClears() {
        pager.render(null);

        verify(chatView).clear();
        verify(chatView, never()).renderHistory(anyString());
        verify(statusBar, never()).updateUsageFromSession(anyString());
    }

    @Test
    @DisplayName("loadOlder：更早一页升序前置上屏，游标前移；补到不足一页即认为到头")
    void loadOlderPrependsOlderPageAndAdvancesCursor() {
        when(chatService.recentHistory("s1", ChatService.HISTORY_PAGE_SIZE)).thenReturn(page(101, 50));
        when(chatService.historyBefore("s1", 101L, ChatService.HISTORY_PAGE_SIZE)).thenReturn(page(51, 50));
        pager.render("s1");

        pager.loadOlder();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(chatView).prependOlder(json.capture());
        String prepended = json.getValue();
        assertTrue(prepended.indexOf("\"id\":51,") < prepended.indexOf("\"id\":100,"));

        // 游标前移到 51：再次补页按新游标请求；不足一页 → 不再有更早
        when(chatService.historyBefore("s1", 51L, ChatService.HISTORY_PAGE_SIZE)).thenReturn(page(1, 10));
        pager.loadOlder();
        pager.loadOlder();
        verify(chatService, times(2)).historyBefore(anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("loadOlder：流式进行中拦截，结束恢复后照常补页")
    void loadOlderBlockedWhileBusy() {
        when(chatService.recentHistory("s1", ChatService.HISTORY_PAGE_SIZE)).thenReturn(page(101, 50));
        pager.render("s1");

        busy[0] = true;
        pager.loadOlder();
        verify(chatService, never()).historyBefore(anyString(), anyLong(), anyInt());

        busy[0] = false;
        when(chatService.historyBefore("s1", 101L, ChatService.HISTORY_PAGE_SIZE)).thenReturn(List.of());
        pager.loadOlder();
        verify(chatService).historyBefore("s1", 101L, ChatService.HISTORY_PAGE_SIZE);
    }

    @Test
    @DisplayName("onTrimmed：游标前移到回收后首条，已到头状态恢复为可补页（paging07 关键语义）")
    void onTrimmedAdvancesCursorAndRestoresPaging() {
        when(chatService.recentHistory("s1", ChatService.HISTORY_PAGE_SIZE)).thenReturn(page(101, 50));
        pager.render("s1");
        // 先把补页耗尽（不足一页 → hasMoreOlder=false）
        when(chatService.historyBefore("s1", 101L, ChatService.HISTORY_PAGE_SIZE)).thenReturn(page(91, 10));
        pager.loadOlder();

        // 前端贴底回收顶部 DOM 至 id=301：游标前移且恢复可补页
        pager.onTrimmed(301L);
        when(chatService.historyBefore("s1", 301L, ChatService.HISTORY_PAGE_SIZE)).thenReturn(List.of());
        pager.loadOlder();
        verify(chatService).historyBefore("s1", 301L, ChatService.HISTORY_PAGE_SIZE);
    }

    @Test
    @DisplayName("onTrimmed：非正主键或无会话时忽略，游标保持不动")
    void onTrimmedIgnoredWhenSessionMissingOrInvalidId() {
        when(chatService.recentHistory("s1", ChatService.HISTORY_PAGE_SIZE)).thenReturn(page(101, 50));
        pager.render("s1");

        pager.onTrimmed(0);
        session[0] = null;
        pager.onTrimmed(301L);
        session[0] = "s1";

        when(chatService.historyBefore("s1", 101L, ChatService.HISTORY_PAGE_SIZE)).thenReturn(List.of());
        pager.loadOlder();
        verify(chatService).historyBefore("s1", 101L, ChatService.HISTORY_PAGE_SIZE);
    }
}
