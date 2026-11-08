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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 停机收尾：跑完清理即终止进程，不给「点了退出、窗口没了、进程还在」留余地。
 * <p>{@code Platform.exit()} 只结束 JavaFX 运行环境，进程活着与否取决于还有没有非守护线程——
 * JCEF/AWT 恰好是最难保证的一类：CEF 的 OSR 宿主是不可见的 AWT 重量级窗，只要还有一个保持
 * displayable，AWT 事件分发线程就一直算非守护，而 {@code CefApp.dispose()} 原本挂在 JVM
 * shutdown hook 上，hook 又只在进程开始退出时才跑，两者互等，进程永不结束、cache 目录句柄
 * 还被渲染子进程占着，下次启动即撞「文件被占用」。</p>
 * <p>故清理之后统一 {@code System.exit(0)}：进程终止不再取决于谁还留着线程，且 exit 会照常
 * 执行 shutdown hook（含 JCEF 释放）。若清理或 hook 自身卡死，由守护线程看门狗在宽限期后
 * {@code halt} 兜底——halt 跳过 hook，只在已经等不动的情况下才用。</p>
 * <p>停机时把仍在场的非守护线程连栈顶打进日志：症状一旦出现，不必复跑即可指名是谁没放线程。</p>
 *
 * @author gogym
 */
public final class AppExit {

    private static final Logger log = LoggerFactory.getLogger(AppExit.class);

    /** 看门狗宽限期（ms）：正常停机在 1 秒内完成，超时说明清理或 shutdown hook 卡住。 */
    private static final long GRACE_MS = 10_000L;

    private static final AtomicBoolean TERMINATED = new AtomicBoolean();
    private static final AtomicBoolean WATCHDOG_ARMED = new AtomicBoolean();

    private AppExit() {
    }

    /**
     * 终止应用：先在调用线程跑 {@code cleanup}（释放浏览器、关组合根等），再强制结束进程。
     * <p>幂等：重复调用（如 {@code stop()} 与托盘退出并发）只有首次生效。须在 JavaFX 应用线程
     * 调用——待释放的视图只能在 FX 线程停。</p>
     */
    public static void terminate(Runnable cleanup) {
        if (!TERMINATED.compareAndSet(false, true)) {
            return;
        }
        armWatchdog();
        try {
            cleanup.run();
        } catch (Throwable t) {
            log.warn("停机清理失败", t);
        }
        String survivors = survivorsReport();
        if (!survivors.isEmpty()) {
            log.info("停机时仍在场的非守护线程（已由 System.exit 收尾）:{}", survivors);
        }
        System.exit(0);
    }

    /**
     * 只挂看门狗：退出请求已发出、但 {@code stop()} 未必来得及跑（FX 线程阻塞在弹框或队列里）时，
     * 至少保证进程在宽限期后终止。与 {@link #terminate} 共用一次性开关。
     */
    public static void armWatchdog() {
        if (!WATCHDOG_ARMED.compareAndSet(false, true)) {
            return;
        }
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(GRACE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            log.warn("停机 {}ms 后进程仍未终止，强制结束（清理或 shutdown hook 卡住）", GRACE_MS);
            Runtime.getRuntime().halt(0);
        }, "jwork-exit-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * 逐个列出除当前线程外的非守护存活线程（它们正是拦住 JVM 退出的那批），各带栈顶两帧。
     * <p>包级可见供单测校验取数口径；无在场者返回空串。</p>
     */
    static String survivorsReport() {
        Thread self = Thread.currentThread();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread t = entry.getKey();
            if (t == self || t.isDaemon() || !t.isAlive()) {
                continue;
            }
            sb.append("\n  ").append(t.getName());
            StackTraceElement[] stack = entry.getValue();
            if (stack.length > 0) {
                sb.append(" | ").append(stack[0]);
                if (stack.length > 1) {
                    sb.append(" <- ").append(stack[1]);
                }
            }
        }
        return sb.toString();
    }
}
