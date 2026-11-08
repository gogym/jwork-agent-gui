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

package com.gettyio.gim.agent.ui.window;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AppExit#survivorsReport()} 取数口径单测：停机日志里要点名的是「拦住 JVM 退出的非守护线程」，
 * 因此守护线程与自己都不该占行（校验线程起停均在测试内收尾，不留非守护线程影响 surefire 进程退出）。
 *
 * @author gogym
 */
class AppExitTest {

    private static final String HOLDER = "jwork-test-non-daemon-holder";
    private static final String DAEMON = "jwork-test-daemon-holder";

    @Test
    @DisplayName("在场线程：非守护的点名，守护的不报、自己也不报")
    void reportsForeignNonDaemonThreadsOnly() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable park = () -> {
            ready.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Thread nonDaemon = new Thread(park, HOLDER);
        Thread daemon = new Thread(park, DAEMON);
        daemon.setDaemon(true);
        nonDaemon.start();
        daemon.start();
        assertTrue(ready.await(5, TimeUnit.SECONDS), "两条线程应都已挂起等待");

        try {
            String report = AppExit.survivorsReport();
            assertTrue(report.contains(HOLDER), "非守护在场线程应被点名: " + report);
            assertFalse(report.contains(DAEMON), "守护线程不会拦住进程退出，不该占行");
            assertFalse(report.contains(Thread.currentThread().getName()), "收尾线程自身不列自己");
        } finally {
            release.countDown();
            nonDaemon.join(TimeUnit.SECONDS.toMillis(5));
            daemon.join(TimeUnit.SECONDS.toMillis(5));
        }
        assertFalse(nonDaemon.isAlive(), "测试线程须自行收尾");
    }
}
