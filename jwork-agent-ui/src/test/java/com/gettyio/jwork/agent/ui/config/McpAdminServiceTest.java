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

import com.gettyio.jwork.agent.core.capability.CapabilityCodec;
import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.entity.McpServerConfig;
import com.gettyio.jwork.agent.domain.persist.SqliteConnectionProvider;
import com.gettyio.jwork.agent.domain.persist.SqlSchemaInitializer;
import com.gettyio.jwork.agent.domain.security.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpAdminService} 「测试连接」支撑方法单测：探测状态回写与智能体运行时刷新委托。
 * 全部落临时 SQLite（与 SecuritySandboxConfigTest 同模式）；agent 表为空时
 * {@link AgentAdminService} 不会触达网关，故网关位可传 null。
 * <p>密钥加解密用可逆替身 cipher（前缀 t: + Base64），不触真实密钥文件。</p>
 *
 * @author gogym
 */
class McpAdminServiceTest {

    @TempDir
    Path tempDir;

    private JworkDatabase db;
    private McpAdminService service;

    /** 可逆替身 cipher：前缀 t: + Base64 */
    private static final class StandInCipher implements SecretCipher {

        @Override
        public String encrypt(String plainText) {
            return prefix() + Base64.getEncoder().encodeToString(plainText.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String decrypt(String cipherText) {
            return new String(Base64.getDecoder().decode(cipherText.substring(prefix().length())),
                    StandardCharsets.UTF_8);
        }

        @Override
        public String prefix() {
            return "t:";
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(tempDir.resolve("jwork.db"));
        new SqlSchemaInitializer(cp).initialize();
        db = new JworkDatabase(cp);
        service = new McpAdminService(db, new AgentAdminService(db, null), new StandInCipher());
    }

    private McpServerConfig insertRow(String code) {
        McpServerConfig m = new McpServerConfig();
        m.setServerCode(code);
        m.setServerName("探针测试");
        m.setTransportType("STDIO");
        m.setStatus("UNKNOWN");
        db.mcpServerDao().insert(m);
        return m;
    }

    @Test
    @DisplayName("recordStatus：状态回写可持久化回读，同值重复探测不产生多余更新")
    void recordStatusRoundTrips() throws Exception {
        insertRow("m1");

        service.recordStatus("m1", true);
        McpServerConfig afterUp = db.mcpServerDao().findByCode("m1").orElseThrow();
        assertEquals("CONNECTED", afterUp.getStatus());
        LocalDateTime stamp = afterUp.getUpdatedAt();

        // 同值重复探测：状态保持且未触发 UPDATE（updatedAt 不变）
        Thread.sleep(20);
        service.recordStatus("m1", true);
        McpServerConfig afterRepeat = db.mcpServerDao().findByCode("m1").orElseThrow();
        assertEquals("CONNECTED", afterRepeat.getStatus());
        assertEquals(stamp, afterRepeat.getUpdatedAt(), "同值探测不应刷新 updated_at");

        service.recordStatus("m1", false);
        assertEquals("DISCONNECTED", db.mcpServerDao().findByCode("m1").orElseThrow().getStatus());
    }

    @Test
    @DisplayName("recordStatus：未知 serverCode 静默忽略，不抛异常")
    void recordStatusMissingCodeIsNoOp() {
        assertDoesNotThrow(() -> service.recordStatus("m-missing", true));
    }

    @Test
    @DisplayName("refreshAgentRuntimes：无智能体引用时安全空转")
    void refreshAgentRuntimesSafeWithoutReferences() {
        insertRow("m2");
        assertDoesNotThrow(() -> service.refreshAgentRuntimes("m2"));
        assertTrue(db.mcpServerDao().findByCode("m2").isPresent());
    }

    /** 落一条带明文密钥映射的 HTTP 服务（save 入口约定收明文）。 */
    private McpServerConfig saveWithSecrets(String code) {
        McpServerConfig m = new McpServerConfig();
        m.setServerCode(code);
        m.setServerName("联网搜索");
        m.setTransportType("HTTP");
        m.setBaseUrl("https://example.com/mcp");
        m.setEnvJson(CapabilityCodec.writeStringMap(Map.of("API_KEY", "sk-9")));
        m.setHeadersJson(CapabilityCodec.writeStringMap(Map.of("Authorization", "Bearer sk-9")));
        service.save(m);
        return db.mcpServerDao().findByCode(code).orElseThrow();
    }

    @Test
    @DisplayName("save：密钥值加密入库；envOf/headersOf 解密回明文供表单回填")
    void saveEncryptsAndHelpersDecrypt() {
        McpServerConfig raw = saveWithSecrets("k1");
        assertTrue(CapabilityCodec.readStringMap(raw.getHeadersJson()).get("Authorization").startsWith("t:"),
                "库中不应留明文密钥: " + raw.getHeadersJson());
        assertTrue(CapabilityCodec.readStringMap(raw.getEnvJson()).get("API_KEY").startsWith("t:"));
        assertEquals(Map.of("API_KEY", "sk-9"), service.envOf(raw));
        assertEquals(Map.of("Authorization", "Bearer sk-9"), service.headersOf(raw));
    }

    @Test
    @DisplayName("save：库中读出的行重复保存不二次加密（幂等）")
    void saveTwiceDoesNotDoubleEncrypt() {
        McpServerConfig raw = saveWithSecrets("k2");
        String before = raw.getHeadersJson();
        service.save(raw);
        assertEquals(before, db.mcpServerDao().findByCode("k2").orElseThrow().getHeadersJson());
    }

    @Test
    @DisplayName("setEnabled：只改启禁位，密文原样保留且仍可解密")
    void setEnabledPreservesCipher() {
        McpServerConfig raw = saveWithSecrets("k3");
        String before = raw.getHeadersJson();
        service.setEnabled("k3", false);
        McpServerConfig after = db.mcpServerDao().findByCode("k3").orElseThrow();
        assertEquals(0, after.getEnabled());
        assertEquals(before, after.getHeadersJson());
        assertEquals(Map.of("Authorization", "Bearer sk-9"), service.headersOf(after));
    }
}
