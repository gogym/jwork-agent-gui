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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProxyManager} 单测：设置往返与密码加密落库、留空沿用/显式清除两种语义、
 * 系统属性的应用与清理、端口解析边界。
 * 全部落临时 SQLite 与临时密钥文件（与 McpAdminServiceTest 同模式）；每个用例结束还原
 * 代理关闭态，避免系统属性污染同 JVM 的其他测试。
 *
 * @author gogym
 */
class ProxyManagerTest {

    @TempDir
    Path tempDir;

    private JworkDatabase db;
    private SecretStore secrets;
    private ProxyManager manager;

    @BeforeEach
    void setUp() throws Exception {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(tempDir.resolve("jwork.db"));
        new SqlSchemaInitializer(cp).initialize();
        db = new JworkDatabase(cp);
        Path keyFile = tempDir.resolve("secret.key");
        // 固定用 AES 实现：DPAPI 绑定 Windows 用户，CI 与本机行为会不一致
        secrets = new SecretStore(new AesGcmSecretCipher(keyFile), keyFile);
        manager = new ProxyManager(db, secrets);
    }

    @AfterEach
    void tearDown() {
        // 还原关闭态：清除本用例可能写入的系统属性与默认传输
        manager.save(new ProxyManager.Settings(false, "", 0, "", "", ""));
    }

    @Test
    @DisplayName("save/settings：设置往返一致，密码解密回明文")
    void settingsRoundTrips() {
        manager.save(new ProxyManager.Settings(true, "127.0.0.1", 7890, "u", "p@ss", "*.corp.com, 10.*"));

        ProxyManager.Settings s = manager.settings();
        assertTrue(s.enabled());
        assertEquals("127.0.0.1", s.host());
        assertEquals(7890, s.port());
        assertEquals("u", s.username());
        assertEquals("p@ss", s.password());
        assertEquals("*.corp.com, 10.*", s.bypass());
    }

    @Test
    @DisplayName("密码加密落库：原始值为密文且可被当前 cipher 识别")
    void passwordStoredEncrypted() {
        manager.save(new ProxyManager.Settings(true, "127.0.0.1", 7890, "u", "plain-secret", ""));

        String raw = db.settingDao().getValue(ProxyManager.KEY_PASSWORD).orElseThrow();
        assertNotEquals("plain-secret", raw);
        assertTrue(secrets.canHandle(raw));
        assertEquals("plain-secret", secrets.decrypt(raw));
    }

    @Test
    @DisplayName("密码留空沿用已存值，clearPassword 才撤销")
    void blankPasswordKeepsAndClearRevokes() {
        manager.save(new ProxyManager.Settings(true, "127.0.0.1", 7890, "u", "p@ss", ""));

        // 界面上密码框不回显明文：一次保存带空串不该等于撤销认证
        manager.save(new ProxyManager.Settings(true, "127.0.0.1", 7890, "u", "   ", ""));
        assertEquals("p@ss", manager.settings().password(), "留空应沿用已存密码");
        assertEquals(SecretStore.State.READABLE, manager.passwordState());

        manager.clearPassword();
        assertEquals("", manager.settings().password());
        assertEquals(SecretStore.State.EMPTY, manager.passwordState());
    }

    @Test
    @DisplayName("外机密文：解不开返回空串并标记 UNREADABLE，绝不把密文体当密码")
    void foreignSealedPasswordIsNotLeaked() {
        db.settingDao().upsert(ProxyManager.KEY_PASSWORD, "dpapi:bm90LW9uLXRoaXMtbWFjaGluZQ==", "脏数据");

        assertEquals("", manager.settings().password(), "解不开应返回空串而不是密文");
        assertEquals(SecretStore.State.UNREADABLE, manager.passwordState());
    }

    @Test
    @DisplayName("apply：启用后写入 http/https 系统属性，本机地址恒在绕过列表")
    void applySetsSystemProperties() {
        manager.save(new ProxyManager.Settings(true, "127.0.0.1", 7890, "", "", "*.corp.com"));

        assertEquals("127.0.0.1", System.getProperty("http.proxyHost"));
        assertEquals("7890", System.getProperty("http.proxyPort"));
        assertEquals("127.0.0.1", System.getProperty("https.proxyHost"));
        assertEquals("7890", System.getProperty("https.proxyPort"));
        String nonProxy = System.getProperty("http.nonProxyHosts");
        assertTrue(nonProxy.contains("localhost"));
        assertTrue(nonProxy.contains("127.*"));
        assertTrue(nonProxy.contains("*.corp.com"));
    }

    @Test
    @DisplayName("apply：关闭后清除全部代理系统属性（含残留）")
    void applyDisabledClearsSystemProperties() {
        manager.save(new ProxyManager.Settings(true, "127.0.0.1", 7890, "", "", ""));
        manager.save(new ProxyManager.Settings(false, "127.0.0.1", 7890, "", "", ""));

        assertNull(System.getProperty("http.proxyHost"));
        assertNull(System.getProperty("http.proxyPort"));
        assertNull(System.getProperty("https.proxyHost"));
        assertNull(System.getProperty("https.proxyPort"));
        assertNull(System.getProperty("http.nonProxyHosts"));
    }

    @Test
    @DisplayName("端口解析：非法或越界按未填处理（0），边界 65535 有效")
    void invalidPortTreatedAsBlank() {
        db.settingDao().upsert(ProxyManager.KEY_PORT, "abc", "test");
        assertEquals(0, manager.settings().port());
        db.settingDao().upsert(ProxyManager.KEY_PORT, "70000", "test");
        assertEquals(0, manager.settings().port());
        db.settingDao().upsert(ProxyManager.KEY_PORT, "65535", "test");
        assertEquals(65535, manager.settings().port());
    }
}
