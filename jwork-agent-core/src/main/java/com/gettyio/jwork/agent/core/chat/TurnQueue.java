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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮次串行队列：让同一个智能体运行体上的轮次首尾相接，不交错。
 *
 * <p>为什么需要它：运行体按「智能体 + 供应商 + 模型」装配并跨会话复用，两个会话同时发消息
 * 就会把两次回复灌进同一个 {@code HarnessAgent} 实例——它共享同一份对话状态与回复游标，
 * 框架本身也不允许上一回复未结束时重入。表现为事件流互相穿插、上下文串台，严重时整轮报错。</p>
 *
 * <p>实现取舍：用信号组合排队而不是阻塞锁。等位的轮次不占线程（并发一多就不会把
 * {@code boundedElastic} 的线程全钉在锁上），UI 点「停止」产生的取消也能立刻把队列往前推。
 * 队位登记推迟到订阅时，只组装不订阅的流不占队位，否则一条被丢弃的流能把后来者全堵在门外。</p>
 *
 * @author gogym
 */
final class TurnQueue {

    private static final Logger log = LoggerFactory.getLogger(TurnQueue.class);

    /** 日志前缀用的运行体标识（智能体 ID） */
    private final String label;
    private final Object gate = new Object();
    /** 队尾信号：上一轮收口时完成；订阅它即等于在该轮之后排队。初始即已收口——第一要不排队。 */
    private Sinks.Empty<Void> tail = closedSink();
    /** 已登记未收口的轮数：大于 1 说明有轮次在等，用来解释一条流为什么迟迟不出事件 */
    private final AtomicInteger inFlight = new AtomicInteger();

    TurnQueue(String label) {
        this.label = label;
    }

    /** 一个「已经收口」的信号：队列空时的队尾，第一位订阅者即刻通过。 */
    private static Sinks.Empty<Void> closedSink() {
        Sinks.Empty<Void> sink = Sinks.empty();
        sink.tryEmitEmpty();
        return sink;
    }

    /** 把一轮排在先前所有轮次之后：前一轮收口（含异常与取消）才开始订阅它。 */
    <T> Flux<T> serialize(Flux<T> turn) {
        return Flux.defer(() -> {
            Slot slot = takeSlot();
            return slot.waitForPrevious().thenMany(turn)
                    .doFinally(sig -> slot.close());
        });
    }

    /** {@link #serialize(Flux)} 的 Mono 版：非流式问答与流式轮次共用同一个队。 */
    <T> Mono<T> serialize(Mono<T> turn) {
        return Mono.defer(() -> {
            Slot slot = takeSlot();
            return slot.waitForPrevious().then(turn)
                    .doFinally(sig -> slot.close());
        });
    }

    /** 当前在途（含排队）轮数，供测试与自检观察。 */
    int inFlight() {
        return inFlight.get();
    }

    /** 取一个队位：拿到「上一轮收口」信号，同时把自己登记成新队尾。 */
    private Slot takeSlot() {
        Sinks.Empty<Void> closeSelf;
        Mono<Void> waitForPrevious;
        synchronized (gate) {
            waitForPrevious = tail.asMono();
            closeSelf = Sinks.empty();
            tail = closeSelf;
        }
        int running = inFlight.incrementAndGet();
        if (running > 1) {
            log.info("同一运行体上一轮仍在途，本轮排队等候其收口: agent={}, 在途={}", label, running);
        }
        return new Slot(waitForPrevious, closeSelf, inFlight);
    }

    /**
     * 一个队位：等上一轮收口的信号，收口时放行下一轮。
     * <p>{@link #close()} 必须在正常结束、异常、取消三种终态下都跑到——漏放一次，
     * 该运行体之后每一轮都会永久等待。</p>
     */
    private record Slot(Mono<Void> waitForPrevious, Sinks.Empty<Void> closeSelf,
                        AtomicInteger inFlight) {

        void close() {
            inFlight.decrementAndGet();
            closeSelf.tryEmitEmpty();
        }
    }
}
