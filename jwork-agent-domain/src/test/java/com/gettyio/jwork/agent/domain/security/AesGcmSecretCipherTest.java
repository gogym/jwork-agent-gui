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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AesGcmSecretCipher} 单测：加解密往返、前缀识别、null 透传、密钥文件生成、错误密文拒绝。
 *
 * @author gogym
 */
class AesGcmSecretCipherTest {

    private static final byte[] KEY32 = "0123456789abcdef0123456789abcdef".getBytes();

    @Test
    @DisplayName("往返：同密钥解密还原原文，密文带 aes: 前缀且两次加密不同（随机 IV）")
    void roundTrip() {
        AesGcmSecretCipher cipher = new AesGcmSecretCipher(KEY32);
        String plain = "sk-test-密钥-123";
        String c1 = cipher.encrypt(plain);
        String c2 = cipher.encrypt(plain);
        assertTrue(c1.startsWith("aes:"));
        assertNotEquals(c1, c2, "IV 随机化应使同明文密文不同");
        assertEquals(plain, cipher.decrypt(c1));
        assertEquals(plain, cipher.decrypt(c2));
    }

    @Test
    @DisplayName("null 输入加解密均返回 null")
    void nullPassthrough() {
        AesGcmSecretCipher cipher = new AesGcmSecretCipher(KEY32);
        assertEquals(null, cipher.encrypt(null));
        assertEquals(null, cipher.decrypt(null));
    }

    @Test
    @DisplayName("canHandle 依据前缀判定归属")
    void canHandleByPrefix() {
        AesGcmSecretCipher cipher = new AesGcmSecretCipher(KEY32);
        assertTrue(cipher.canHandle("aes:xyz"));
        assertTrue(!cipher.canHandle("dpapi:xyz"));
    }

    @Test
    @DisplayName("密钥文件首启生成、二启复用同密钥可解旧文")
    void keyFileGeneratedAndReused(@TempDir Path dir) throws Exception {
        Path keyFile = dir.resolve("secret.key");
        AesGcmSecretCipher first = new AesGcmSecretCipher(keyFile);
        String cipher = first.encrypt("hello");
        assertTrue(Files.exists(keyFile));

        AesGcmSecretCipher second = new AesGcmSecretCipher(keyFile);
        assertEquals("hello", second.decrypt(cipher), "同一密钥文件应能解密此前密文");
    }

    @Test
    @DisplayName("解密非 aes 前缀密文抛 SecurityOperationException")
    void decryptRejectsForeignPrefix() {
        AesGcmSecretCipher cipher = new AesGcmSecretCipher(KEY32);
        assertThrows(SecurityOperationException.class, () -> cipher.decrypt("dpapi:notmine"));
    }

    @Test
    @DisplayName("错误密钥长度被拒")
    void wrongKeyLengthRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new AesGcmSecretCipher(new byte[16]));
    }
}
