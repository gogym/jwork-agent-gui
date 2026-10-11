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
import com.gettyio.gim.agent.domain.security.SecretCipher;
import com.gettyio.gim.agent.domain.security.SecretCipherFactory;
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
 * {@link ProxyManager} 单测：设置往返与密码加密落库、系统属性的应用与清理、端口解析边界。
 * 全部落临时 SQLite 与临时密钥文件（与 McpAdminServiceTest 同模式）；每个用例结束还原
 * 代理关闭态，避免系统属性污染同 JVM 的其他测试。
 *
 * @author gogym
 */
class ProxyManagerTest {

    @TempDir
    Path tempDir;

    private GimDatabase db;
    private SecretCipher cipher;
    private ProxyManager manager;

    @BeforeEach
    void setUp() throws Exception {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(tempDir.resolve("gim.db"));
        new SqlSchemaInitializer(cp).initialize();
        db = new GimDatabase(cp);
        cipher = SecretCipherFactory.create(tempDir.resolve("secret.key"));
        manager = new ProxyManager(db, cipher);
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
        assertTrue(cipher.canHandle(raw));
        assertEquals("plain-secret", cipher.decrypt(raw));
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
