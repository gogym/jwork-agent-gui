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

package com.gettyio.gim.agent.core.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatService#withIdleLimit} 单测：断流判定、事件透传与「只换超时异常」的边界。
 * <p>定时任务那一轮没有停止按钮，靠这条上限保证卡死的连接不会永久占住调度线程池。</p>
 *
 * @author gogym
 */
class ChatServiceIdleLimitTest {

    private static final String SID = "s-idle";

    @Test
    @DisplayName("超过静默上限没有新事件：中断并给出可读原因")
    void stalledStreamIsAborted() {
        Flux<StreamEvent> stalled = Flux.concat(
                Flux.just(StreamEvent.textDelta(SID, "前半段")), Flux.never());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ChatService.withIdleLimit(stalled, Duration.ofMillis(120)).blockLast(Duration.ofSeconds(5)));
        assertTrue(e.getMessage().contains("无响应"), "应说明是无响应中断: " + e.getMessage());
        assertTrue(e.getMessage().contains("秒"), "亚分钟上限应按秒表述: " + e.getMessage());
    }

    @Test
    @DisplayName("事件照常流出：上限只掐真正断流的源")
    void normalEventsPassThrough() {
        List<StreamEvent> events = ChatService.withIdleLimit(Flux.just(
                        StreamEvent.textDelta(SID, "He"),
                        StreamEvent.textDelta(SID, "llo"),
                        StreamEvent.complete(SID)), Duration.ofSeconds(5))
                .collectList().block(Duration.ofSeconds(5));

        assertEquals(3, events.size());
        assertEquals("He", events.get(0).getText());
    }

    @Test
    @DisplayName("非超时异常原样上抛：不改写模型侧的真实错误")
    void otherErrorsAreNotRemapped() {
        RuntimeException boom = new RuntimeException("模型连接被重置");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ChatService.withIdleLimit(Flux.error(boom), Duration.ofSeconds(5)).blockLast(Duration.ofSeconds(5)));

        assertSame(boom, thrown);
    }
}
