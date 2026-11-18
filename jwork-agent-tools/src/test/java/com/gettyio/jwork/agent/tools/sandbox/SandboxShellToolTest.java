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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>后台档另计：那条链必须真起子进程才能证明「立刻返回、增量可读、真能停」，故用 echo 与
 * 平台原生等待命令跑最短的任务，每个用例结束统一 {@code disposeAll} 收口。</p>
 *
 * @author gogym
 */
class SandboxShellToolTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void disposeBackgroundTasks() {
        BackgroundShellRunner.disposeAll();
    }

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
                sid -> autoApprove, () -> dockerEnabled, () -> "python:3.12-slim",
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

        String out = tool.execute(ctx("s1"), "echo hi", null, null, null);

        assertEquals("Exit code: 0\n\nlocal out", out);
        assertEquals(0, executor.calls);
    }

    @Test
    @DisplayName("档位按会话解析：自动档会话的选择不外溢到同实例的询问档会话")
    void approvalTierIsResolvedPerSession() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.result = new SandboxExecutor.ExecResult(0, "ok", "", true, 1L);
        fs = mock(AbstractSandboxFilesystem.class);
        when(fs.execute(any(), anyString(), any())).thenReturn(new ExecuteResponse("local out", 0, false));
        // 只有 auto-session 处在「自动」档：工具实例是跨会话共用的，档位必须按 sessionId 现取
        SandboxShellTool tool = new SandboxShellTool(fs, tempDir, null,
                sid -> "auto-session".equals(sid), () -> true, () -> "python:3.12-slim",
                () -> true, image -> executor);

        tool.execute(ctx("auto-session"), "echo hi", null, null, null);
        assertEquals(1, executor.calls, "自动档会话走容器隔离");

        String out = tool.execute(ctx("ask-session"), "echo hi", null, null, null);
        assertEquals(1, executor.calls, "询问档会话不该被别的会话的档位代签");
        assertEquals("Exit code: 0\n\nlocal out", out);
    }

    @Test
    @DisplayName("Docker 档：挂载工作区（无项目时容器目录 /workspace），渲染容器输出")
    void dockerModeExecutesInSandbox() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.result = new SandboxExecutor.ExecResult(0, "hello docker", "", true, 1L);
        SandboxShellTool tool = newTool(true, true, () -> true, null, executor);

        String out = tool.execute(ctx("s1"), "echo hello", null, null, null);

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

        String out = tool.execute(ctx("s1"), "ls", "src/main", null, null);

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

        String first = tool.execute(rc, "echo hi", null, null, null);
        String second = tool.execute(rc, "echo hi", null, null, null);

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

        String out = tool.execute(ctx("s1"), "exit 3", null, null, null);

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

        String out = tool.execute(ctx("s1"), "echo hi", null, null, null);

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

        String out = tool.execute(ctx("s1"), "sleep 5", null, 1, null);

        assertTrue(out.contains("Exit code: 124"), out);
        assertTrue(out.contains("Command timed out after 1 seconds"), out);
        assertTrue(out.contains("[stderr] [jwork] 命令超时被终止（1s）"), out);
    }

    @Test
    @DisplayName("working_directory 越界：直接拒绝，不落任何执行路径")
    void invalidWorkingDirectoryRejected() {
        RecordingExecutor executor = new RecordingExecutor();
        SandboxShellTool tool = newTool(true, true, () -> true, null, executor);

        String out = tool.execute(ctx("s1"), "ls", "../outside", null, null);

        assertTrue(out.startsWith("Error: working_directory must be a relative path"), out);
        assertEquals(0, executor.calls);
        verify(fs, never()).execute(any(), anyString(), any());
    }

    // ===================== 后台档 =====================

    @Test
    @DisplayName("run_in_background：立刻返回任务号，shell_output 读到输出，kill_shell 收尾")
    void backgroundModeStartsPollsAndKills() {
        RecordingExecutor executor = new RecordingExecutor();
        SandboxShellTool tool = newTool(false, false, () -> false, null, executor);

        String started = tool.execute(ctx("s1"), "echo bg-tool-marker", null, null, true);

        assertTrue(started.startsWith("Started background task bg"), started);
        assertTrue(started.contains("shell_output"), started);
        String taskId = taskIdOf(started);
        assertEquals(0, executor.calls);

        String polled = pollUntilDone(tool, taskId);
        assertTrue(polled.contains("bg-tool-marker"), polled);
        // 终态只看最后一段 poll：中间轮次「还在跑」是正常的
        String finalPoll = polled.substring(polled.lastIndexOf("Task bg"));
        assertTrue(finalPoll.contains("Exit code: 0"), finalPoll);
        assertFalse(finalPoll.contains("Still running"), finalPoll);
        assertTrue(tool.killShell(taskId).contains("already finished"), tool.killShell(taskId));
    }

    @Test
    @DisplayName("沙箱档在线：拒绝后台执行，不把该隔离的命令偷偷跑在宿主上")
    void backgroundRefusedWhileSandboxed() {
        RecordingExecutor executor = new RecordingExecutor();
        SandboxShellTool tool = newTool(true, true, () -> true, null, executor);
        int before = BackgroundShellRunner.running();

        String out = tool.execute(ctx("s1"), "node server.js", null, null, true);

        assertTrue(out.startsWith("Error: run_in_background=true is not available while Docker"), out);
        assertEquals(before, BackgroundShellRunner.running());
        assertEquals(0, executor.calls);
        verify(fs, never()).execute(any(), anyString(), any());
    }

    @Test
    @DisplayName("沙箱开关开着但握手不上：按本地起后台任务，并如实标注未在容器内")
    void backgroundFallsBackToLocalWithNotice() {
        RecordingExecutor executor = new RecordingExecutor();
        SandboxShellTool tool = newTool(true, true, () -> false, null, executor);

        String out = tool.execute(ctx("s1"), "echo bg-fallback-marker", null, null, true);

        assertTrue(out.startsWith("[jwork] Docker 沙箱不可用"), out);
        assertTrue(out.contains("Started background task bg"), out);
        assertTrue(pollUntilDone(tool, taskIdOf(out)).contains("bg-fallback-marker"));
    }

    @Test
    @DisplayName("后台档同样校验相对路径：越界目录不起进程")
    void backgroundRejectsEscapingWorkingDirectory() {
        RecordingExecutor executor = new RecordingExecutor();
        SandboxShellTool tool = newTool(false, false, () -> false, null, executor);
        int before = BackgroundShellRunner.running();

        String out = tool.execute(ctx("s1"), "ls", "../outside", null, true);

        assertTrue(out.startsWith("Error: working_directory must be a relative path"), out);
        assertEquals(before, BackgroundShellRunner.running());
    }

    @Test
    @DisplayName("任务号缺失：shell_output / kill_shell 都报错并列出在跑任务")
    void missingTaskIdIsReported() {
        SandboxShellTool tool = newTool(false, false, () -> false, null, new RecordingExecutor());

        assertTrue(tool.shellOutput(" ", null, null, null).startsWith("Error: task_id is required"));
        assertTrue(tool.killShell(null).startsWith("Error: task_id is required"));
        assertTrue(tool.shellOutput("bg404", null, 0, null).startsWith("Error: unknown task id"));
    }

    @Test
    @DisplayName("工具面：一次注册同时给出 execute/shell_output/kill_shell，新参数也进得了 schema")
    void toolSurfaceExposesBackgroundTools() {
        SandboxShellTool tool = newTool(false, false, () -> false, null, new RecordingExecutor());
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);

        assertTrue(toolkit.getToolNames().containsAll(
                        List.of("execute", "shell_output", "kill_shell")),
                toolkit.getToolNames().toString());

        Map<String, Object> executeProps = propertiesOf(schema(toolkit, "execute"));
        assertEquals("boolean", type(executeProps.get("run_in_background")),
                "run_in_background 应是布尔参数");
        assertFalse(requiredOf(schema(toolkit, "execute")).contains("run_in_background"));

        ToolSchema poll = schema(toolkit, "shell_output");
        assertEquals("integer", type(propertiesOf(poll).get("cursor")));
        assertEquals(List.of("task_id"), requiredOf(poll));
    }

    // ===================== 辅助 =====================

    private static ToolSchema schema(Toolkit toolkit, String name) {
        return toolkit.getToolSchemas().stream()
                .filter(s -> name.equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("工具未出现在 schema 列表里: " + name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertiesOf(ToolSchema schema) {
        return (Map<String, Object>) schema.getParameters().get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<String> requiredOf(ToolSchema schema) {
        return (List<String>) schema.getParameters().getOrDefault("required", List.of());
    }

    @SuppressWarnings("unchecked")
    private static String type(Object paramSchema) {
        return paramSchema instanceof Map<?, ?> m
                ? String.valueOf(((Map<String, Object>) m).get("type")) : null;
    }

    /** 从「Started background task bgN」里取任务号。 */
    private static String taskIdOf(String started) {
        Matcher m = Pattern.compile("bg\\d+").matcher(started);
        assertTrue(m.find(), started);
        return m.group();
    }

    /** 用工具面读到「已结束且再无新内容」为止（最多 60 轮），返回拼起来的输出。 */
    private static String pollUntilDone(SandboxShellTool tool, String taskId) {
        StringBuilder all = new StringBuilder();
        long cursor = 0;
        for (int i = 0; i < 60; i++) {
            String out = tool.shellOutput(taskId, cursor, 1, 20_000);
            all.append(out);
            long next = cursorOf(out, cursor);
            boolean progressed = next > cursor;
            cursor = next;
            if (!out.contains("Still running") && !progressed) {
                break;
            }
        }
        return all.toString();
    }

    private static long cursorOf(String pollOutput, long fallback) {
        Matcher m = Pattern.compile("\\[Cursor for the next call: (\\d+)]").matcher(pollOutput);
        return m.find() ? Long.parseLong(m.group(1)) : fallback;
    }
}
