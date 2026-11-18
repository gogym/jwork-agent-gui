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
import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.entity.ChatConversation;
import com.gettyio.jwork.agent.domain.entity.ChatSession;
import com.gettyio.jwork.agent.domain.persist.SqliteConnectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在途应答检查点单测：跑到一半就落草稿、终态与草稿同事务交接、重启把草稿提升成一条中断消息。
 * <p>这条链只有真实 SQLite 才测得准（写盘语义、提升后的计数），所以不起 JavaFX，只把检查点间隔
 * 调小以免真等三秒。</p>
 *
 * @author gogym
 */
class ChatServiceDraftCheckpointTest {

    @TempDir
    Path tempDir;

    private JworkDatabase db;
    private ChatService chatService;

    /** 假网关：每次 {@code stream} 供给同一脚本流。 */
    private static final class ScriptedGateway implements AgentGateway {
        private final Supplier<Flux<StreamEvent>> script;

        ScriptedGateway(Supplier<Flux<StreamEvent>> script) {
            this.script = script;
        }

        @Override
        public Mono<String> ask(String agentCode, String sessionId, String userText) {
            return Mono.empty();
        }

        @Override
        public Flux<StreamEvent> stream(ChatRequest request) {
            return script.get();
        }
    }

    @BeforeEach
    void setUp() {
        db = new JworkDatabase(new SqliteConnectionProvider(tempDir.resolve("jwork.db")));
        db.initSchema();
        chatService = new ChatService(null, db, new JworkPaths(tempDir.resolve("home")), null);
    }

    private static ChatRequest req(String sessionId, String text) {
        return ChatRequest.builder().agentCode("jwork-agent").sessionId(sessionId).text(text).build();
    }

    private ChatService withGateway(Supplier<Flux<StreamEvent>> script) {
        return new ChatService(new ScriptedGateway(script), db, new JworkPaths(tempDir.resolve("home")), null);
    }

    private ChatConversation draftOf(String sessionId) {
        return db.draftDao().findAll().stream()
                .filter(d -> sessionId.equals(d.getSessionId()))
                .findFirst().orElse(null);
    }

    /** 轮询等检查点出现或消失：写盘发生在流线程上，断言前得等它落地。 */
    private ChatConversation awaitDraft(String sessionId, boolean present) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            ChatConversation hit = draftOf(sessionId);
            if (present == (hit != null)) {
                return hit;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError(present ? "超时仍未写出检查点" : "超时仍未清掉检查点");
    }

    private int messageCount(String sessionId) {
        return db.sessionDao().findBySessionId(sessionId)
                .map(ChatSession::getMessageCount).orElse(-1);
    }

    @Test
    @DisplayName("跑到一半就有草稿：进程被杀不再整轮丢失，且草稿标为中断")
    void inFlightTurnCheckpointsDraft() throws Exception {
        String sid = "s-live";
        CountDownLatch secondEvent = new CountDownLatch(1);
        // 检查点只在事件到来时顺带写：第二个事件刻意压到间隔之后，才测得出「跑到一半落了盘」
        Flux<StreamEvent> src = Flux.concat(
                Flux.just(StreamEvent.thinkingDelta(sid, "先看看日志")),
                Mono.delay(Duration.ofMillis(150))
                        .doOnNext(tick -> secondEvent.countDown())
                        .thenMany(Flux.just(StreamEvent.textDelta(sid, "半截答复"))),
                Flux.never());
        ChatService svc = withGateway(() -> src);
        svc.setDraftCheckpointInterval(Duration.ofMillis(30));

        Disposable subscription = svc.chat(req(sid, "长答")).subscribe();
        assertTrue(secondEvent.await(2, TimeUnit.SECONDS), "应在超时前收到第二个事件");

        ChatConversation draft = awaitDraft(sid, true);
        assertNotNull(draft);
        assertEquals("半截答复", draft.getContent());
        assertEquals("先看看日志", MessageMetadataCodec.read(draft.getMetadata()).thinking());
        assertTrue(MessageMetadataCodec.read(draft.getMetadata()).interrupted(), "没跑完的一轮必须带中断标记");
        assertEquals(2, MessageMetadataCodec.read(draft.getMetadata()).segments().size(),
                "分段一并落草稿，提升后重放才与流式同构");

        subscription.dispose();
        // 终态落盘与删草稿同事务：正式行出现后草稿必须消失
        assertNotNull(awaitAssistant(sid));
        awaitDraft(sid, false);
    }

