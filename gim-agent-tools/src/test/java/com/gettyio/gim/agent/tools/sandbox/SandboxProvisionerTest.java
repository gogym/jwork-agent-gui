package com.gettyio.gim.agent.tools.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SandboxProvisioner} 单测：自动通过档在「未开启隔离 / Docker 可用 / 不可用 / 探测抛异常」
 * 四条路径下的落地结论，重点验证依赖缺失时的优雅回退与提示，且全程不外抛。
 */
class SandboxProvisionerTest {

    private static final Path DOCKER_WS = Path.of("/jwork/sandbox/workspace");
    private static final Path LOCAL_WS = Path.of("/home/user/.jwork/workspace");

    @Test
    @DisplayName("未开启隔离：落本地、无提示，维持既有行为")
    void notDesiredLandsLocalSilently() {
        SandboxFilesystemSpec spec = new SandboxProvisioner(() -> true)
                .provisionForAutoApprove(false, DOCKER_WS, LOCAL_WS);
        assertEquals(SandboxMode.LOCAL_FALLBACK, spec.mode());
        assertEquals(LOCAL_WS, spec.root());
        assertFalse(spec.isolated());
        assertFalse(spec.shouldNotice());
        assertNull(spec.notice());
    }

    @Test
    @DisplayName("开启且 Docker 可用：隔离落容器工作目录，无提示")
    void availableLandsDocker() {
        SandboxFilesystemSpec spec = new SandboxProvisioner(() -> true)
                .provisionForAutoApprove(true, DOCKER_WS, LOCAL_WS);
        assertEquals(SandboxMode.DOCKER, spec.mode());
        assertEquals(DOCKER_WS, spec.root());
        assertTrue(spec.isolated());
        assertFalse(spec.shouldNotice());
    }

    @Test
    @DisplayName("开启但 Docker 不可用：优雅回退本地并带提示键")
    void unavailableFallsBackWithNotice() {
        SandboxFilesystemSpec spec = new SandboxProvisioner(() -> false)
                .provisionForAutoApprove(true, DOCKER_WS, LOCAL_WS);
        assertEquals(SandboxMode.LOCAL_FALLBACK, spec.mode());
        assertEquals(LOCAL_WS, spec.root());
        assertEquals(SandboxProvisioner.NOTICE_KEY_DOCKER_UNAVAILABLE, spec.notice());
        assertTrue(spec.shouldNotice());
    }

    @Test
    @DisplayName("探测抛异常：收敛为不可用回退，绝不向调用方外抛")
    void probeThrowingDegradesGracefully() {
        DockerAvailabilityProbe boom = () -> {
            throw new IllegalStateException("docker daemon down");
        };
        SandboxFilesystemSpec spec = new SandboxProvisioner(boom)
                .provisionForAutoApprove(true, DOCKER_WS, LOCAL_WS);
        assertEquals(SandboxMode.LOCAL_FALLBACK, spec.mode());
        assertEquals(SandboxProvisioner.NOTICE_KEY_DOCKER_UNAVAILABLE, spec.notice());
    }

    @Test
    @DisplayName("可用但缺容器工作目录：无处可落，回退本地并提示")
    void availableWithoutWorkspaceFallsBack() {
        SandboxFilesystemSpec spec = new SandboxProvisioner(() -> true)
                .provisionForAutoApprove(true, null, LOCAL_WS);
        assertEquals(SandboxMode.LOCAL_FALLBACK, spec.mode());
        assertTrue(spec.shouldNotice());
    }

    @Test
    @DisplayName("本地兜底目录缺失属非法入参：快速失败")
    void nullLocalWorkspaceRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new SandboxProvisioner(() -> false)
                        .provisionForAutoApprove(true, DOCKER_WS, null));
    }
}
