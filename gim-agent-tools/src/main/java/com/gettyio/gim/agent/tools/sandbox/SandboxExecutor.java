package com.gettyio.gim.agent.tools.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 沙箱真实执行器：Docker 可用时经 docker CLI 拉起一次性容器执行命令
 * （{@code --rm} + 宿主目录按 {@link Mount} 挂载进容器 + 内存/CPU 限制 + 调用方超时），
 * 输出回传给调用方；Docker 不可用时无缝回退为本地进程直执（宿主档），保证功能始终可用。
 * <p>与 {@link SandboxProvisioner} 的分工：Provisioner 决定「副作用落在哪」（文件系统落地规格），
 * 本类决定「命令在哪跑」（执行原语）。工程不引入 Docker SDK，统一走 CLI（与
 * {@link DockerAvailabilityProbe} 的零依赖口径一致）；{@link ProcessStarter} 可注入以便单测打桩。</p>
 */
public class SandboxExecutor {

    private static final Logger log = LoggerFactory.getLogger(SandboxExecutor.class);

    /** 预拉取的轻量镜像：覆盖最常见的脚本执行场景，拉取失败不影响功能。 */
    public static final String DEFAULT_IMAGE = "python:3.12-slim";
    /** 容器内存上限（MB）。 */
    public static final long DEFAULT_MEMORY_MB = 512;
    /** 容器 CPU 配额（核）。 */
    public static final double DEFAULT_CPUS = 1.0;
    /** 默认命令超时。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    /** 超时杀进程后的退出码约定（本地与容器档一致）。 */
    public static final int EXIT_TIMEOUT = -1;

    /** 一次执行的结果：退出码、标准/错误输出、是否真实容器隔离、耗时（毫秒）。 */
    public record ExecResult(int exitCode, String stdout, String stderr, boolean sandboxed, long elapsedMillis) {
    }

    /** 容器挂载点：宿主目录 → 容器内路径；{@code readOnly} 为真时以只读挂载（追加 {@code :ro}）。 */
    public record Mount(Path hostPath, String containerPath, boolean readOnly) {
    }

    /** 进程启动器抽象：默认 ProcessBuilder，测试注入桩进程。 */
    @FunctionalInterface
    public interface ProcessStarter {
        Process start(List<String> command, Path workDir) throws IOException;
    }

    private final DockerAvailabilityProbe probe;
    private final String image;
    private final ProcessStarter starter;

    public SandboxExecutor() {
        this(DockerAvailabilityProbe.onPath(), DEFAULT_IMAGE);
    }

    public SandboxExecutor(DockerAvailabilityProbe probe, String image) {
        this(probe, image, (cmd, dir) -> new ProcessBuilder(cmd)
                .directory(dir == null ? null : dir.toFile())
                .start());
    }

    public SandboxExecutor(DockerAvailabilityProbe probe, String image, ProcessStarter starter) {
        this.probe = probe == null ? DockerAvailabilityProbe.onPath() : probe;
        this.image = image == null || image.isBlank() ? DEFAULT_IMAGE : image;
        this.starter = Objects.requireNonNull(starter, "starter");
    }

    /** Docker 执行档是否可用（探测异常按不可用收敛，绝不外抛）。 */
    public boolean dockerAvailable() {
        try {
            return probe.available();
        } catch (RuntimeException e) {
            log.warn("Docker 可用性探测异常，按不可用处理", e);
            return false;
        }
    }

    /** 探测可用时预拉取轻量镜像；任何失败只记日志，绝不打断调用方（启动期后台调用）。 */
    public void prePullImage() {
        if (!dockerAvailable()) {
            return;
        }
        try {
            Process p = starter.start(List.of("docker", "pull", image), null);
            drain(p.getInputStream());
            drain(p.getErrorStream());
            int code = p.waitFor();
            if (code == 0) {
                log.info("沙箱镜像已就绪: {}", image);
            } else {
                log.warn("沙箱镜像预拉取失败（exit={}），首次执行容器时再拉取: {}", code, image);
            }
        } catch (Exception e) {
            log.warn("沙箱镜像预拉取异常，忽略: {}", e.getMessage());
        }
    }

    /**
     * 执行一条命令：Docker 可用且给定 workspace 时以一次性容器执行（挂载到 {@code /workspace}
     * 并以其为工作目录），否则回退本地进程直执。语义等价于
     * {@link #exec(List, List, String, Path, Duration, long, double)} 的单挂载特例，保留旧调用方兼容。
     */
    public ExecResult exec(List<String> command, Path workspace, Duration timeout, long memoryMB, double cpus)
            throws IOException, InterruptedException {
        List<Mount> mounts = workspace == null
                ? List.of()
                : List.of(new Mount(workspace, "/workspace", false));
        return exec(command, mounts, "/workspace", workspace, timeout, memoryMB, cpus);
    }

