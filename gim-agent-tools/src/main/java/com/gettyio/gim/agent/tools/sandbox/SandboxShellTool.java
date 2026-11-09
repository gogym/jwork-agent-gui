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
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 装饰版 Shell 工具：以同名 {@code execute} 替换框架内置 {@link ShellExecuteTool}（ToolRegistry
 * 同名注册即覆盖），在不改变审批/HITL 行为（权限引擎按工具对象元数据评估，本类与内置工具同为
 * 普通 {@code @Tool} 对象）与本地语义（默认档完整委托内置实现）的前提下，为「自动通过档 +
 * 设置开启」的轮次提供 Docker 容器隔离执行：
 * <ul>
 *   <li><b>容器档</b>：命令经 {@link SandboxExecutor} 在一次性容器内执行（{@code --rm}），
 *       工作区挂载为 {@code /workspace}、授权项目目录挂载为 {@code /project} 并作为容器
 *       工作目录（与本地档 shell 的 pwd 语义对齐），内存/CPU 限额与超时杀进程沿用执行器约定；</li>
 *   <li><b>本地档</b>：未开启隔离、非自动通过档、或 Docker 握手失败时，完整委托内置实现
 *       （含 working_directory 校验与输出渲染），行为与替换前逐字节一致；</li>
 *   <li><b>回退</b>：开启隔离但环境不可用/执行中途异常时自动回退本地执行，并以每会话一次的
 *       提示标注「未在容器内执行」，绝不让 Shell 能力因沙箱故障而中断；</li>
 *   <li><b>后台档</b>：{@code run_in_background=true} 交给 {@link BackgroundShellRunner} 自己持有
 *       进程，配 {@code shell_output}/{@code kill_shell} 增量读进度与真停；容器档下直接拒绝，
 *       不把本该隔离的命令偷偷跑在宿主上。</li>
 * </ul>
 *
 * @author gogym
 */
public class SandboxShellTool {

    private static final Logger log = LoggerFactory.getLogger(SandboxShellTool.class);

    /** 注册工具名：与框架内置 Shell 工具同名，装配后注册即完成替换。 */
    public static final String NAME = "execute";

    /** 与框架内置 Shell 工具同源的描述，末尾补上本地增强的两档：更宽的默认超时与后台执行。 */
    private static final String DESCRIPTION =
            "Execute a shell command. Use for git, npm, build, test, and other terminal"
                    + " operations. Returns combined output and exit code. If a dedicated tool"
                    + " exists (e.g., read_file, write_file), you MUST use it instead of shell"
                    + " commands. For a command that outlives a reasonable timeout (a dev server,"
                    + " a long training or export job, watch -n), pass run_in_background=true: it"
                    + " returns a task id immediately, and shell_output(task_id) reads whatever has"
                    + " appeared so far while kill_shell(task_id) stops it.";

    /** 工具级默认超时（秒）：框架内置是 30，这里放宽到 120——pandas/openpyxl 出报表的脚本常跑过半分钟，
     *  超时报错后模型只会改个写法再跑一遍，白烧一轮。显式传 {@code timeout} 仍以模型给的为准。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /** shell_output 缺省等待秒数：给一点等待窗口，模型一次调用就能看到进展而不是空转。 */
    private static final int DEFAULT_WAIT_SECONDS = 15;

    /** shell_output 缺省单次读取字符数。 */
    private static final int DEFAULT_READ_CHARS = 12_000;

    /**
     * shell_output 等待上限（秒）：框架单次工具调用默认 5 分钟就判超时，留出收尾余量后取 120——
     * 再长也只是把风险推给框架，模型本来就能反复 poll。
     */
    private static final int MAX_WAIT_SECONDS = 120;

    /** 输出截断上限（字符），与本地档默认（LocalFilesystemSpec.maxOutputBytes=100_000）对齐。 */
    private static final int MAX_OUTPUT_CHARS = 100_000;

