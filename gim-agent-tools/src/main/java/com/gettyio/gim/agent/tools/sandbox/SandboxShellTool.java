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
 *       提示标注「未在容器内执行」，绝不让 Shell 能力因沙箱故障而中断。</li>
 * </ul>
 *
 * @author gogym
 */
public class SandboxShellTool {

    private static final Logger log = LoggerFactory.getLogger(SandboxShellTool.class);

    /** 注册工具名：与框架内置 Shell 工具同名，装配后注册即完成替换。 */
    public static final String NAME = "execute";

    /** 与框架内置 Shell 工具完全一致的描述：替换实现不应改变模型对工具的既有理解。 */
    private static final String DESCRIPTION =
            "Execute a shell command. Use for git, npm, build, test, and other terminal"
                    + " operations. Returns combined output and exit code. If a dedicated tool"
                    + " exists (e.g., read_file, write_file), you MUST use it instead of shell"
                    + " commands.";

    /** 工具级默认超时（秒），与框架内置实现一致。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

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
    /** 按调用时刻读取的「本轮自动通过档」标记（与 HITL 判定同源，由网关注入）。 */
    private final BooleanSupplier autoApprove;
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
                            BooleanSupplier autoApprove, BooleanSupplier dockerEnabled,
                            Supplier<String> imageSupplier, DockerAvailabilityProbe probe) {
        this(localFilesystem, workspace, project, autoApprove, dockerEnabled, imageSupplier, probe,
                image -> new SandboxExecutor(probe, image));
    }

    /** 完整构造（单测可注入执行器工厂，按当前镜像现建，用户改镜像设置即时生效）。 */
    public SandboxShellTool(AbstractSandboxFilesystem localFilesystem, Path workspace, Path project,
                            BooleanSupplier autoApprove, BooleanSupplier dockerEnabled,
                            Supplier<String> imageSupplier, DockerAvailabilityProbe probe,
                            Function<String, SandboxExecutor> executorByImage) {
        this.localDelegate = new ShellExecuteTool(Objects.requireNonNull(localFilesystem, "localFilesystem"));
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.project = project;
        this.autoApprove = autoApprove == null ? () -> false : autoApprove;
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
                            description = "Timeout in seconds (default: 30)",
                            required = false)
                    Integer timeout) {
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
        if (!dockerModeEnabled()) {
            return localDelegate.execute(runtimeContext, command, workingDirectory, timeout);
        }
        int timeoutSeconds = timeout != null && timeout > 0 ? timeout : DEFAULT_TIMEOUT_SECONDS;
        try {
            if (!probe.available()) {
                // 已开启隔离但环境不可用：按既有本地能力继续，并每会话提示一次回退
                return fallbackNotice(runtimeContext)
                        + localDelegate.execute(runtimeContext, command, workingDirectory, timeout);
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
                    + localDelegate.execute(runtimeContext, command, workingDirectory, timeout);
        }
    }

    // ===================== 内部流程 =====================

    /** 是否走容器档：本轮自动通过（黑名单仍由权限链拦截）且用户开启了 Docker 隔离。 */
    private boolean dockerModeEnabled() {
        try {
            return autoApprove.getAsBoolean() && dockerEnabled.getAsBoolean();
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