    /**
     * 执行一条命令（泛化挂载）：Docker 可用且有挂载点时为一次性容器（按 {@code mounts} 逐一
     * {@code -v}，容器工作目录取 {@code containerWorkDir}、缺省 {@code /workspace}，
     * 施加内存/CPU 限制），否则回退本地进程直执并取 {@code localWorkDir} 为进程目录。
     *
     * @param command         命令与参数（不经 shell 拼接，避免注入）
     * @param mounts          容器挂载点列表（本地档不生效；空列表即回退本地）
     * @param containerWorkDir 容器内工作目录（{@code null}/空白取 {@code /workspace}）
     * @param localWorkDir    本地回退档的进程目录（可为 {@code null}）
     * @param timeout         超时（超时强杀进程，退出码 {@link #EXIT_TIMEOUT}）
     * @param memoryMB        内存上限（MB），仅容器档生效；{@code <=0} 取 {@link #DEFAULT_MEMORY_MB}
     * @param cpus            CPU 配额（核），仅容器档生效；{@code <=0} 取 {@link #DEFAULT_CPUS}
     * @return 执行结果（含 stdout/stderr 全量回传）
     */
    public ExecResult exec(List<String> command, List<Mount> mounts, String containerWorkDir,
                           Path localWorkDir, Duration timeout, long memoryMB, double cpus)
            throws IOException, InterruptedException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        Duration effectiveTimeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? DEFAULT_TIMEOUT : timeout;
        boolean sandboxed = dockerAvailable() && mounts != null && !mounts.isEmpty();

        List<String> full;
        Path workDir;
        if (sandboxed) {
            full = new ArrayList<>();
            full.add("docker");
            full.add("run");
            full.add("--rm");
            for (Mount mount : mounts) {
                if (mount == null || mount.hostPath() == null
                        || mount.containerPath() == null || mount.containerPath().isBlank()) {
                    continue;
                }
                full.add("-v");
                full.add(mountSpec(mount));
            }
            full.add("-w");
            full.add(containerWorkDir == null || containerWorkDir.isBlank()
                    ? "/workspace" : containerWorkDir.strip());
            full.add("--memory");
            full.add((memoryMB <= 0 ? DEFAULT_MEMORY_MB : memoryMB) + "m");
            full.add("--cpus");
            full.add(String.valueOf(cpus <= 0 ? DEFAULT_CPUS : cpus));
            full.add(image);
            full.addAll(command);
            workDir = null;
            log.info("沙箱容器执行: {}（挂载={}，容器目录={}，memory={}m，cpus={}）",
                    command.get(0), mounts, containerWorkDir, memoryMB, cpus);
        } else {
            full = List.copyOf(command);
            workDir = localWorkDir;
        }

        long t0 = System.currentTimeMillis();
        Process p = starter.start(full, workDir);
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread tout = drainAsync(p.getInputStream(), out);
        Thread terr = drainAsync(p.getErrorStream(), err);

        boolean finished = p.waitFor(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
        int exit;
        if (!finished) {
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
            err.append(err.isEmpty() ? "" : System.lineSeparator())
                    .append("[jwork] 命令超时被终止（").append(effectiveTimeout.toSeconds()).append("s）");
            exit = EXIT_TIMEOUT;
        } else {
            exit = p.exitValue();
        }
        tout.join(5_000);
        terr.join(5_000);
        return new ExecResult(exit, out.toString(), err.toString(), sandboxed,
                System.currentTimeMillis() - t0);
    }

    // ---- 内部工具 ----

    /** 挂载规格：宿主路径统一正斜杠（避免 Windows 盘符歧义），只读挂载追加 {@code :ro}。 */
    private static String mountSpec(Mount mount) {
        String spec = mount.hostPath().toAbsolutePath().toString().replace('\\', '/')
                + ":" + mount.containerPath();
        return mount.readOnly() ? spec + ":ro" : spec;
    }

    private static void drain(InputStream in) throws IOException {
        in.readAllBytes();
    }

    private static Thread drainAsync(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (InputStream is = in) {
                sink.append(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // 进程被强杀时流读异常属正常，交由退出码与超时标记表达
            }
        }, "sandbox-exec-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
