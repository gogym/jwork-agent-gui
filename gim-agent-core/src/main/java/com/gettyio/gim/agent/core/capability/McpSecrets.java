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

import com.gettyio.gim.agent.domain.security.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 环境变量 / 请求头的密钥编解码：映射整体仍是 JSON 对象（键名明文可见），
 * <b>值逐个加密</b>后入库（与提供商 API Key 同用 {@link SecretCipher}），读取时逐个解密。
 * <p>口径：</p>
 * <ul>
 *   <li>空值不加密（市场模板的占位空串保持空串，便于表单识别「待补 Key」）；</li>
 *   <li>已是密文的值不再二次加密——{@code save} 对库中读出的行重复保存也幂等；</li>
 *   <li>解密失败（换机器 / 换 Windows 账户导致本机解不开）只告警并保留原值：
 *       与 {@link CapabilityCodec} 的 fail-soft 同口径，不让一条坏密钥把装配或表单炸掉，
 *       连接失败会由「测试连接」与运行时状态如实呈现。</li>
 * </ul>
 * <p>{@code cipher} 为 null 表示按明文读写（单测与无密钥场景）。</p>
 *
 * @author gogym
 */
public final class McpSecrets {

    private static final Logger log = LoggerFactory.getLogger(McpSecrets.class);

    private McpSecrets() {
    }

    /** 读环境变量 / 请求头映射：密文值就地解密；cipher 为 null 时原样返回。 */
    public static Map<String, String> readMap(String json, SecretCipher cipher) {
        Map<String, String> raw = CapabilityCodec.readStringMap(json);
        if (cipher == null || raw.isEmpty()) {
            return raw;
        }
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((name, value) -> out.put(name, decryptValue(name, value, cipher)));
        return out;
    }

    /** 写环境变量 / 请求头映射：非空值逐个加密；空值与已密文值原样保留（重复保存幂等）。 */
    public static String writeMap(Map<String, String> plain, SecretCipher cipher) {
        Map<String, String> raw = plain == null ? Map.of() : plain;
        if (cipher == null) {
            return CapabilityCodec.writeStringMap(raw);
        }
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((name, value) -> out.put(name,
                value == null || value.isBlank() || cipher.canHandle(value) ? value : cipher.encrypt(value)));
        return CapabilityCodec.writeStringMap(out);
    }

    private static String decryptValue(String name, String value, SecretCipher cipher) {
        if (value == null || value.isBlank() || !cipher.canHandle(value)) {
            return value;
        }
        try {
            return cipher.decrypt(value);
        } catch (RuntimeException e) {
            log.warn("MCP 密钥解密失败（换机器或换账户？），保留原值: {}", name, e);
            return value;
        }
    }
}