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

package com.gettyio.jwork.agent.tools.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DockerAvailabilityProbe} 单测：验证真实握手探针的 TTL 缓存、失败结果缓存、
 * 异常收敛与不缓存模式，握手函数打桩，全程无 Docker 依赖。
 *
 * @author gogym
 */
class DockerAvailabilityProbeTest {

    @Test
    @DisplayName("握手成功结果在 TTL 内缓存：多次探测只真实握手一次")
    void successCachedWithinTtl() {
        AtomicInteger pings = new AtomicInteger();
        DockerAvailabilityProbe probe = DockerAvailabilityProbe.handshake(
                Duration.ofSeconds(1), Duration.ofMinutes(1), timeout -> {
                    pings.incrementAndGet();
                    return true;
                });

        assertTrue(probe.available());
        assertTrue(probe.available());
        assertTrue(probe.available());
        assertEquals(1, pings.get());
    }

    @Test
    @DisplayName("握手失败结果同样缓存：TTL 内不重复付出探测成本")
    void failureAlsoCached() {
        AtomicInteger pings = new AtomicInteger();
        DockerAvailabilityProbe probe = DockerAvailabilityProbe.handshake(
                Duration.ofSeconds(1), Duration.ofMinutes(1), timeout -> {
                    pings.incrementAndGet();
                    return false;
                });

        assertFalse(probe.available());
        assertFalse(probe.available());
        assertEquals(1, pings.get());
    }

    @Test
    @DisplayName("ttl<=0 不缓存：每次探测都重新握手（供设置页「立即检测」）")
    void noCacheWhenTtlZero() {
        AtomicInteger pings = new AtomicInteger();
        DockerAvailabilityProbe probe = DockerAvailabilityProbe.handshake(
                Duration.ofSeconds(1), Duration.ZERO, timeout -> {
                    pings.incrementAndGet();
                    return true;
                });

        probe.available();
        probe.available();
        assertEquals(2, pings.get());
    }

    @Test
    @DisplayName("握手抛异常：按不可用收敛，绝不外抛")
    void handshakeExplosionConverges() {
        DockerAvailabilityProbe probe = DockerAvailabilityProbe.handshake(
                Duration.ofSeconds(1), Duration.ofMinutes(1), timeout -> {
                    throw new IllegalStateException("docker broken");
                });

        assertFalse(probe.available());
    }
}