    /** 超时的对外退出码（与本地档超时语义一致：124）。 */
    private static final int EXIT_CODE_TIMEOUT = 124;

    /** 容器内工作区挂载路径。 */
    private static final String CONTAINER_WORKSPACE = "/workspace";

    /** 容器内项目目录挂载路径。 */
    private static final String CONTAINER_PROJECT = "/project";

    /** 本地档委托：与框架内置实现同源（同一文件系统实例），保证未隔离时行为不变。 */
    private final ShellExecuteTool localDelegate;
    /** 智能体工作区（容器档挂载为 {@code /workspace}）。 */
    private final Path workspace;
    /** 授权项目目录（可空；容器档挂载为 {@code /project} 并作为容器工作目录）。 */
    private final Path project;
    /**
     * 「该会话本轮是否自动通过档」查询（入参 sessionId，与 HITL 判定同源，由网关注入）。
     * <p>按会话查而不是一把布尔：一个工具实例服务该智能体的全部会话，档位是轮次级的。</p>
     */
    private final Predicate<String> autoApprove;
    /** 设置项：自动通过档是否启用 Docker 隔离（实时读取，改设置无需重建工具）。 */
    private final BooleanSupplier dockerEnabled;
    /** 设置项：沙箱镜像（实时读取）。 */
    private final Supplier<String> imageSupplier;
    /** Docker 连通性探针（应为带 TTL 缓存的握手探测）。 */
    private final DockerAvailabilityProbe probe;
    /** 按镜像现建执行器的工厂（默认实现即可；单测可注入打桩）。 */
    private final Function<String, SandboxExecutor> executorByImage;
    /** 已提示过回退的会话集合：回退提示每会话只出现一次，避免刷屏工具卡片。 */
    private final Set<String> fallbackNoticed = ConcurrentHashMap.newKeySet();

    public SandboxShellTool(AbstractSandboxFilesystem localFilesystem, Path workspace, Path project,
                            Predicate<String> autoApprove, BooleanSupplier dockerEnabled,
                            Supplier<String> imageSupplier, DockerAvailabilityProbe probe) {
        this(localFilesystem, workspace, project, autoApprove, dockerEnabled, imageSupplier, probe,
                image -> new SandboxExecutor(probe, image));
    }

    /** 完整构造（单测可注入执行器工厂，按当前镜像现建，用户改镜像设置即时生效）。 */
    public SandboxShellTool(AbstractSandboxFilesystem localFilesystem, Path workspace, Path project,
                            Predicate<String> autoApprove, BooleanSupplier dockerEnabled,
                            Supplier<String> imageSupplier, DockerAvailabilityProbe probe,
                            Function<String, SandboxExecutor> executorByImage) {
        this.localDelegate = new ShellExecuteTool(Objects.requireNonNull(localFilesystem, "localFilesystem"));
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.project = project;
        this.autoApprove = autoApprove == null ? sid -> false : autoApprove;
        this.dockerEnabled = dockerEnabled == null ? () -> false : dockerEnabled;
        this.imageSupplier = imageSupplier == null ? () -> SandboxExecutor.DEFAULT_IMAGE : imageSupplier;
        this.probe = probe == null ? DockerAvailabilityProbe.onPath() : probe;
        this.executorByImage = executorByImage == null
                ? image -> new SandboxExecutor(this.probe, image) : executorByImage;
    }

