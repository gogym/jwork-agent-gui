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

import com.gettyio.jwork.agent.domain.util.OsPlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 后台命令登记表：长任务不占对话轮次，边跑边能被 {@code shell_output} 增量读走。
 *
 * <p>为什么不能复用同步档：框架的本地 shell 把 stdout/stderr 全量缓存在内存里、等进程退出才一次性
 * 返回，并且 {@code waitFor} 被中断时只回一句错误、<b>不杀子进程</b>。所以「把 execute 丢到别的线程
 * 里跑」这种做法既不给出中间输出（模型只能干等最后一次全量），也没法真停（Future.cancel 之后进程
 * 还在后台跑，退出时还挂在系统里）。这里自己起进程、自己持有句柄：输出按行落到带绝对游标的缓冲里，
 * 停就 {@code destroyForcibly}，收尾统一 {@link #disposeAll()}。</p>
 *
 * <p>进程表是 JVM 级单例：一次桌面会话里可能有多个智能体运行体各自起过长任务，退出时必须全部收掉，
 * 否则留下的是无人认领的孤儿进程。</p>
 *
 * @author gogym
 */
public final class BackgroundShellRunner {

    private static final Logger log = LoggerFactory.getLogger(BackgroundShellRunner.class);

    /** 单个任务保留的输出上限（字符）：超出后丢最早的行，末尾仍给出丢弃量。 */
    public static final int MAX_OUTPUT_CHARS = 200_000;

    /** 单次 shell_output 返回的输出上限（字符）。 */
    public static final int MAX_READ_CHARS = 30_000;

    /** 已结束任务保留时长：留够「跑完再回头看结果」的窗口，过期即从表里清掉。 */
    public static final Duration FINISHED_TTL = Duration.ofMinutes(30);

    private static final Map<String, Task> TASKS = new ConcurrentHashMap<>();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private BackgroundShellRunner() {
    }

    /** 一个后台任务：进程句柄 + 带绝对游标的输出缓冲 + 终态。 */
    private static final class Task {
        private final String id;
        private final String command;
        private final Instant startedAt = Instant.now();
        private final StringBuilder buffer = new StringBuilder();
        /** 缓冲里第 0 个字符对应的绝对位置（丢过头部后不为 0，游标因此始终单调）。 */
        private long absoluteStart;
        private final AtomicReference<String> terminal = new AtomicReference<>();
        private final AtomicLong dropped = new AtomicLong();
        private volatile Process process;
        private volatile Integer exitCode;

        private Task(String id, String command) {
            this.id = id;
            this.command = command;
        }

        private synchronized void append(String line) {
            buffer.append(line).append('\n');
            int overflow = buffer.length() - MAX_OUTPUT_CHARS;
            if (overflow <= 0) {
                return;
            }
            // 丢头部按整行边界切，免得模型读到半行；超长单行退化成按字符切
            int cut = buffer.indexOf("\n", overflow);
            int keepFrom = cut < 0 ? overflow : cut + 1;
            absoluteStart += keepFrom;
            dropped.addAndGet(keepFrom);
            buffer.delete(0, keepFrom);
        }

        private synchronized Chunk read(long cursor, int maxChars) {
            long from = Math.max(cursor, absoluteStart);
            int localFrom = (int) Math.min(from - absoluteStart, buffer.length());
            int localTo = Math.min(buffer.length(), localFrom + maxChars);
            String text = buffer.substring(localFrom, localTo);
            return new Chunk(text, from + text.length());
        }

        private synchronized long size() {
            return absoluteStart + buffer.length();
        }

        private boolean finished() {
            return terminal.get() != null;
        }
    }

    /**
     * 起一条后台命令，立刻返回任务号。
     *
     * @param command 完整 shell 命令（按平台交给 cmd.exe /c 或 sh -c）
     * @param workDir 工作目录，可空则用当前进程目录
     * @return 任务号，供 shell_output / kill_shell 使用
     */
    public static String start(String command, Path workDir) throws IOException {
        pruneFinished();
        String[] argv = shellArgv(command);
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(false);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        Task task = new Task("bg" + SEQUENCE.incrementAndGet(), command);
        Process process = pb.start();
        task.process = process;
        TASKS.put(task.id, task);
        Charset charset = outputCharset();
        drain(task, process.getInputStream(), charset, "");
        drain(task, process.getErrorStream(), charset, "[stderr] ");
        Thread waiter = new Thread(() -> awaitExit(task, process), "jwork-bg-" + task.id);
        waiter.setDaemon(true);
        waiter.start();
        log.info("后台命令已启动: id={}, command={}", task.id, abbreviate(command));
        return task.id;
    }

    /**
     * 读取后台任务的进度与新增输出。
     *
     * @param id          任务号
     * @param cursor      上次返回的游标（0 起）
     * @param maxChars    本次最多读多少字符
     * @param waitSeconds 没有新增输出时最多阻塞多久
     * @return 供模型直接读的结果文本
     */
    public static String poll(String id, long cursor, int maxChars, int waitSeconds) {
        Task task = TASKS.get(id);
        if (task == null) {
            return "Error: unknown task id '" + id + "'. Running tasks: " + runningIds();
        }
        if (waitSeconds > 0 && task.size() <= cursor && !task.finished()) {
            waitForOutput(task, cursor, waitSeconds);
        }
        int limit = Math.min(Math.max(1, maxChars), MAX_READ_CHARS);
        Chunk chunk = task.read(cursor, limit);
        String delta = chunk.text();
        long next = chunk.next();
        StringBuilder sb = new StringBuilder();
        sb.append("Task ").append(task.id).append(": ").append(statusOf(task)).append('\n');
        sb.append("Elapsed: ").append(Duration.between(task.startedAt, Instant.now()).getSeconds())
                .append("s\n");
        if (task.exitCode != null) {
            sb.append("Exit code: ").append(task.exitCode).append('\n');
        }
        if (delta.isEmpty()) {
            sb.append("No new output");
            if (!task.finished()) {
                sb.append(" within ").append(waitSeconds).append("s of waiting");
            }
            sb.append(".\n");
        } else {
            sb.append("Output:\n").append(delta);
            if (!delta.endsWith("\n")) {
                sb.append('\n');
            }
        }
        long droppedChars = task.dropped.get();
        if (droppedChars > 0) {
            sb.append("[Earliest ").append(droppedChars)
                    .append(" output characters were dropped; the buffer keeps the last ")
                    .append(MAX_OUTPUT_CHARS).append(".]\n");
        }
        sb.append("[Cursor for the next call: ").append(next).append("]");
        if (!task.finished()) {
            sb.append("\n[Still running - poll again with wait_seconds>0 or stop it with kill_shell].");
        }
        return sb.toString();
    }

    /** 停一个后台任务；找不到或已结束返回 false 并说明现状。 */
    public static String kill(String id) {
        Task task = TASKS.get(id);
        if (task == null) {
            return "Error: unknown task id '" + id + "'. Running tasks: " + runningIds();
        }
        if (task.finished()) {
            return "Task " + id + " already finished (" + statusOf(task) + ").";
        }
        Process process = task.process;
        if (process == null) {
            return "Error: task " + id + " has no live process.";
        }
        process.destroyForcibly();
        task.terminal.compareAndSet(null, "killed");
        log.info("后台命令已终止: id={}, command={}", id, abbreviate(task.command));
        return "Killed task " + id + " (was running for "
                + Duration.between(task.startedAt, Instant.now()).getSeconds() + "s).";
    }

    /** 在跑的任务清单：供提示与错误信息引用。 */
    public static String runningIds() {
        List<String> ids = new ArrayList<>();
        for (Task t : TASKS.values()) {
            if (!t.finished()) {
                ids.add(t.id);
            }
        }
        return ids.isEmpty() ? "none" : String.join(", ", ids);
    }

    /** 正在跑的任务数。 */
    public static int running() {
        int n = 0;
        for (Task t : TASKS.values()) {
            if (!t.finished()) {
                n++;
            }
        }
        return n;
    }

    /** 应用退出时收掉所有在跑的子进程，避免留下无人认领的孤儿进程。 */
    public static void disposeAll() {
        for (Map.Entry<String, Task> e : TASKS.entrySet()) {
            Task task = e.getValue();
            if (task.finished()) {
                continue;
            }
            Process process = task.process;
            if (process != null) {
                process.destroyForcibly();
            }
            task.terminal.compareAndSet(null, "disposed on exit");
            log.info("退出清理：已终止后台命令 {} ({})", task.id, abbreviate(task.command));
        }
        TASKS.clear();
    }

    // ===================== 内部 =====================

    /** 一次增量读取：文本片段 + 下次该传的游标（头部被丢弃时游标会跳过丢掉的量）。 */
    private record Chunk(String text, long next) {
    }

    private static void awaitExit(Task task, Process process) {
        try {
            int code = process.waitFor();
            task.exitCode = code;
            task.terminal.compareAndSet(null, code == 0 ? "finished" : "exited with code " + code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.terminal.compareAndSet(null, "interrupted");
        }
    }

    private static void drain(Task task, InputStream stream, Charset charset, String prefix) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, charset), 4096)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    task.append(prefix.isEmpty() ? line : prefix + line);
                }
            } catch (IOException e) {
                task.append(prefix + "[stream closed: " + e.getMessage() + "]");
            }
        }, "jwork-bg-io-" + task.id);
        t.setDaemon(true);
        t.start();
    }

    /** 无新增输出时的小睡：轮询粒度 200ms，任务结束立刻返回。 */
    private static void waitForOutput(Task task, long cursor, int waitSeconds) {
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        while (System.currentTimeMillis() < deadline && task.size() <= cursor && !task.finished()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String statusOf(Task task) {
        String terminal = task.terminal.get();
        return terminal == null ? "running" : terminal;
    }

    /** 清掉过期的已结束任务，防止长时间运行的进程里登记表无界增长。 */
    private static void pruneFinished() {
        Instant deadline = Instant.now().minus(FINISHED_TTL);
        TASKS.entrySet().removeIf(e -> e.getValue().finished()
                && e.getValue().startedAt.isBefore(deadline));
    }

    /** 与框架本地 shell 同规：Windows 走 cmd.exe /c，其余走 sh -c。 */
    private static String[] shellArgv(String command) {
        boolean win = OsPlatform.current().isWindows();
        return win ? new String[] {"cmd.exe", "/c", command}
                : new String[] {"sh", "-c", command};
    }

    /** 输出解码：非 Windows 一律 UTF-8，Windows 按控制台原生编码（与框架同口径）。 */
    private static Charset outputCharset() {
        if (!OsPlatform.current().isWindows()) {
            return StandardCharsets.UTF_8;
        }
        String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding != null && Charset.isSupported(nativeEncoding)) {
            return Charset.forName(nativeEncoding);
        }
        return Charset.defaultCharset();
    }

    private static String abbreviate(String command) {
        if (command == null) {
            return "";
        }
        return command.length() <= 120 ? command : command.substring(0, 120) + "...";
    }
}
