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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TurnQueue} 单测：轮次不交错、三种收口都能放行下一轮、未订阅不占队位。
 * <p>这里验的是「排队会不会把人永久堵死」——队列一堵，该运行体名下所有会话都再也发不出消息，
 * 所以异常与取消这两条放行路径必须和正常结束一样被测。</p>
 *
 * @author gogym
 */
class TurnQueueTest {

    /** 事件顺序记录（并发订阅下用并发安全表）。 */
    private final List<String> events = new CopyOnWriteArrayList<>();

    /**
     * 一轮「由测试决定何时收口」的对话：先吐两个元素，然后停在 {@code finish} 上。
     * 起始与收口都往 {@link #events} 里记一笔，断言直接看顺序。
     */
    private Flux<String> hanging(String tag, Sinks.Empty<Void> finish) {
        return Flux.defer(() -> {
            events.add(tag + ":start");
            return Flux.just(tag + "-a", tag + "-b")
                    .concatWith(finish.asMono().thenMany(Flux.<String>empty()));
        });
    }

    @Test
    @DisplayName("第一位即刻起跑，第二位等它收口才起：事件流不交错")
    void secondTurnWaitsForFirstToClose() {
        TurnQueue queue = new TurnQueue("test");
        Sinks.Empty<Void> finishA = Sinks.empty();
        Sinks.Empty<Void> finishB = Sinks.empty();
        List<String> received = new ArrayList<>();

        Disposable first = queue.serialize(hanging("A", finishA)).subscribe(received::add);
        assertEquals(List.of("A-a", "A-b"), received, "第一位不等任何人");
        assertEquals(1, queue.inFlight());

        Disposable second = queue.serialize(hanging("B", finishB)).subscribe(received::add);
        assertFalse(events.contains("B:start"), "B 不该在 A 收口前起跑");
        assertEquals(2, queue.inFlight(), "B 已排队未收口");

        finishA.tryEmitEmpty();
        assertTrue(events.contains("B:start"), "A 收口后 B 应即刻起跑");
        finishB.tryEmitEmpty();
        assertEquals(List.of("A-a", "A-b", "B-a", "B-b"), received, "两段答案首尾相接，不交错");
        assertEquals(0, queue.inFlight());
        first.dispose();
        second.dispose();
    }

    @Test
    @DisplayName("三位按订阅顺序排队：先订的先跑，谁都不插队")
    void turnsRunInSubscribeOrder() {
        TurnQueue queue = new TurnQueue("test");
        Sinks.Empty<Void> a = Sinks.empty();
        Sinks.Empty<Void> b = Sinks.empty();
        Sinks.Empty<Void> c = Sinks.empty();
        queue.serialize(hanging("A", a)).subscribe();
        queue.serialize(hanging("B", b)).subscribe();
        queue.serialize(hanging("C", c)).subscribe();
        assertEquals(List.of("A:start"), events);
        a.tryEmitEmpty();
        assertEquals(List.of("A:start", "B:start"), events);
        b.tryEmitEmpty();
        assertEquals(List.of("A:start", "B:start", "C:start"), events);
        c.tryEmitEmpty();
        assertEquals(0, queue.inFlight());
    }

    @Test
    @DisplayName("上一轮报错也要放行下一轮：异常终态不等于队列作废")
    void erroringTurnReleasesNext() {
        TurnQueue queue = new TurnQueue("test");
        AtomicBoolean failed = new AtomicBoolean();
        queue.serialize(Flux.defer(() -> {
            events.add("A:start");
            return Flux.just("A-a").concatWith(Mono.error(new IllegalStateException("模型流中断")));
        })).subscribe(o -> {
        }, e -> failed.set(true));
        assertTrue(failed.get(), "错误要照常下发给订阅方");

        Sinks.Empty<Void> finishB = Sinks.empty();
        queue.serialize(hanging("B", finishB)).subscribe();
        assertTrue(events.contains("B:start"), "A 出错后 B 必须能起");
        assertEquals(1, queue.inFlight(), "B 还在途");
        finishB.tryEmitEmpty();
        assertEquals(0, queue.inFlight());
    }

    @Test
    @DisplayName("取消在途轮次同样放行队列：用户点停止不该把会话永久卡死")
    void cancelledTurnReleasesNext() {
        TurnQueue queue = new TurnQueue("test");
        Disposable held = queue.serialize(hanging("A", Sinks.empty())).subscribe();
        assertFalse(events.contains("B:start"));
        held.dispose();

        Sinks.Empty<Void> finishB = Sinks.empty();
        queue.serialize(hanging("B", finishB)).subscribe();
        assertTrue(events.contains("B:start"), "取消即收口，队列要继续走");
        assertEquals(1, queue.inFlight(), "B 还在途");
        finishB.tryEmitEmpty();
        assertEquals(0, queue.inFlight());
    }

    @Test
    @DisplayName("只组装未订阅的轮次不占队位：丢弃一条流不能把后来者全堵在门外")
    void unsubscribedTurnHoldsNoSlot() {
        TurnQueue queue = new TurnQueue("test");
        Sinks.Empty<Void> neverSubscribed = Sinks.empty();
        queue.serialize(hanging("A", neverSubscribed));
        assertEquals(0, queue.inFlight(), "没订阅就不该有队位");

        queue.serialize(hanging("B", Sinks.empty())).subscribe();
        assertTrue(events.contains("B:start"), "B 不该排在一条从未起跑的流之后");
        assertFalse(events.contains("A:start"));
    }

    @Test
    @DisplayName("非流式问答与流式轮次共用同一个队")
    void monoAndFluxShareOneQueue() {
        TurnQueue queue = new TurnQueue("test");
        Sinks.Empty<Void> finishA = Sinks.empty();
        queue.serialize(hanging("A", finishA)).subscribe();

        AtomicReference<String> asked = new AtomicReference<>();
        Disposable ask = queue.serialize(Mono.fromCallable(() -> {
            events.add("ask:start");
            return "answer";
        })).subscribe(asked::set);
        assertFalse(events.contains("ask:start"), "UI 轮次在途时自主问答要等");

        finishA.tryEmitEmpty();
        assertEquals("answer", asked.get());
        assertTrue(events.contains("ask:start"));
        ask.dispose();
    }
}