    /**
     * @param runtimeContext per-call agent runtime injected by the framework (not an LLM argument);
     *                       may be {@code null} when no merged context is available
     */
    @Tool(name = NAME, description = DESCRIPTION)
    public String execute(
            RuntimeContext runtimeContext,
            @ToolParam(name = "command", description = "Shell command to execute") String command,
            @ToolParam(
                            name = "working_directory",
                            description =
                                    "Working directory (relative to workspace root, optional)",
                            required = false)
                    String workingDirectory,
            @ToolParam(
                            name = "timeout",
                            description = "Timeout in seconds (default: 120)",
                            required = false)
                    Integer timeout,
            @ToolParam(
                            name = "run_in_background",
                            description = "Start the command in the background and return a task"
                                    + " id immediately instead of waiting for it (default: false)",
                            required = false)
                    Boolean runInBackground) {
        if (command == null || command.isBlank()) {
            return localDelegate.execute(runtimeContext, command, workingDirectory, timeout);
        }
        String wd = workingDirectory == null ? null : workingDirectory.strip();
        if (wd != null && !wd.isEmpty()
                && (wd.startsWith("/") || wd.startsWith("~") || wd.contains(".."))) {
            // 与框架内置校验一致：相对路径限定在工作区/项目内，绝对路径、~ 与 .. 拒绝
            return "Error: working_directory must be a relative path within the workspace"
                    + " (absolute paths, '~', and '..' are not allowed).";
        }
        int timeoutSeconds = timeout != null && timeout > 0 ? timeout : DEFAULT_TIMEOUT_SECONDS;
        if (Boolean.TRUE.equals(runInBackground)) {
            return startBackground(runtimeContext, command, wd);
        }
        if (!dockerModeEnabled(runtimeContext)) {
            // 本地档也按放宽后的默认值下发（框架内置默认 30 秒，脚本类任务不够用）
            return localDelegate.execute(runtimeContext, command, workingDirectory, timeoutSeconds);
        }
        try {
            if (!probe.available()) {
                // 已开启隔离但环境不可用：按既有本地能力继续，并每会话提示一次回退
                return fallbackNotice(runtimeContext)
                        + localDelegate.execute(runtimeContext, command, workingDirectory, timeoutSeconds);
            }
            SandboxExecutor.ExecResult result = execInSandbox(command, wd, timeoutSeconds);
            if (!result.sandboxed()) {
                // 握手后环境瞬断：执行器已按本地直执兜底（结果真实来自本机），如实标注
                return fallbackNotice(runtimeContext) + render(result, timeoutSeconds);
            }
            return render(result, timeoutSeconds);
        } catch (RuntimeException | IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Docker 沙箱执行失败，回退本地执行: {}", e.getMessage(), e);
            return fallbackNotice(runtimeContext)
                    + localDelegate.execute(runtimeContext, command, workingDirectory, timeoutSeconds);
        }
    }

    // ===================== 后台任务读取 =====================

    /**
     * 读后台任务的增量输出。游标由上一次调用给出，只回新增部分，反复轮询不会把同一段日志灌进上下文。
     *
     * @param taskId      {@code execute(run_in_background=true)} 返回的任务号
     * @param cursor      上一次返回的游标（缺省从头读）
     * @param waitSeconds 没有新增输出时的最长等待秒数
     * @param limit       本次最多返回的字符数
     */
    @Tool(name = "shell_output", readOnly = true, concurrencySafe = true,
            description = "Read the output of a command started with execute"
                    + "(run_in_background=true). Returns what has appeared since the cursor you"
                    + " passed, plus the cursor for the next call, plus whether the task is still"
                    + " running or how it ended. Pass wait_seconds to block until new output"
                    + " appears instead of polling in a tight loop.")
    public String shellOutput(
            @ToolParam(name = "task_id", description = "Task id returned by execute") String taskId,
            @ToolParam(name = "cursor", description = "Cursor from the previous reply"
                            + " (omit to read from the beginning)", required = false) Long cursor,
            @ToolParam(name = "wait_seconds", description =
                            "Seconds to wait for new output when there is none yet (default: 15,"
                                    + " maximum: 120)", required = false) Integer waitSeconds,
            @ToolParam(name = "limit", description = "Maximum characters to return this call"
                            + " (default: 12000; whatever does not fit stays behind the next"
                            + " cursor)", required = false) Integer limit) {
        if (taskId == null || taskId.isBlank()) {
            return "Error: task_id is required. Running tasks: " + BackgroundShellRunner.runningIds();
        }
        long from = cursor == null || cursor < 0 ? 0L : cursor;
        int wait = waitSeconds == null ? DEFAULT_WAIT_SECONDS
                : Math.min(Math.max(waitSeconds, 0), MAX_WAIT_SECONDS);
        int maxChars = limit == null || limit <= 0 ? DEFAULT_READ_CHARS : limit;
        return BackgroundShellRunner.poll(taskId.strip(), from, maxChars, wait);
    }

