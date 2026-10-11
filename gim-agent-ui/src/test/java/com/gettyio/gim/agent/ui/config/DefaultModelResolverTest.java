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

import com.gettyio.gim.agent.core.model.ModelSpec;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.entity.ModelConfig;
import com.gettyio.gim.agent.domain.entity.ModelProviderConfig;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import com.gettyio.gim.agent.domain.persist.SqlSchemaInitializer;
import com.gettyio.gim.agent.domain.security.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link DefaultModelResolver#resolveFallback} 单测：备用模型选取，以及「消息级显式指定模型即不降级」约束。
 * 全部落临时 SQLite（与 McpAdminServiceTest 同模式）。
 *
 * @author gogym
 */
class DefaultModelResolverTest {

    @TempDir
    Path tempDir;

    private DefaultModelResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(tempDir.resolve("gim.db"));
        new SqlSchemaInitializer(cp).initialize();
        GimDatabase db = new GimDatabase(cp);

        ModelProviderConfig provider = new ModelProviderConfig();
        provider.setProviderCode("p1");
        provider.setProviderName("测试提供商");
        provider.setApiProtocol("OPENAI_COMPATIBLE");
        provider.setBaseUrl("https://example.invalid/v1");
        provider.setApiKey("plain:sk-test");
        db.modelProviderDao().insert(provider);

        // 主模型 m1（应用默认）；m3 优先级最高但停用——备选取用应为 m2（跳过停用项与主模型自身）
        insertModel(db, "m1", "model-one", 10, 1);
        insertModel(db, "m2", "model-two", 20, 1);
        insertModel(db, "m3", "model-three", 5, 0);

        db.settingDao().upsert(DefaultModelResolver.KEY_DEFAULT_PROVIDER, "p1", "test");
        db.settingDao().upsert(DefaultModelResolver.KEY_DEFAULT_MODEL, "m1", "test");

        resolver = new DefaultModelResolver(db, new SecretCipher() {
            @Override
            public String encrypt(String plainText) {
                return "plain:" + plainText;
            }

            @Override
            public String decrypt(String cipherText) {
                return cipherText.substring("plain:".length());
            }

            @Override
            public String prefix() {
                return "plain:";
            }
        });
    }

    private static void insertModel(GimDatabase db, String code, String name, int priority, int enabled) {
        ModelConfig m = new ModelConfig();
        m.setProviderCode("p1");
        m.setModelCode(code);
        m.setModelName(name);
        m.setPriority(priority);
        m.setEnabled(enabled);
        db.modelConfigDao().insert(m);
    }

    @Test
    @DisplayName("消息级显式指定模型：即使存在可用替代也不提供备用（失败直接报错，不自动降级）")
    void explicitSelectionDisablesFallback() {
        assertNull(resolver.resolveFallback(new AgentDefinition(), "p1", "m2"),
                "会话里已选定模型时不应再解析备用模型");
    }

    @Test
    @DisplayName("未指定模型（null/空白）：取同提供商下一个启用模型，跳过主模型与停用模型")
    void noSelectionResolvesNextEnabledModel() {
        ModelSpec fallback = resolver.resolveFallback(new AgentDefinition(), null, null);
        assertNotNull(fallback);
        assertEquals("p1", fallback.providerCode());
        assertEquals("m2", fallback.modelCode(), "应取 priority 升序的下一个启用模型（m3 停用、m1 为主模型）");
        assertEquals("model-two", fallback.modelName());

        // 空白串与 null 同义：都表示「未指定」
        ModelSpec blank = resolver.resolveFallback(new AgentDefinition(), "  ", "");
        assertNotNull(blank);
        assertEquals("m2", blank.modelCode());
    }
}
