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

package com.gettyio.gim.agent.ui.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ModelProviderConfig;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import com.gettyio.gim.agent.domain.persist.SqlSchemaInitializer;
import com.gettyio.gim.agent.domain.security.AesGcmSecretCipher;
import com.gettyio.gim.agent.domain.security.SecretStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExportService} 全库 JSON 导出的密钥脱敏单测：密文设置、键名像密钥的历史明文、
 * 提供商 API Key 各走哪条脱敏路，普通设置项与其余表须原样导出。真实 SQLite，无外部依赖。
 *
 * @author gogym
 */
class ExportServiceRedactionTest {

    @TempDir
    Path tempDir;

    private GimDatabase db;
    private SecretStore secrets;
    private ExportService service;

    @BeforeEach
    void setUp() {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(tempDir.resolve("gim.db"));
        new SqlSchemaInitializer(cp).initialize();
        db = new GimDatabase(cp);
        Path keyFile = tempDir.resolve("secret.key");
        secrets = new SecretStore(new AesGcmSecretCipher(keyFile), keyFile);
        service = new ExportService(db, secrets);
    }

    /** 导出全库 JSON 并解析（导出件是给人贴来贴去的，形状变了这里就该响）。 */
    private JsonNode exportAndParse() throws Exception {
        Path target = tempDir.resolve("out/export.json");
        service.exportFullJson(target);
        return new ObjectMapper().readTree(target.toFile());
    }

    /** 按 settingKey 找导出件里的设置行；缺失即返回 null 让断言直接失败在「没找到」上。 */
    private static JsonNode findSetting(JsonNode root, String key) {
        for (JsonNode s : root.get("settings")) {
            if (key.equals(s.get("settingKey").asText())) {
                return s;
            }
        }
        return null;
    }

    @Test
    @DisplayName("密文设置值被抹掉并登记：导出件带着密文等于连着 secret.key 一起送人")
    void sealsCipherValuesOutOfExport() throws Exception {
        db.settingDao().upsert("sync.webdav.password", secrets.seal("s3cret"), "WebDAV 同步密码（加密）");
        db.settingDao().upsert("ui.theme", "dark", "界面主题");

        JsonNode root = exportAndParse();
        List<String> redactedKeys = new ArrayList<>();
        root.get("redactedSecrets").get("settings").forEach(n -> redactedKeys.add(n.asText()));
        assertTrue(redactedKeys.contains("sync.webdav.password"), redactedKeys.toString());

        assertEquals(ExportService.REDACTED, findSetting(root, "sync.webdav.password").get("settingValue").asText());
        assertEquals("dark", findSetting(root, "ui.theme").get("settingValue").asText(), "非密钥设置须原样导出");
        assertFalse(root.toString().contains("s3cret"), "明文与密文都不该出现在导出件里");
    }

    @Test
    @DisplayName("键名像密钥的历史明文照样抹：点号与下划线两种写法都要认出")
    void redactsLegacyPlainSecrets() throws Exception {
        db.settingDao().upsert("web.search.api.key", "sk-legacy-plain", "历史明文密钥（点号写法）");
        db.settingDao().upsert("net.proxy.api_key", "legacy-underscore", "历史明文密钥（下划线写法）");
        db.settingDao().upsert("ui.hotkey.mode", "global", "非密钥项，须原样导出");

        JsonNode root = exportAndParse();

        assertEquals(ExportService.REDACTED, findSetting(root, "web.search.api.key").get("settingValue").asText());
        assertEquals(ExportService.REDACTED, findSetting(root, "net.proxy.api_key").get("settingValue").asText());
        assertEquals("global", findSetting(root, "ui.hotkey.mode").get("settingValue").asText());
        assertFalse(root.toString().contains("sk-legacy-plain"));
        assertFalse(root.toString().contains("legacy-underscore"));
    }

    @Test
    @DisplayName("提供商 API Key 一律抹掉并按 providerCode 登记")
    void redactsProviderApiKeys() throws Exception {
        ModelProviderConfig p = new ModelProviderConfig();
        p.setProviderCode("dashscope");
        p.setProviderName("DashScope");
        p.setApiProtocol("openai");
        p.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        p.setApiKey(secrets.seal("sk-real-key"));
        p.setEnabled(1);
        db.modelProviderDao().insert(p);

        JsonNode root = exportAndParse();
        JsonNode provider = null;
        for (JsonNode n : root.get("modelProviders")) {
            if ("dashscope".equals(n.get("providerCode").asText())) {
                provider = n;
            }
        }
        assertTrue(provider != null, "导出件里没有该提供商");

        assertEquals(ExportService.REDACTED, provider.get("apiKey").asText());
        List<String> codes = new ArrayList<>();
        root.get("redactedSecrets").get("modelProviders").forEach(n -> codes.add(n.asText()));
        assertTrue(codes.contains("dashscope"), codes.toString());
        assertFalse(root.toString().contains("sk-real-key"));
    }

    @Test
    @DisplayName("没有提供商密钥时 redactedSecrets 两段仍是数组：形状稳定，读导出件的一方不必猜")
    void emitsStableRedactionSection() throws Exception {
        JsonNode root = exportAndParse();

        assertTrue(root.get("redactedSecrets").get("settings").isArray());
        assertTrue(root.get("redactedSecrets").get("modelProviders").isArray());
        assertEquals(0, root.get("redactedSecrets").get("modelProviders").size(), "未建提供商不该登记抹除项");
    }
}
