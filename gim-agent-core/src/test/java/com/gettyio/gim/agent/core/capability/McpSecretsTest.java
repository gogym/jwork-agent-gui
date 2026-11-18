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

package com.gettyio.gim.agent.core.capability;

import com.gettyio.gim.agent.domain.entity.McpServerConfig;
import com.gettyio.gim.agent.domain.security.SecretCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 密钥编解码口径：值逐个加密入库、读时解密；空值不加密；重复保存幂等；
 * 本机解不开只告警保留原值（fail-soft，与 CapabilityCodec 同口径）。
 *
 * @author gogym
 */
class McpSecretsTest {

    /** 可逆替身 cipher：前缀 t: + Base64，不触真实密钥文件 */
    private static final class StandInCipher implements SecretCipher {

        @Override
        public String encrypt(String plainText) {
            return prefix() + Base64.getEncoder().encodeToString(plainText.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String decrypt(String cipherText) {
            if (!canHandle(cipherText)) {
                throw new IllegalArgumentException("无法识别的密文前缀");
            }
            return new String(Base64.getDecoder().decode(cipherText.substring(prefix().length())),
                    StandardCharsets.UTF_8);
        }

        @Override
        public String prefix() {
            return "t:";
        }
    }

    private final SecretCipher cipher = new StandInCipher();

    private static Map<String, String> plain() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("Authorization", "Bearer sk-1");
        map.put("X-Empty", "");
        return map;
    }

    @Test
    @DisplayName("写加密读解密往返一致；空值保持空串不加密")
    void roundTripAndBlankStaysBlank() {
        String json = McpSecrets.writeMap(plain(), cipher);
        Map<String, String> raw = CapabilityCodec.readStringMap(json);
        assertTrue(raw.get("Authorization").startsWith("t:"), "非空值应入库为密文: " + json);
        assertEquals("", raw.get("X-Empty"), "空值不加密");
        assertEquals(plain(), McpSecrets.readMap(json, cipher));
    }

    @Test
    @DisplayName("cipher 为 null 按明文读写（单测与无密钥场景）")
    void nullCipherKeepsPlain() {
        String json = McpSecrets.writeMap(plain(), null);
        assertEquals(plain(), CapabilityCodec.readStringMap(json));
        assertEquals(plain(), McpSecrets.readMap(json, null));
    }

    @Test
    @DisplayName("已密文值不二次加密：库中读出的行重复保存幂等")
    void writeIsIdempotent() {
        String once = McpSecrets.writeMap(plain(), cipher);
        String twice = McpSecrets.writeMap(CapabilityCodec.readStringMap(once), cipher);
        assertEquals(once, twice);
    }

    @Test
    @DisplayName("本机解不开（换机器/换账户）：告警并保留原值，不炸装配与表单")
    void unreadableValueKeptRaw() {
        SecretCipher broken = new SecretCipher() {
            @Override
            public String encrypt(String plainText) {
                return "t:x";
            }

            @Override
            public String decrypt(String cipherText) {
                throw new IllegalStateException("密钥文件不匹配");
            }

            @Override
            public String prefix() {
                return "t:";
            }
        };
        String json = CapabilityCodec.writeStringMap(Map.of("K", "t:garbage"));
        assertEquals("t:garbage", McpSecrets.readMap(json, broken).get("K"));
    }

    @Test
    @DisplayName("配置翻译带 cipher 解密回明文；不带 cipher 按明文读（密文原样）")
    void fromDecryptsEnvAndHeaders() {
        McpServerConfig row = new McpServerConfig();
        row.setServerCode("s1");
        row.setTransportType("HTTP");
        row.setBaseUrl("https://example.com/mcp");
        row.setEnvJson(McpSecrets.writeMap(Map.of("API_KEY", "sk-9"), cipher));
        row.setHeadersJson(McpSecrets.writeMap(Map.of("Authorization", "Bearer sk-9"), cipher));

        io.agentscope.harness.agent.tools.McpServerConfig cfg = McpServerConfigs.from(row, cipher);
        assertEquals("sk-9", cfg.getEnv().get("API_KEY"));
        assertEquals("Bearer sk-9", cfg.getHeaders().get("Authorization"));

        io.agentscope.harness.agent.tools.McpServerConfig plainCfg = McpServerConfigs.from(row);
        assertTrue(plainCfg.getHeaders().get("Authorization").startsWith("t:"));
    }
}