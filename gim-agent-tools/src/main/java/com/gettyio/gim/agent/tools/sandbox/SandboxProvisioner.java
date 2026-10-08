package com.gettyio.gim.agent.tools.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 自动通过档的 Sandbox 供给器：在「用户为自动通过档开启了 Docker 文件隔离」的意图下，
 * 经 {@link DockerAvailabilityProbe} 探测环境，产出本次落地的 {@link SandboxFilesystemSpec}。
 * <p>骨架实现不拉起真实容器：可用时给出容器工作目录、不可用时优雅回退本地工作目录并附一句提示，
 * 全过程不向调用方抛出异常——探测或编排的任何失败都收敛为本地回退，保证自动通过档始终可继续。</p>
 */
public class SandboxProvisioner {

    private static final Logger log = LoggerFactory.getLogger(SandboxProvisioner.class);

    /** 回退提示的 i18n 键：Docker 隔离不可用、已回退本地落地，交由 UI 层按当前语言渲染。 */
    public static final String NOTICE_KEY_DOCKER_UNAVAILABLE = "sandbox.docker.unavailable";

    private final DockerAvailabilityProbe probe;

    public SandboxProvisioner() {
        this(DockerAvailabilityProbe.onPath());
    }

    public SandboxProvisioner(DockerAvailabilityProbe probe) {
        this.probe = probe == null ? DockerAvailabilityProbe.onPath() : probe;
    }

    /**
     * 为自动通过档规划文件系统落地根。
     *
     * @param sandboxDesired 用户是否为自动通过档开启了 Docker 文件隔离
     * @param dockerWorkspace 期望的容器内工作目录（{@code sandboxDesired} 为真且 Docker 可用时采用）
     * @param localWorkspace 本地回退工作目录（始终作为兜底，不可为 {@code null}）
     * @return 落地规格；隔离不可用时回退本地并带上 {@link #NOTICE_KEY_DOCKER_UNAVAILABLE} 提示
     */
    public SandboxFilesystemSpec provisionForAutoApprove(boolean sandboxDesired,
                                                         Path dockerWorkspace,
                                                         Path localWorkspace) {
        if (localWorkspace == null) {
            throw new IllegalArgumentException("localWorkspace must not be null");
        }
        if (!sandboxDesired) {
            // 未开启隔离：维持既有落地，落本地、不打扰用户（无提示）
            return SandboxFilesystemSpec.localFallback(localWorkspace, null);
        }
        boolean up;
        try {
            up = probe.available();
        } catch (RuntimeException e) {
            log.warn("Docker 可用性探测异常，按不可用处理并回退本地", e);
            up = false;
        }
        if (up && dockerWorkspace != null) {
            log.info("自动通过档启用 Docker 文件隔离，落地根：{}", dockerWorkspace);
            return SandboxFilesystemSpec.docker(dockerWorkspace);
        }
        log.info("自动通过档请求 Docker 隔离但环境不可用，优雅回退本地工作目录：{}", localWorkspace);
        return SandboxFilesystemSpec.localFallback(localWorkspace, NOTICE_KEY_DOCKER_UNAVAILABLE);
    }
}
