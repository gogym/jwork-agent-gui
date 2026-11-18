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

package com.gettyio.jwork.agent.ui.config;

import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.persist.SqliteConnectionProvider;
import com.gettyio.jwork.agent.domain.persist.SqlSchemaInitializer;
import com.gettyio.jwork.agent.domain.security.AesGcmSecretCipher;
import com.gettyio.jwork.agent.domain.security.SecretStore;
import com.gettyio.jwork.agent.tools.sandbox.SandboxExecutor;
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
 *
 * @author gogym
 */
class SecuritySandboxConfigTest {

    @TempDir
    Path tempDir;

    private JworkDatabase db;
    private SecurityAdminService service;

    @BeforeEach
    void setUp() throws Exception {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(tempDir.resolve("jwork.db"));
        new SqlSchemaInitializer(cp).initialize();
        db = new JworkDatabase(cp);
        Path keyFile = tempDir.resolve("secret.key");
        service = new SecurityAdminService(db, new SecretStore(new AesGcmSecretCipher(keyFile), keyFile));
    }

    @Test
    @DisplayName("沙箱开关：缺省关闭，置位后可跨实例持久化回读")
    void dockerToggleRoundTrip() {
        assertFalse(service.isSandboxDockerEnabled(), "缺省应为关闭");

        service.setSandboxDockerEnabled(true);
        assertTrue(service.isSandboxDockerEnabled());
        Path keyFile = tempDir.resolve("secret.key");
        assertTrue(new SecurityAdminService(db, new SecretStore(new AesGcmSecretCipher(keyFile), keyFile))
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