    /** 停掉后台任务：进程真被 destroyForcibly，不是只把工具调用取消掉。 */
    @Tool(name = "kill_shell",
            description = "Stop a background command started with execute(run_in_background=true)"
                    + " and report what it had been running for. Finished tasks are reported"
                    + " as-is rather than restarted.")
    public String killShell(
            @ToolParam(name = "task_id", description = "Task id returned by execute") String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return "Error: task_id is required. Running tasks: " + BackgroundShellRunner.runningIds();
        }
        return BackgroundShellRunner.kill(taskId.strip());
    }

    // ===================== 内部流程 =====================

    /**
     * 后台档：起进程、立刻返回任务号，长任务不再占着对话轮次干等。
     *
     * <p>沙箱档下直接拒绝：容器是一次性的，{@code --rm} 退出即销毁，没有「后台跑着的容器」可Poll；
     * 若此时偷偷落回本机执行，等于把用户明确要求隔离的命令跑在了宿主上。</p>
     */
    private String startBackground(RuntimeContext runtimeContext, String command, String wd) {
        String notice = "";
        if (dockerModeEnabled(runtimeContext)) {
            if (probeAvailable()) {
                return "Error: run_in_background=true is not available while Docker sandbox isolation"
                        + " is active (each sandboxed command runs in a one-shot container)."
                        + " Run it without run_in_background and raise timeout, or turn off sandbox"
                        + " isolation in settings for long-running jobs.";
            }
            // Docker 已开但握手不上：同步档此时同样按本地执行，后台如实标注同一口径
            notice = fallbackNotice(runtimeContext);
        }
        try {
            String taskId = BackgroundShellRunner.start(command, backgroundDir(wd));
            return notice + "Started background task " + taskId + ".\n"
                    + "Read what has appeared so far with shell_output(task_id=\"" + taskId
                    + "\", cursor=<the cursor from the previous reply>) and stop it with"
                    + " kill_shell(task_id=\"" + taskId + "\").\n"
                    + "The command keeps running while you do other work in this turn - poll it"
                    + " near the end rather than assuming it is done.";
        } catch (RuntimeException | IOException e) {
            log.warn("后台命令启动失败: {}", e.getMessage(), e);
            return "Error: failed to start the background command: " + e.getMessage();
        }
    }

    /**
     * 后台任务的工作目录：与本地档 shell 的 pwd 对齐（有授权项目目录时落在项目目录），
     * working_directory 仍是已通过校验的工作区内相对路径。
     */
    private Path backgroundDir(String wd) {
        Path base = project != null ? project : workspace;
        if (wd == null || wd.isEmpty()) {
            return base;
        }
        Path resolved = base.resolve(wd).normalize();
        return resolved.startsWith(base) ? resolved : base;
    }

    /** Docker 握手（探针异常按不可用处理，与同步档的回退口径一致）。 */
    private boolean probeAvailable() {
        try {
            return probe.available();
        } catch (RuntimeException e) {
            log.warn("Docker 探针失败，视为不可用: {}", e.getMessage());
            return false;
        }
    }

    /** 是否走容器档：本轮自动通过（黑名单仍由权限链拦截）且用户开启了 Docker 隔离。 */
    private boolean dockerModeEnabled(RuntimeContext runtimeContext) {
        try {
            String sessionId = runtimeContext == null ? null : runtimeContext.getSessionId();
            return autoApprove.test(sessionId) && dockerEnabled.getAsBoolean();
        } catch (RuntimeException e) {
            log.warn("读取沙箱开关失败，按本地执行: {}", e.getMessage());
            return false;
        }
    }

    /** 在一次性容器内执行：工作区挂 /workspace、项目目录挂 /project（作为容器工作目录）。 */
    private SandboxExecutor.ExecResult execInSandbox(String command, String wd, int timeoutSeconds)
            throws IOException, InterruptedException {
        String effectiveCommand = command;
        if (wd != null && !wd.isEmpty()) {
            // 容器内为 POSIX sh：cd 用单引号包裹并转义，与框架本地 POSIX 分支同规
            effectiveCommand = "cd " + posixQuote(wd) + " && " + command;
        }
        List<SandboxExecutor.Mount> mounts = new ArrayList<>();
        mounts.add(new SandboxExecutor.Mount(workspace, CONTAINER_WORKSPACE, false));
        String containerCwd = CONTAINER_WORKSPACE;
        if (project != null && !project.equals(workspace)) {
            // 授权项目目录同步挂载：与本地档 shell 的 pwd（项目目录）语义对齐
            mounts.add(new SandboxExecutor.Mount(project, CONTAINER_PROJECT, false));
            containerCwd = CONTAINER_PROJECT;
        }
        String image = imageSupplier.get();
        SandboxExecutor executor = executorByImage.apply(
                image == null || image.isBlank() ? SandboxExecutor.DEFAULT_IMAGE : image);
        return executor.exec(List.of("sh", "-c", effectiveCommand), mounts, containerCwd,
                workspace, Duration.ofSeconds(timeoutSeconds), 0, 0);
    }

    /** 渲染容器档结果：输出与退出码口径对齐框架内置实现（stderr 行加前缀、截断标注、超时 124）。 */
    private static String render(SandboxExecutor.ExecResult result, int timeoutSeconds) {
        int exitCode = result.exitCode() == SandboxExecutor.EXIT_TIMEOUT
                ? EXIT_CODE_TIMEOUT : result.exitCode();
        StringBuilder body = new StringBuilder();
        if (result.exitCode() == SandboxExecutor.EXIT_TIMEOUT) {
            body.append("Error: Command timed out after ").append(timeoutSeconds)
                    .append(" seconds. The command may be stuck or require more time.");
        } else if (result.stdout() != null && !result.stdout().isEmpty()) {
            body.append(result.stdout());
        }
        if (result.stderr() != null && !result.stderr().isBlank()) {
            for (String line : result.stderr().strip().split("\n")) {
                if (!body.isEmpty()) {
                    body.append('\n');
                }
                body.append("[stderr] ").append(line);
            }
        }
        String output = body.isEmpty() ? "<no output>" : body.toString();
        boolean truncated = false;
        if (output.length() > MAX_OUTPUT_CHARS) {
            output = output.substring(0, MAX_OUTPUT_CHARS)
                    + "\n\n... Output truncated at " + MAX_OUTPUT_CHARS + " bytes.";
            truncated = true;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Exit code: ").append(exitCode).append("\n");
        if (!output.isBlank()) {
            sb.append("\n").append(output);
        }
        if (truncated) {
            sb.append("\n(output was truncated)");
        }
        return sb.toString();
    }

    /** 一次性回退提示（每会话一条）：让用户与模型明确知道该命令并未在容器内执行。 */
    private String fallbackNotice(RuntimeContext runtimeContext) {
        String sessionId = runtimeContext == null ? null : runtimeContext.getSessionId();
        String key = sessionId == null || sessionId.isBlank() ? "-" : sessionId;
        if (!fallbackNoticed.add(key)) {
            return "";
        }
        return "[jwork] Docker 沙箱不可用，已按本地方式执行该命令。\n\n";
    }

    /** POSIX 单引号包裹（内嵌单引号转义为 {@code '\''}），与框架本地 POSIX 分支同规。 */
    private static String posixQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
