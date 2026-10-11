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

package com.gettyio.gim.agent.tools.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SandboxExecutor} 单测：以桩 {@link Process} 验证 docker 一次性容器命令编排
 * （--rm/挂载/内存/CPU 限制）、本地回退、探测异常收敛与超时强杀语义，全程无 Docker 依赖。
 *
 * @author gogym
 */
class SandboxExecutorTest {

    @TempDir
    Path tempDir;

    // ---- 桩进程 ----

    /** 可控结束时机的假进程：输出回放、结束由 latch 控制，超时用例不倒计数。 */
    private static final class FakeProcess extends Process {
        private final int exit;
        private final byte[] out;
        private final byte[] err;
        private final CountDownLatch latch;

        FakeProcess(int exit, String out, String err, boolean finishImmediately) {
            this.exit = exit;
            this.out = out.getBytes(StandardCharsets.UTF_8);
            this.err = err.getBytes(StandardCharsets.UTF_8);
            this.latch = new CountDownLatch(finishImmediately ? 0 : 1);
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(out);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(err);
        }

        @Override
        public int waitFor() throws InterruptedException {
            latch.await();
            return exit;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            return exit;
        }

        @Override
        public void destroy() {
            latch.countDown();
        }

        @Override
        public Process destroyForcibly() {
            latch.countDown();
            return this;
        }

        @Override
        public boolean isAlive() {
            return latch.getCount() > 0;
        }
    }

    /** 记录启动命令的桩启动器。 */
    private static final class RecordingStarter implements SandboxExecutor.ProcessStarter {
        final List<List<String>> started = new ArrayList<>();
        private final FakeProcess process;

        RecordingStarter(FakeProcess process) {
            this.process = process;
        }

        @Override
        public Process start(List<String> command, Path workDir) {
            started.add(List.copyOf(command));
            return process;
        }
    }

    @Test
    @DisplayName("Docker 可用：命令编排为一次性容器（--rm/挂载 /workspace/内存/CPU 限制）并回传输出")
    void dockerCommandShapeAndOutput() throws Exception {
        RecordingStarter starter = new RecordingStarter(new FakeProcess(0, "hello out", "warn err", true));
        SandboxExecutor executor = new SandboxExecutor(() -> true, "python:3.12-slim", starter);

        SandboxExecutor.ExecResult r = executor.exec(
                List.of("python", "app.py"), tempDir, Duration.ofSeconds(10), 256, 0.5);

        assertTrue(r.sandboxed());
        assertEquals(0, r.exitCode());
        assertEquals("hello out", r.stdout());
        assertEquals("warn err", r.stderr());

        assertEquals(1, starter.started.size());
        List<String> cmd = starter.started.get(0);
        assertEquals("docker", cmd.get(0));
        assertEquals("run", cmd.get(1));
        assertTrue(cmd.contains("--rm"));
        int v = cmd.indexOf("-v");
        assertTrue(v >= 0 && cmd.get(v + 1).endsWith(":/workspace"));
        int w = cmd.indexOf("-w");
        assertTrue(w >= 0 && "/workspace".equals(cmd.get(w + 1)));
        int m = cmd.indexOf("--memory");
        assertTrue(m >= 0 && "256m".equals(cmd.get(m + 1)));
        int c = cmd.indexOf("--cpus");
        assertTrue(c >= 0 && "0.5".equals(cmd.get(c + 1)));
        assertTrue(cmd.contains("python:3.12-slim"));
        // 原命令追加在镜像之后，不被 shell 拼接
        assertTrue(cmd.subList(cmd.indexOf("python:3.12-slim") + 1, cmd.size())
                .equals(List.of("python", "app.py")));
    }

    @Test
    @DisplayName("Docker 不可用：无缝回退本地直执，命令原样启动")
    void fallbackToLocal() throws Exception {
        RecordingStarter starter = new RecordingStarter(new FakeProcess(3, "", "", true));
        SandboxExecutor executor = new SandboxExecutor(() -> false, "python:3.12-slim", starter);

        SandboxExecutor.ExecResult r = executor.exec(
                List.of("echo", "hi"), tempDir, Duration.ofSeconds(10), 0, 0);

        assertFalse(r.sandboxed());
        assertEquals(3, r.exitCode());
        assertEquals(List.of("echo", "hi"), starter.started.get(0));
    }

    @Test
    @DisplayName("探测抛异常：按不可用收敛，回退本地不外抛")
    void probeExplosionFallsBack() throws Exception {
        RecordingStarter starter = new RecordingStarter(new FakeProcess(0, "", "", true));
        DockerAvailabilityProbe boom = () -> {
            throw new IllegalStateException("docker broken");
        };
        SandboxExecutor executor = new SandboxExecutor(boom, "python:3.12-slim", starter);

        SandboxExecutor.ExecResult r = executor.exec(
                List.of("true"), tempDir, Duration.ofSeconds(10), 0, 0);

        assertFalse(r.sandboxed());
        assertFalse(executor.dockerAvailable());
    }

    @Test
    @DisplayName("超时：强杀进程，退出码 -1，stderr 带超时标记")
    void timeoutKills() throws Exception {
        RecordingStarter starter = new RecordingStarter(new FakeProcess(0, "partial", "", false));
        SandboxExecutor executor = new SandboxExecutor(() -> false, "python:3.12-slim", starter);

        SandboxExecutor.ExecResult r = executor.exec(
                List.of("sleep"), tempDir, Duration.ofMillis(120), 0, 0);

        assertEquals(SandboxExecutor.EXIT_TIMEOUT, r.exitCode());
        assertTrue(r.stderr().contains("超时"));
    }

    @Test
    @DisplayName("空命令拒绝执行")
    void emptyCommandRejected() {
        SandboxExecutor executor = new SandboxExecutor(() -> false, null, (cmd, dir) -> null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> executor.exec(List.of(), tempDir, null, 0, 0));
    }
}
