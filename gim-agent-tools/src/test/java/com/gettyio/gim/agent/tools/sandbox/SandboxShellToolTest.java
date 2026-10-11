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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SandboxShellTool} 单测：桩文件系统（本地委托目标）+ 记录型桩执行器，
 * 验证 docker 档路由（多挂载/容器工作目录/结果渲染）、本地档完整委托、握手不可用回退
 * （每会话仅一次提示）、执行器瞬时回退与运行时异常回退，全程无 Docker 依赖。
 *
 * @author gogym
 */
class SandboxShellToolTest {

    @TempDir
    Path tempDir;

    /** 记录调用的桩执行器：可设定返回值或抛出故障。 */
    private static final class RecordingExecutor extends SandboxExecutor {
        private SandboxExecutor.ExecResult result;
        private IOException failure;
        private int calls;
        private List<Mount> lastMounts;
        private String lastWorkDir;
        private List<String> lastCommand;

        private RecordingExecutor() {
            super(() -> true, "python:3.12-slim");
        }

        @Override
        public ExecResult exec(List<String> command, List<Mount> mounts, String containerWorkDir,
                               Path localWorkDir, Duration timeout, long memoryMB, double cpus)
                throws IOException {
            calls++;
            lastCommand = command;
            lastMounts = mounts;
            lastWorkDir = containerWorkDir;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private AbstractSandboxFilesystem fs;

    /** 组装被测工具：本地档委托目标为桩文件系统，容器档执行器为记录桩。 */
    private SandboxShellTool newTool(boolean autoApprove, boolean dockerEnabled,
                                     DockerAvailabilityProbe probe, Path project,
                                     RecordingExecutor executor) {
        fs = mock(AbstractSandboxFilesystem.class);
        when(fs.execute(any(), anyString(), any())).thenReturn(new ExecuteResponse("local out", 0, false));
        return new SandboxShellTool(fs, tempDir, project,
                () -> autoApprove, () -> dockerEnabled, () -> "python:3.12-slim",
                probe, image -> executor);
    }

    private static RuntimeContext ctx(String sessionId) {
        return RuntimeContext.builder().sessionId(sessionId).build();
    }

    @Test
    @DisplayName("非自动通过档：完整委托本地实现，不触碰执行器")
    void localModeDelegates() {
        RecordingExecutor executor = new RecordingExecutor();
        SandboxShellTool tool = newTool(false, true, () -> true, null, executor);

        String out = tool.execute(ctx("s1"), "echo hi", null, null);

        assertEquals("Exit code: 0\n\nlocal out", out);
        assertEquals(0, executor.calls);
    }

    @Test
    @DisplayName("Docker 档：挂载工作区（无项目时容器目录 /workspace），渲染容器输出")
    void dockerModeExecutesInSandbox() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.result = new SandboxExecutor.ExecResult(0, "hello docker", "", true, 1L);
        SandboxShellTool tool = newTool(true, true, () -> true, null, executor);

        String out = tool.execute(ctx("s1"), "echo hello", null, null);

        assertEquals("Exit code: 0\n\nhello docker", out);
        assertEquals(1, executor.calls);
        assertEquals(List.of("sh", "-c", "echo hello"), executor.lastCommand);
        assertEquals("/workspace", executor.lastWorkDir);
        assertEquals(1, executor.lastMounts.size());
        assertEquals("/workspace", executor.lastMounts.get(0).containerPath());
        verify(fs, never()).execute(any(), anyString(), any());
    }

    @Test
    @DisplayName("Docker 档：授权项目目录挂载为 /project 并作为容器工作目录，wd 追加 POSIX cd")
    void dockerModeMountsProject() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.result = new SandboxExecutor.ExecResult(0, "ok", "", true, 1L);
        Path project = tempDir.resolve("proj");
        SandboxShellTool tool = newTool(true, true, () -> true, project, executor);

        String out = tool.execute(ctx("s1"), "ls", "src/main", null);

        assertEquals("Exit code: 0\n\nok", out);
        assertEquals("/project", executor.lastWorkDir);
        assertEquals(2, executor.lastMounts.size());
        assertEquals("/project", executor.lastMounts.get(1).containerPath());
        assertEquals(List.of("sh", "-c", "cd 'src/main' && ls"), executor.lastCommand);
    }

    @Test
    @DisplayName("握手不可用：回退本地执行，回退提示每会话仅一次")
    void handshakeDownFallsBackOncePerSession() {
        RecordingExecutor executor = new RecordingExecutor();
        SandboxShellTool tool = newTool(true, true, () -> false, null, executor);
        RuntimeContext rc = ctx("s1");

        String first = tool.execute(rc, "echo hi", null, null);
        String second = tool.execute(rc, "echo hi", null, null);

        assertTrue(first.startsWith("[jwork] Docker 沙箱不可用"), first);
        assertTrue(first.endsWith("Exit code: 0\n\nlocal out"), first);
        assertFalse(second.startsWith("[jwork]"), second);
        assertEquals("Exit code: 0\n\nlocal out", second);
        assertEquals(0, executor.calls);
        verify(fs, times(2)).execute(any(), anyString(), any());
    }

    @Test
    @DisplayName("执行器瞬时回退（sandboxed=false）：如实标注并渲染其本地结果")
    void runtimeFallbackNotice() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.result = new SandboxExecutor.ExecResult(3, "", "boom", false, 1L);
        SandboxShellTool tool = newTool(true, true, () -> true, null, executor);

        String out = tool.execute(ctx("s1"), "exit 3", null, null);

        assertTrue(out.startsWith("[jwork] Docker 沙箱不可用"), out);
        assertTrue(out.contains("Exit code: 3"), out);
        assertTrue(out.contains("[stderr] boom"), out);
    }

    @Test
    @DisplayName("执行器抛 IOException：回退本地执行并带提示")
    void executorIOExceptionFallsBack() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.failure = new IOException("docker cli gone");
        SandboxShellTool tool = newTool(true, true, () -> true, null, executor);

        String out = tool.execute(ctx("s1"), "echo hi", null, null);

        assertTrue(out.startsWith("[jwork] Docker 沙箱不可用"), out);
        assertTrue(out.endsWith("Exit code: 0\n\nlocal out"), out);
        assertEquals(1, executor.calls);
        verify(fs, times(1)).execute(any(), anyString(), any());
    }

    @Test
    @DisplayName("容器超时：退出码折算 124，附超时说明与 [stderr] 前缀")
    void timeoutRenderedAs124() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.result = new SandboxExecutor.ExecResult(
                SandboxExecutor.EXIT_TIMEOUT, "", "[jwork] 命令超时被终止（1s）", true, 1L);
        SandboxShellTool tool = newTool(true, true, () -> true, null, executor);

        String out = tool.execute(ctx("s1"), "sleep 5", null, 1);

        assertTrue(out.contains("Exit code: 124"), out);
        assertTrue(out.contains("Command timed out after 1 seconds"), out);
        assertTrue(out.contains("[stderr] [jwork] 命令超时被终止（1s）"), out);
    }

    @Test
    @DisplayName("working_directory 越界：直接拒绝，不落任何执行路径")
    void invalidWorkingDirectoryRejected() {
        RecordingExecutor executor = new RecordingExecutor();
        SandboxShellTool tool = newTool(true, true, () -> true, null, executor);

        String out = tool.execute(ctx("s1"), "ls", "../outside", null);

        assertTrue(out.startsWith("Error: working_directory must be a relative path"), out);
        assertEquals(0, executor.calls);
        verify(fs, never()).execute(any(), anyString(), any());
    }
}