    @Test
    @DisplayName("工具轨迹也进检查点：崩溃后提升的那半截仍能重放出工具卡片")
    void toolCallsSurviveCheckpoint() throws Exception {
        String sid = "s-live-tools";
        CountDownLatch secondEvent = new CountDownLatch(1);
        Flux<StreamEvent> src = Flux.concat(
                Flux.just(StreamEvent.textDelta(sid, "先读文件")),
                Mono.delay(Duration.ofMillis(150))
                        .doOnNext(tick -> secondEvent.countDown())
                        .thenMany(Flux.just(
                                StreamEvent.toolEnd(sid, "read_file", "src/App.java", "已读", 12L, true))),
                Flux.never());
        ChatService svc = withGateway(() -> src);
        svc.setDraftCheckpointInterval(Duration.ofMillis(30));

        Disposable subscription = svc.chat(req(sid, "读一下")).subscribe();
        assertTrue(secondEvent.await(2, TimeUnit.SECONDS), "应在超时前收到工具结束事件");
        ChatConversation draft = awaitDraft(sid, true);
        assertNotNull(draft.getToolCalls(), "工具调用是这轮的一半真相，不能只留正文");
        assertTrue(draft.getToolCalls().contains("read_file"));
        subscription.dispose();
        awaitDraft(sid, false);
    }

    @Test
    @DisplayName("重启提升：草稿变成会话末尾一条中断消息，计数同步、草稿清空")
    void draftPromotedOnStartup() {
        String sid = "s-promote";
        chatService.insertLocalExchange(sid, "jwork-agent", "问", "答");
        int before = messageCount(sid);

        ChatConversation draft = new ChatConversation();
        draft.setSessionId(sid);
        draft.setAgentCode("jwork-agent");
        // 角色不入草稿表：DAO 读回时恒按助手轮给出（草稿按定义就是没收尾的助手应答）
        draft.setContent("写到一半");
        draft.setMetadata(MessageMetadataCodec.write(List.of(), true, "在想这一步",
                List.of(ChatSegmentCodec.ChatSegment.ofThinking("在想这一步"),
                        ChatSegmentCodec.ChatSegment.ofText("写到一半"))));
        draft.setCreatedAt(LocalDateTime.now());
        db.draftDao().save(draft);

        assertEquals(1, chatService.recoverInterruptedDrafts());

        List<ChatConversation> history = chatService.history(sid);
        assertEquals(before + 1, history.size());
        ChatConversation last = history.get(history.size() - 1);
        assertEquals("assistant", last.getRole());
        assertEquals("写到一半", last.getContent());
        MessageMetadataCodec.MessageMetadata meta = MessageMetadataCodec.read(last.getMetadata());
        assertTrue(meta.interrupted());
        assertEquals("在想这一步", meta.thinking());
        assertEquals(2, meta.segments().size());
        assertEquals(before + 1, messageCount(sid), "提升出来的消息也要计入会话条数");
        assertTrue(db.draftDao().findAll().isEmpty(), "提升即消费，不能下次启动再来一遍");
    }

    @Test
    @DisplayName("会话已删：无人认领的草稿直接丢弃，不复活成孤行")
    void draftOfMissingSessionIsDropped() {
        ChatConversation draft = new ChatConversation();
        draft.setSessionId("s-gone");
        draft.setContent("无人认领");
        draft.setCreatedAt(LocalDateTime.now());
        db.draftDao().save(draft);

        assertEquals(0, chatService.recoverInterruptedDrafts());

        assertTrue(db.draftDao().findAll().isEmpty(), "留着就是每次启动白跑一遍");
        assertTrue(chatService.history("s-gone").isEmpty());
    }

    @Test
    @DisplayName("没有草稿时提升是空操作（正常退出不该留下任何残稿）")
    void recoverIsNoOpWhenClean() {
        chatService.insertLocalExchange("s-clean", "jwork-agent", "问", "答");
        assertTrue(db.draftDao().findAll().isEmpty());
        assertEquals(0, chatService.recoverInterruptedDrafts());
        assertEquals(2, chatService.history("s-clean").size());
    }

    @Test
    @DisplayName("删会话连草稿一起删：否则下次启动会往已删的会话里提升半截")
    void deleteSessionClearsDraft() {
        String sid = "s-del";
        chatService.insertLocalExchange(sid, "jwork-agent", "问", "答");
        ChatConversation draft = new ChatConversation();
        draft.setSessionId(sid);
        draft.setContent("半截");
        draft.setCreatedAt(LocalDateTime.now());
        db.draftDao().save(draft);

        chatService.deleteSession(sid);

        assertTrue(db.draftDao().findAll().isEmpty());
        assertEquals(0, chatService.recoverInterruptedDrafts());
    }

    /** 轮询等终态助手行落盘：取消回调跑在流线程上，主线程断言前得等它写完。 */
    private ChatConversation awaitAssistant(String sessionId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            ChatConversation hit = chatService.history(sessionId).stream()
                    .filter(c -> "assistant".equals(c.getRole()))
                    .reduce((first, second) -> second).orElse(null);
            if (hit != null) {
                return hit;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("超时未落助手消息");
    }
}
