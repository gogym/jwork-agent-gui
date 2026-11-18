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
import com.gettyio.jwork.agent.domain.persist.SqliteConnectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatService#chat} 三路终态落盘单测：complete 正常落助手，error/cancel 落半截且标 interrupted。
 * <p>用脚本化假网关供给事件流，真实 SQLite（{@code @TempDir} 文件）验落盘语义，无 JavaFX/框架运行时。</p>
 *
 * @author gogym
 */
class ChatServicePersistenceTest {

    @TempDir
    Path tempDir;

    private ChatService chatService;

    /** 假网关：每次 {@code stream} 供给同一脚本流；{@code ask} 不用。 */
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
        JworkDatabase db = new JworkDatabase(new SqliteConnectionProvider(tempDir.resolve("jwork.db")));
        db.initSchema();
        chatService = new ChatService(null, db, new JworkPaths(tempDir.resolve("home")), null);
    }

    private static ChatRequest req(String sessionId, String text) {
        return ChatRequest.builder().agentCode("jwork-agent").sessionId(sessionId).text(text).build();
    }

    private ChatConversation lastAssistant(String sessionId) {
        List<ChatConversation> history = chatService.history(sessionId);
        return history.stream()
                .filter(c -> "assistant".equals(c.getRole()))
                .reduce((a, b) -> b)
                .orElse(null);
    }

    @Test
    @DisplayName("complete：用户+助手双落盘，助手非中断且记录 token 用量")
    void completePersistsBothRoles() {
        String sid = "s-complete";
        Flux<StreamEvent> src = Flux.just(
                StreamEvent.textDelta(sid, "Hel"),
                StreamEvent.textDelta(sid, "lo"),
                StreamEvent.usage(sid, new TokenUsage(10, 5, 2, 1, 100L, 7)),
                StreamEvent.complete(sid));
        this.chatService = withGateway(() -> src);

        chatService.chat(req(sid, "你好")).blockLast();

        assertEquals(2, chatService.history(sid).size());
        ChatConversation assistant = lastAssistant(sid);
        assertNotNull(assistant);
        assertEquals("Hello", assistant.getContent());
        assertEquals(Integer.valueOf(10), assistant.getTokensInput());
        assertEquals(Integer.valueOf(5), assistant.getTokensOutput());
        // 轮末真实体量单独落列：占用条基准，不受累加值影响
        assertEquals(Integer.valueOf(7), assistant.getLastInputTokens());
        assertFalse(MessageMetadataCodec.read(assistant.getMetadata()).interrupted());
    }

    @Test
    @DisplayName("thinking：思考增量按序累积入 metadata.thinking，供历史折叠重放")
    void thinkingPersistedToMetadata() {
        String sid = "s-thinking";
        Flux<StreamEvent> src = Flux.just(
                StreamEvent.thinkingDelta(sid, "先看报错信息，"),
                StreamEvent.thinkingDelta(sid, "再定位到 47 行。"),
                StreamEvent.textDelta(sid, "已修复"),
                StreamEvent.complete(sid));
        this.chatService = withGateway(() -> src);

        chatService.chat(req(sid, "帮我修一下")).blockLast();

        ChatConversation assistant = lastAssistant(sid);
        assertNotNull(assistant);
        assertEquals("已修复", assistant.getContent());
        assertEquals("先看报错信息，再定位到 47 行。",
                MessageMetadataCodec.read(assistant.getMetadata()).thinking());
    }

    @Test
    @DisplayName("thinking 且无正文：半截思考也落盘，不丢")
    void thinkingOnlyPersisted() {
        String sid = "s-think-only";
        Flux<StreamEvent> src = Flux.just(
                StreamEvent.thinkingDelta(sid, "正在构思答案…"),
                StreamEvent.complete(sid));
        this.chatService = withGateway(() -> src);

        chatService.chat(req(sid, "在吗")).blockLast();

        ChatConversation assistant = lastAssistant(sid);
        assertNotNull(assistant, "仅有思考也应落盘助手行");
        assertEquals("正在构思答案…", MessageMetadataCodec.read(assistant.getMetadata()).thinking());
    }

    @Test
    @DisplayName("error：半截应答落盘且标 interrupted")
    void errorPersistsPartialInterrupted() {
        String sid = "s-error";
        Flux<StreamEvent> src = Flux.concat(
                Flux.just(StreamEvent.textDelta(sid, "Partial answer")),
                Flux.error(new RuntimeException("model blew up")));
        this.chatService = withGateway(() -> src);

        try {
            chatService.chat(req(sid, "问题")).blockLast();
        } catch (RuntimeException expected) {
            // 网关错误向上传播，符合预期
        }

        ChatConversation assistant = lastAssistant(sid);
        assertNotNull(assistant, "错误时应保住已生成半截");
        assertEquals("Partial answer", assistant.getContent());
        assertTrue(MessageMetadataCodec.read(assistant.getMetadata()).interrupted());
    }

    @Test
    @DisplayName("cancel（用户停止）：dispose 触发 doOnCancel 落半截且标 interrupted")
    void cancelPersistsPartialInterrupted() throws Exception {
        String sid = "s-cancel";
        CountDownLatch firstEmitted = new CountDownLatch(1);
        // 先吐一段并让内层完成（点灯），再挂到 never 保持流不终止，等待被 dispose
        Flux<StreamEvent> src = Flux.just(StreamEvent.textDelta(sid, "Half answer"))
                .doOnComplete(firstEmitted::countDown)
                .concatWith(Flux.never());
        this.chatService = withGateway(() -> src);

        Disposable subscription = chatService.chat(req(sid, "长答")).subscribe();
        assertTrue(firstEmitted.await(2, TimeUnit.SECONDS), "应在超时前收到首段增量");
        subscription.dispose();

        ChatConversation assistant = awaitAssistant(sid);
        assertNotNull(assistant, "取消时应保住半截应答");
        assertEquals("Half answer", assistant.getContent());
        assertTrue(MessageMetadataCodec.read(assistant.getMetadata()).interrupted());
    }

    @Test
    @DisplayName("一字未出的中断不落空气泡")
    void emptyInterruptionNotPersisted() {
        String sid = "s-empty";
        // 无任何 TEXT_DELTA/工具即出错：doOnError 落助手时应跳过空气泡
        this.chatService = withGateway(() -> Flux.error(new RuntimeException("no output")));

        try {
            chatService.chat(req(sid, "hi")).blockLast();
        } catch (RuntimeException ignored) {
            // 预期
        }
        assertNull(lastAssistant(sid), "无正文且无工具调用不应产生助手行");
        // 但用户消息仍落盘
        assertEquals(1, chatService.history(sid).size());
    }

    @Test
    @DisplayName("分段：单段轮次同样落分段，重放不再退回另一套布局")
    void singleSegmentTurnIsStored() {
        String sid = "s-seg-one";
        Flux<StreamEvent> src = Flux.just(
                StreamEvent.textDelta(sid, "只有一段正文"),
                StreamEvent.complete(sid));
        this.chatService = withGateway(() -> src);

        chatService.chat(req(sid, "在吗")).blockLast();

        ChatConversation assistant = lastAssistant(sid);
        assertNotNull(assistant);
        List<ChatSegmentCodec.ChatSegment> segs = MessageMetadataCodec.read(assistant.getMetadata()).segments();
        assertEquals(1, segs.size(), "旧规则攒够两段才落分段，单段轮重放会与流式排得不一样");
        assertEquals(ChatSegmentCodec.ChatSegment.TEXT, segs.get(0).type());
        assertEquals("只有一段正文", segs.get(0).text());
    }

    @Test
    @DisplayName("分段：思考段落耗时，且与工具/正文按时序交错")
    void thinkingSegmentCarriesDuration() {
        String sid = "s-seg-order";
        Flux<StreamEvent> src = Flux.just(
                StreamEvent.thinkingDelta(sid, "先想一下"),
                StreamEvent.toolEnd(sid, "read_file", "{\"path\":\"a.txt\"}", "内容", 12L, true),
                StreamEvent.thinkingDelta(sid, "再看一眼"),
                StreamEvent.textDelta(sid, "结论"),
                StreamEvent.complete(sid));
        this.chatService = withGateway(() -> src);

        chatService.chat(req(sid, "读一下")).blockLast();

        List<ChatSegmentCodec.ChatSegment> segs =
                MessageMetadataCodec.read(lastAssistant(sid).getMetadata()).segments();
        assertEquals(List.of(ChatSegmentCodec.ChatSegment.THINKING, ChatSegmentCodec.ChatSegment.TOOLS,
                        ChatSegmentCodec.ChatSegment.THINKING, ChatSegmentCodec.ChatSegment.TEXT),
                segs.stream().map(ChatSegmentCodec.ChatSegment::type).toList());
        // 思考段耗时在「下一段开始」时结算：不早于 0，且不因两段思考合并而丢失
        assertNotNull(segs.get(0).durationMillis());
        assertTrue(segs.get(0).durationMillis() >= 0L);
        assertNotNull(segs.get(2).durationMillis());
        assertNull(segs.get(1).durationMillis(), "工具段不带思考耗时");
    }

    @Test
    @DisplayName("分段：运行体旁白独立成段落盘，重放不再凭空消失")
    void noticeSegmentIsStored() {
        String sid = "s-seg-notice";
        Flux<StreamEvent> src = Flux.just(
                StreamEvent.textDelta(sid, "前半段"),
                StreamEvent.notice(sid, "iterContinue", "200,1"),
                StreamEvent.textDelta(sid, "后半段"),
                StreamEvent.complete(sid));
        this.chatService = withGateway(() -> src);

        chatService.chat(req(sid, "继续")).blockLast();

        List<ChatSegmentCodec.ChatSegment> segs =
                MessageMetadataCodec.read(lastAssistant(sid).getMetadata()).segments();
        assertEquals(3, segs.size(), "旁白既不并入正文，也不与相邻文本段合并");
        assertEquals(ChatSegmentCodec.ChatSegment.NOTICE, segs.get(1).type());
        assertEquals("iterContinue", segs.get(1).noticeCode());
        assertEquals("200,1", segs.get(1).text());
    }

    private ChatService withGateway(Supplier<Flux<StreamEvent>> script) {
        JworkDatabase db = new JworkDatabase(new SqliteConnectionProvider(tempDir.resolve("jwork.db")));
        db.initSchema();
        return new ChatService(new ScriptedGateway(script), db, new JworkPaths(tempDir.resolve("home")), null);
    }

    /** dispose 后取消落盘可能极短暂延迟，轮询取助手消息。 */
    private ChatConversation awaitAssistant(String sessionId) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            ChatConversation a = lastAssistant(sessionId);
            if (a != null) {
                return a;
            }
            Thread.sleep(25);
        }
        return lastAssistant(sessionId);
    }
}
