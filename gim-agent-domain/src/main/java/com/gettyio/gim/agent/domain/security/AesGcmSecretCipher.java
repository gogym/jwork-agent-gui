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

package com.gettyio.gim.agent.domain.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * 跨平台默认实现：AES-256-GCM，密钥来自本地密钥文件（首次运行随机生成）。
 * <p>密文格式：{@code aes:base64(iv[12] || ciphertext||tag)}</p>
 *
 * @author gogym
 */
public class AesGcmSecretCipher implements SecretCipher {

    /** 密文前缀；{@link SecretStore} 按此前缀把存量密文路由回本实现。 */
    public static final String PREFIX = "aes:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmSecretCipher(Path keyFile) {
        this.key = new SecretKeySpec(loadOrCreateKey(keyFile), "AES");
    }

    /** 以现成 256 位密钥构造（测试/密钥注入用）；长度不为 32 字节抛 {@link IllegalArgumentException}。 */
    public AesGcmSecretCipher(byte[] key256) {
        if (key256.length != KEY_BYTES) {
            throw new IllegalArgumentException("AES-256 密钥应为 " + KEY_BYTES + " 字节");
        }
        this.key = new SecretKeySpec(key256, "AES");
    }

    /** 加密：随机 12 字节 IV + AES-256-GCM，输出 {@code aes:base64(iv||密文||tag)}；{@code null} 原样返回。 */
    @Override
    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new SecurityOperationException("AES 加密失败", e);
        }
    }

    /** 解密 {@code aes:} 前缀密文；前缀不匹配或认证失败抛 {@link SecurityOperationException}，{@code null} 原样返回。 */
    @Override
    public String decrypt(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        if (!cipherText.startsWith(PREFIX)) {
            throw new SecurityOperationException("密文前缀不匹配 AES-GCM: " + cipherText.substring(0, Math.min(8, cipherText.length())));
        }
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new SecurityOperationException("AES 解密失败", e);
        }
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    private static byte[] loadOrCreateKey(Path keyFile) {
        try {
            if (Files.exists(keyFile)) {
                String b64 = Files.readString(keyFile).trim();
                return Base64.getDecoder().decode(b64);
            }
            Files.createDirectories(keyFile.toAbsolutePath().getParent());
            byte[] key = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(key);
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(key));
            restrictPermissions(keyFile);
            return key;
        } catch (IOException e) {
            throw new SecurityOperationException("无法初始化 AES 密钥文件: " + keyFile, e);
        }
    }

    private static void restrictPermissions(Path keyFile) {
        try {
            Set<PosixFilePermission> ownerOnly = Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(keyFile, ownerOnly);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 无 POSIX 权限，忽略
        }
    }
}
