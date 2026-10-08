package com.gettyio.gim.agent.core.chat;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatService} 历史分页语义单测：首页取最新一页（升序）、游标翻页衔接无缝、
 * 游标耗尽返回空、非法参数防御。真实 SQLite（{@code @TempDir} 文件），消息经 DAO 直插。
 */
class ChatServiceHistoryPageTest {

    @TempDir
    Path tempDir;

    private ChatService chatService;
    private GimDatabase db;

    @BeforeEach
    void setUp() {
        db = new GimDatabase(new SqliteConnectionProvider(tempDir.resolve("gim.db")));
        db.initSchema();
        chatService = new ChatService(null, db, new GimPaths(tempDir.resolve("home")), null);
    }

    /** 建会话并直插 {@code count} 条 user 消息，返回消息主键列表（按插入序，即 id 升序）。 */
    private List<Long> seedSession(String sessionId, int count) {
        ChatSession s = new ChatSession();
        s.setSessionId(sessionId);
        s.setAgentCode("gim-agent");
        s.setTitle("分页会话");
        s.setStatus("ACTIVE");
        s.setMessageCount(0);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        db.sessionDao().insert(s);

        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ChatConversation m = new ChatConversation();
            m.setSessionId(sessionId);
            m.setAgentCode("gim-agent");
            m.setRole("user");
            m.setContent("消息-" + i);
            m.setCreatedAt(LocalDateTime.now());
            db.conversationDao().insert(m);
            ids.add(m.getId());
        }
        return ids;
    }

    @Test
    @DisplayName("首页：不足一页全量返回且升序；超过一页只取最新一页")
    void recentHistoryPageSize() {
        seedSession("s-small", 3);
        List<ChatConversation> small = chatService.recentHistory("s-small", 50);
        assertEquals(3, small.size());
        assertEquals("消息-0", small.get(0).getContent(), "应按 id 升序");

        List<Long> ids = seedSession("s-big", 120);
        List<ChatConversation> page = chatService.recentHistory("s-big", 50);
        assertEquals(50, page.size(), "120 条只取最新 50 条");
        assertEquals("消息-70", page.get(0).getContent());
        assertEquals("消息-119", page.get(page.size() - 1).getContent());
        assertEquals(ids.get(70).longValue(), page.get(0).getId().longValue(), "首页最旧即游标起点");
    }

    @Test
    @DisplayName("游标翻页：以首页最旧 id 为游标逐页上翻，页间无缝且最终耗尽")
    void historyBeforePagesThroughCursor() {
        List<Long> ids = seedSession("s-cursor", 120);

        List<ChatConversation> page1 = chatService.recentHistory("s-cursor", 50);
        long cursor = page1.get(0).getId();

        List<ChatConversation> page2 = chatService.historyBefore("s-cursor", cursor, 50);
        assertEquals(50, page2.size());
        // 页间衔接：page2 最后一条应恰为 page1 首条的前一条
        assertEquals(cursor - 1, page2.get(page2.size() - 1).getId().longValue());
        assertEquals("消息-20", page2.get(0).getContent());

        List<ChatConversation> page3 = chatService.historyBefore("s-cursor", page2.get(0).getId(), 50);
        assertEquals(20, page3.size(), "剩余 20 条不足一页");
        assertEquals("消息-0", page3.get(0).getContent());

        // 游标耗尽：再翻返回空
        assertTrue(chatService.historyBefore("s-cursor", page3.get(0).getId(), 50).isEmpty());
        // 与全量对账：三页拼接 = 全部 120 条（升序）
        List<ChatConversation> all = chatService.history("s-cursor");
        assertEquals(120, all.size());
        assertEquals(all.get(20).getId(), page2.get(0).getId());
        assertEquals(all.get(0).getId(), page3.get(0).getId());
        assertEquals(ids.get(119), page1.get(page1.size() - 1).getId());
    }

    @Test
    @DisplayName("防御：非法游标/页大小/会话返回空，多会话互不串页")
    void historyPageDefenses() {
        seedSession("s-a", 60);
        seedSession("s-b", 2);

        assertTrue(chatService.recentHistory("s-a", 0).isEmpty(), "页大小非正返回空");
        assertTrue(chatService.historyBefore("s-a", 0, 50).isEmpty(), "游标非正返回空");
        assertTrue(chatService.historyBefore(null, 10, 50).isEmpty(), "会话为空返回空");
        assertTrue(chatService.recentHistory("s-missing", 50).isEmpty(), "未知会话返回空");

        // 多会话隔离：s-b 只有 2 条，不混入 s-a 的任何消息
        List<ChatConversation> pageB = chatService.recentHistory("s-b", 50);
        assertEquals(2, pageB.size());
        assertTrue(pageB.stream().allMatch(c -> "s-b".equals(c.getSessionId())));
    }
}
