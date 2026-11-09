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

package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import com.gettyio.gim.agent.domain.persist.SqlSchemaInitializer;
import com.gettyio.gim.agent.domain.security.AesGcmSecretCipher;
import com.gettyio.gim.agent.domain.security.SecretStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecurityAdminService} 的密钥项单测：密文落库、留空不覆盖、显式清除、外机密文不外泄、
 * 联网搜索 Key 按提供方隔离。全部落临时 SQLite 与固定 AES 密钥文件，不依赖 Windows DPAPI。
 *
 * @author gogym
 */
class SecurityAdminSecretsTest {

    /** 与 SecurityAdminService 私有常量对应的 settings 键，用于直接注入脏数据。 */
    private static final String KEY_IMG = "image.generation.apiKey";
    private static final String KEY_KB = "knowledge.embedding.apiKey";
    private static final String KEY_WEB_TAVILY = "web.search.apiKey.tavily";

    @TempDir
    Path tempDir;

    private GimDatabase db;
    private SecurityAdminService service;

    @BeforeEach
    void setUp() throws Exception {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(tempDir.resolve("gim.db"));
        new SqlSchemaInitializer(cp).initialize();
        db = new GimDatabase(cp);
        Path keyFile = tempDir.resolve("secret.key");
        service = new SecurityAdminService(db, new SecretStore(new AesGcmSecretCipher(keyFile), keyFile));
    }

    @Test
    @DisplayName("图像 Key：去空白后加密入库，读取回明文")
    void keyStoredEncrypted() {
        assertTrue(service.setImageApiKey("  sk-img-1  "), "非空白应写入");
        assertEquals("sk-img-1", service.getImageApiKeyPlain());

        String raw = db.settingDao().getValue(KEY_IMG).orElseThrow();
        assertTrue(raw.startsWith(AesGcmSecretCipher.PREFIX), "入库应为密文，实际为 " + raw);
        assertEquals(SecretStore.State.READABLE, service.getImageApiKeyState());
    }

    @Test
    @DisplayName("留空表示不修改：一次瞬时解密失败加失焦不该抹掉已配好的 Key")
    void blankKeepsStoredKey() {
        service.setImageApiKey("sk-img-1");

        assertFalse(service.setImageApiKey("   "), "空白不应落库");
        assertFalse(service.setImageApiKey(null), "null 不应落库");
        assertEquals("sk-img-1", service.getImageApiKeyPlain(), "留空保存后原 Key 应完好");

        service.clearImageApiKey();
        assertEquals("", service.getImageApiKeyPlain());
        assertEquals(SecretStore.State.EMPTY, service.getImageApiKeyState(), "撤销只走显式清除");
    }

    @Test
    @DisplayName("外机密文：读回空串并标记解不开，绝不把密文体当 Key 交出")
    void foreignSealedKeyIsNotLeaked() {
        db.settingDao().upsert(KEY_KB, "dpapi:bm90LW9uLXRoaXMtbWFjaGluZQ==", "脏数据");

        assertEquals("", service.getKnowledgeEmbeddingKeyPlain());
        assertEquals(SecretStore.State.UNREADABLE, service.getKnowledgeEmbeddingKeyState());

        // 解不开时用户重新录入即恢复；未录入前留空仍保留原（不可读）值，不静默清空
        assertTrue(service.setKnowledgeEmbeddingKey("  sk-kb-2  "));
        assertEquals("sk-kb-2", service.getKnowledgeEmbeddingKeyPlain());
        assertEquals(SecretStore.State.READABLE, service.getKnowledgeEmbeddingKeyState());
    }

    @Test
    @DisplayName("历史明文 Key 仍可用，状态标为明文遗留")
    void legacyPlainKeyStillUsable() {
        db.settingDao().upsert(KEY_IMG, "sk-hand-written", "手工写入");

        assertEquals("sk-hand-written", service.getImageApiKeyPlain());
        assertEquals(SecretStore.State.LEGACY_PLAIN, service.getImageApiKeyState());

        service.setImageApiKey("sk-hand-written");
        assertTrue(db.settingDao().getValue(KEY_IMG).orElseThrow().startsWith(AesGcmSecretCipher.PREFIX),
                "重新保存应自动改为密文");
    }

    @Test
    @DisplayName("联网搜索 Key 按提供方隔离：切走再切回各自独立")
    void webKeyIsScopedByProvider() {
        service.setWebSearchProvider(SecurityAdminService.WEB_PROVIDER_TAVILY);
        service.setWebSearchApiKey("tv-key");

        service.setWebSearchProvider(SecurityAdminService.WEB_PROVIDER_BOCHA);
        assertEquals("", service.getWebSearchApiKeyPlain(), "博查应看不到 Tavily 的 Key");
        assertEquals(SecretStore.State.EMPTY, service.getWebSearchApiKeyState());

        service.setWebSearchApiKey("bc-key");
        assertEquals("bc-key", service.getWebSearchApiKeyPlain());

        service.setWebSearchProvider(SecurityAdminService.WEB_PROVIDER_TAVILY);
        assertEquals("tv-key", service.getWebSearchApiKeyPlain(), "切回后原 Key 应完好");
        assertTrue(db.settingDao().getValue(KEY_WEB_TAVILY).orElseThrow().startsWith(AesGcmSecretCipher.PREFIX));

        String bochaRaw = db.settingDao().getValue("web.search.apiKey.bocha").orElseThrow();
        service.clearWebSearchApiKey();
        assertEquals("", service.getWebSearchApiKeyPlain());
        assertEquals(bochaRaw, db.settingDao().getValue("web.search.apiKey.bocha").orElseThrow(),
                "清除当前提供方不该动其他提供方的 Key");
    }
}
