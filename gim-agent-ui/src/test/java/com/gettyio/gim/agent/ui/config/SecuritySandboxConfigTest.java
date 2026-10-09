package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import com.gettyio.gim.agent.domain.persist.SqlSchemaInitializer;
import com.gettyio.gim.agent.domain.security.AesGcmSecretCipher;
import com.gettyio.gim.agent.tools.sandbox.SandboxExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecurityAdminService} 的 Docker 沙箱配置单测：开关默认值/持久化回读、
 * 镜像默认回退与空白恢复默认，全部落临时 SQLite，无 Docker 依赖。
 */
class SecuritySandboxConfigTest {

    @TempDir
    Path tempDir;

    private GimDatabase db;
    private SecurityAdminService service;

    @BeforeEach
    void setUp() throws Exception {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(tempDir.resolve("gim.db"));
        new SqlSchemaInitializer(cp).initialize();
        db = new GimDatabase(cp);
        service = new SecurityAdminService(db, new AesGcmSecretCipher(tempDir.resolve("secret.key")));
    }

    @Test
    @DisplayName("沙箱开关：缺省关闭，置位后可跨实例持久化回读")
    void dockerToggleRoundTrip() {
        assertFalse(service.isSandboxDockerEnabled(), "缺省应为关闭");

        service.setSandboxDockerEnabled(true);
        assertTrue(service.isSandboxDockerEnabled());
        assertTrue(new SecurityAdminService(db, new AesGcmSecretCipher(tempDir.resolve("secret.key")))
                .isSandboxDockerEnabled(), "新实例应读回同一持久化值");

        service.setSandboxDockerEnabled(false);
        assertFalse(service.isSandboxDockerEnabled());
    }

    @Test
    @DisplayName("沙箱镜像：缺省回退内置默认，自定义值裁剪保存，空白恢复默认")
    void imageRoundTrip() {
        assertEquals(SandboxExecutor.DEFAULT_IMAGE, service.getSandboxImage(), "未设置应回退默认镜像");
        assertEquals(SecurityAdminService.SANDBOX_IMAGE_DEFAULT, SandboxExecutor.DEFAULT_IMAGE,
                "设置页默认值应与执行器单一来源一致");

        service.setSandboxImage("  ubuntu:24.04  ");
        assertEquals("ubuntu:24.04", service.getSandboxImage(), "自定义镜像应裁剪首尾空白保存");

        service.setSandboxImage("   ");
        assertEquals(SandboxExecutor.DEFAULT_IMAGE, service.getSandboxImage(), "纯空白应恢复默认镜像");

        service.setSandboxImage(null);
        assertEquals(SandboxExecutor.DEFAULT_IMAGE, service.getSandboxImage(), "null 应恢复默认镜像");
    }
}
