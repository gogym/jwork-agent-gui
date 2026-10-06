package com.gettyio.gim.agent.core.chat;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
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
        GimDatabase db = new GimDatabase(new SqliteConnectionProvider(tempDir.resolve("gim.db")));
        db.initSchema();
        chatService = new ChatService(null, db, new GimPaths(tempDir.resolve("home")), null);
    }

    private static ChatRequest req(String sessionId, String text) {
        return ChatRequest.builder().agentCode("gim-agent").sessionId(sessionId).text(text).build();
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
                StreamEvent.usage(sid, new TokenUsage(10, 5, 2, 1, 100L)),
                StreamEvent.complete(sid));
        this.chatService = withGateway(() -> src);

        chatService.chat(req(sid, "你好")).blockLast();

        assertEquals(2, chatService.history(sid).size());
        ChatConversation assistant = lastAssistant(sid);
        assertNotNull(assistant);
        assertEquals("Hello", assistant.getContent());
        assertEquals(Integer.valueOf(10), assistant.getTokensInput());
        assertEquals(Integer.valueOf(5), assistant.getTokensOutput());
        assertFalse(MessageMetadataCodec.read(assistant.getMetadata()).interrupted());
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

    private ChatService withGateway(Supplier<Flux<StreamEvent>> script) {
        GimDatabase db = new GimDatabase(new SqliteConnectionProvider(tempDir.resolve("gim.db")));
        db.initSchema();
        return new ChatService(new ScriptedGateway(script), db, new GimPaths(tempDir.resolve("home")), null);
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
