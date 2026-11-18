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

package com.gettyio.jwork.agent.domain.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecretStore} 单测：前缀路由、绝不把密文当明文、历史明文兼容与可读性状态。
 *
 * @author gogym
 */
class SecretStoreTest {

    /** 代替 DPAPI 的外来算法：base64 可逆，跨平台可跑，前缀仍是真实的 {@code dpapi:}。 */
    private static final class StandInCipher implements SecretCipher {

        @Override
        public String encrypt(String plainText) {
            return WindowsDpapiSecretCipher.PREFIX
                    + Base64.getEncoder().encodeToString(plainText.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String decrypt(String cipherText) {
            if (!cipherText.startsWith(prefix())) {
                throw new SecurityOperationException("前缀不匹配");
            }
            return new String(Base64.getDecoder()
                    .decode(cipherText.substring(prefix().length())), StandardCharsets.UTF_8);
        }

        @Override
        public String prefix() {
            return WindowsDpapiSecretCipher.PREFIX;
        }
    }

    private static SecretCipher aes(Path keyFile) {
        return new AesGcmSecretCipher(keyFile);
    }

    @Test
    @DisplayName("往返：本机主推算法加解密")
    void roundTripWithActiveCipher(@TempDir Path dir) {
        SecretStore store = new SecretStore(new StandInCipher(), dir.resolve("secret.key"));
        String sealed = store.encrypt("sk-测试-1");
        assertTrue(sealed.startsWith("dpapi:"));
        assertEquals("sk-测试-1", store.decrypt(sealed));
    }

    @Test
    @DisplayName("换算法不失明：主推为 dpapi 时，aes: 存量密文路由到 AES 解开")
    void foreignPrefixedValueIsRoutedToItsOwnCipher(@TempDir Path dir) {
        Path keyFile = dir.resolve("secret.key");
        String sealed = aes(keyFile).encrypt("sk-old-machine");
        assertTrue(sealed.startsWith("aes:"));

        SecretStore store = new SecretStore(new StandInCipher(), keyFile);
        assertEquals("sk-old-machine", store.decrypt(sealed), "应按 aes: 前缀构造 AES 读取器");
    }

    @Test
    @DisplayName("认得前缀却解不开：明确失败，绝不把密文原样当明文返回")
    void undecryptableSealedValueNeverLeaksCiphertext(@TempDir Path dir) {
        Path keyFile = dir.resolve("secret.key");
        String sealed = aes(keyFile).encrypt("sk-from-other-key");
        // 同一路由结构、另一份密钥文件：前缀认得，认证标签过不了
        SecretStore store = new SecretStore(new StandInCipher(), dir.resolve("other.key"));

        assertThrows(SecurityOperationException.class, () -> store.decrypt(sealed));
        assertEquals("", store.open(sealed, "联网搜索 API Key"), "解不开只能给出空值，界面据此提示重录");
    }

    @Test
    @DisplayName("未知前缀不是本库封存的密文：按历史明文对待")
    void unknownPrefixIsTreatedAsLegacyPlaintext(@TempDir Path dir) {
        SecretStore store = new SecretStore(new StandInCipher(), dir.resolve("secret.key"));

        assertFalse(store.canHandle("sk-plain-key"));
        assertTrue(store.canHandle("aes:whatever"));
        assertTrue(store.canHandle("dpapi:whatever"));
        assertThrows(SecurityOperationException.class, () -> store.decrypt("kms:future-format"));
        assertEquals("sk-plain-key", store.open("sk-plain-key", "图像 API Key"));
    }

    @Test
    @DisplayName("空值统一收敛为空串，seal 空白不落密文")
    void blanksAreEmptyStrings(@TempDir Path dir) {
        SecretStore store = new SecretStore(new StandInCipher(), dir.resolve("secret.key"));

        assertEquals("", store.open(null, "x"));
        assertEquals("", store.open("   ", "x"));
        assertEquals("", store.seal(null));
        assertEquals("", store.seal("  "));
        assertEquals("sk-1", store.decrypt(store.seal("  sk-1  ")), "入库前应去掉首尾空白");
    }

    @Test
    @DisplayName("state 区分未配置、可读、解不开与历史明文")
    void stateDistinguishesUnconfiguredFromUnreadable(@TempDir Path dir) {
        Path keyFile = dir.resolve("secret.key");
        String readable = aes(keyFile).encrypt("sk-1");
        SecretStore store = new SecretStore(new StandInCipher(), keyFile);

        assertEquals(SecretStore.State.EMPTY, store.state(""));
        assertEquals(SecretStore.State.READABLE, store.state(readable));
        assertEquals(SecretStore.State.LEGACY_PLAIN, store.state("sk-hand-written"));

        SecretStore movedTo = new SecretStore(new StandInCipher(), dir.resolve("another.key"));
        assertEquals(SecretStore.State.UNREADABLE, movedTo.state(readable), "换密钥文件后应报「解不开」而非「未配置」");
    }

    @Test
    @DisplayName("canHandle 让表单里的旧密文不被二次加密")
    void foreignSealedValueIsRecognisedAsCiphertext(@TempDir Path dir) {
        Path keyFile = dir.resolve("secret.key");
        String sealed = aes(keyFile).encrypt("sk-1");
        SecretStore store = new SecretStore(new StandInCipher(), keyFile);

        assertTrue(store.canHandle(sealed), "回传的旧密文须认得出是密文，否则会被再加密一层");
    }
}
