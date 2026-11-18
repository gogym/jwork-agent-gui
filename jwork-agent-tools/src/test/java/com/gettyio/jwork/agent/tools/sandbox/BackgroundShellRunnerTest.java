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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BackgroundShellRunner} 单测：真起子进程验证「立刻返回任务号 → 增量读输出 → 真停进程 →
 * 退出清理」这条链，并按平台选命令（Windows 走 cmd.exe，其余走 sh）。
 *
 * @author gogym
 */
class BackgroundShellRunnerTest {

    private static final boolean WIN =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        BackgroundShellRunner.disposeAll();
    }

    /** 挂指定秒数（Windows 用 ping 代替 sleep，避免依赖 coreutils）。 */
    private static String wait(int seconds) {
        return WIN ? "ping -n " + (seconds + 1) + " 127.0.0.1 > nul" : "sleep " + seconds;
    }

    @Test
    @DisplayName("后台跑完：输出与退出码都能读到，任务号即时返回")
    void startThenPollReportsOutputAndExitCode() throws IOException {
        String id = BackgroundShellRunner.start("echo bg-marker", null);

        assertTrue(id.startsWith("bg"), id);
        String out = readAll(id);
        assertTrue(out.contains("bg-marker"), out);

        String finalPoll = lastPoll(out);
        assertTrue(finalPoll.contains("Exit code: 0"), finalPoll);
        assertFalse(finalPoll.contains("Still running"), finalPoll);
    }

    @Test
    @DisplayName("游标只回新增：第二次读不会把同一段输出再灌一遍")
    void secondPollReturnsOnlyNewOutput() throws IOException {
        String id = BackgroundShellRunner.start("echo first-line && echo second-line", null);

        String first = awaitOutput(id, "second-line");
        assertTrue(first.contains("first-line"), first);
        long cursor = cursorOf(first, 0);

        String again = BackgroundShellRunner.poll(id, cursor, 5_000, 0);
        assertTrue(again.contains("No new output"), again);
        assertEquals(cursor, cursorOf(again, -1), "读完增量后游标不应回退");
    }

    @Test
    @DisplayName("工作目录生效：后台命令就在指定目录里起")
    void startUsesGivenWorkDir() throws IOException {
        Path sub = tempDir.resolve("job");
        Files.createDirectories(sub);

        String id = BackgroundShellRunner.start(WIN ? "cd" : "pwd", sub);

        assertTrue(readAll(id).contains(sub.getFileName().toString()));
    }

    @Test
    @DisplayName("kill 真停进程：已结束的任务不会被重复杀")
    void killStopsRunningTaskAndReportsFinishedOnes() throws IOException {
        int before = BackgroundShellRunner.running();
        String id = BackgroundShellRunner.start(wait(60), null);
        assertEquals(before + 1, BackgroundShellRunner.running());

        String killed = BackgroundShellRunner.kill(id);
        assertTrue(killed.startsWith("Killed task "), killed);
        assertEquals(before, BackgroundShellRunner.running());

        assertTrue(lastPoll(readAll(id)).contains("killed"));
        assertTrue(BackgroundShellRunner.kill(id).contains("already finished"));
    }

    @Test
    @DisplayName("任务号认错：给出错误与在跑清单，而不是空输出")
    void unknownTaskIdIsReported() {
        String out = BackgroundShellRunner.poll("bg999", 0, 100, 0);

        assertTrue(out.startsWith("Error: unknown task id"), out);
        assertTrue(out.contains("bg999"), out);
        assertTrue(BackgroundShellRunner.kill("bg999").startsWith("Error: unknown task id"));
    }

    @Test
    @DisplayName("退出清理：在跑的子进程一并收掉，登记表清空")
    void disposeAllStopsEverything() throws IOException {
        String id = BackgroundShellRunner.start(wait(60), null);

        BackgroundShellRunner.disposeAll();

        assertEquals(0, BackgroundShellRunner.running());
        assertTrue(BackgroundShellRunner.poll(id, 0, 100, 0).startsWith("Error: unknown task id"));
    }

    @Test
    @DisplayName("输出爆表只丢头部：最新一行仍在，游标始终是绝对位置")
    void oversizedOutputDropsOnlyHeadLines() throws IOException {
        Path big = tempDir.resolve("big.txt");
        StringBuilder body = new StringBuilder("head-marker-never-comes-back\n");
        for (int i = 0; i < 4_000; i++) {
            body.append("noise-line-").append(i).append("-".repeat(60)).append('\n');
        }
        body.append("tail-marker-still-here\n");
        Files.writeString(big, body.toString(), StandardCharsets.UTF_8);

        String id = BackgroundShellRunner.start(WIN ? "type \"" + big + "\"" : "cat " + big, null);

        String out = readAll(id);
        assertTrue(out.contains("tail-marker-still-here"), "尾部应留在缓冲里");
        assertFalse(out.contains("head-marker-never-comes-back"), "最早的行应已被丢掉");
        assertTrue(out.contains("[Earliest"), out);
        assertTrue(cursorOf(lastPoll(out), -1) > BackgroundShellRunner.MAX_OUTPUT_CHARS,
                "游标应走到整条输出流的绝对位置，而不是被截断后归零");
    }

    // ===================== 辅助 =====================

    /** 反复读到「已结束且再无新内容」，返回每次 poll 文本的拼串（最多 400 轮）。 */
    private static String readAll(String id) {
        StringBuilder all = new StringBuilder();
        long at = 0;
        for (int i = 0; i < 400; i++) {
            String last = BackgroundShellRunner.poll(id, at, 20_000, 1);
            all.append(last);
            long next = cursorOf(last, at);
            boolean progressed = next > at;
            at = next;
            if (!last.contains("Still running") && !progressed) {
                break;
            }
        }
        return all.toString();
    }

    /** 轮询直到某次 poll 文本里出现 needle（最多 400 轮），返回那一刻的文本。 */
    private static String awaitOutput(String id, String needle) {
        long at = 0;
        String last = "";
        for (int i = 0; i < 400; i++) {
            last = BackgroundShellRunner.poll(id, at, 20_000, 1);
            long next = cursorOf(last, at);
            boolean progressed = next > at;
            at = next;
            if (last.contains(needle)) {
                return last;
            }
            if (!last.contains("Still running") && !progressed) {
                break;
            }
        }
        throw new AssertionError("timed out waiting for '" + needle + "' in task " + id
                + "; last poll said: " + last);
    }

    /** 拼串里最后一段 poll 文本：终态判断只看它，中间轮次的「还在跑」提示不算失败。 */
    private static String lastPoll(String all) {
        int at = all.lastIndexOf("Task bg");
        return at < 0 ? all : all.substring(at);
    }

    private static long cursorOf(String pollOutput, long fallback) {
        String marker = "[Cursor for the next call: ";
        int from = pollOutput.lastIndexOf(marker);
        if (from < 0) {
            return fallback;
        }
        int to = pollOutput.indexOf(']', from + marker.length());
        return Long.parseLong(pollOutput.substring(from + marker.length(), to));
    }
}
