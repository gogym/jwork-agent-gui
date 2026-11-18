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

package com.gettyio.jwork.agent.ui.sync;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 云端同步载荷的口令加密容器：上传前把数据库快照 / workspace 压缩包以用户口令
 * （PBKDF2 派生密钥）加密为自描述文件，恢复时用同一口令解密。
 * <p>文件格式：{@code "JWORKSYNC1"(8) + salt(16) + iv(12) + AES-256-GCM 密文(含 16B tag)}；
 * 全程流式处理，大文件不占堆内存。口令不落远端，换设备恢复时重新输入同一口令即可。</p>
 *
 * @author gogym
 */
public final class SyncPayloadCipher {

    /** 容器魔数，用于识别加密载荷 */
    static final byte[] MAGIC = "JWORKSYNC1".getBytes(StandardCharsets.US_ASCII);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 210_000;

    private SyncPayloadCipher() {
    }

    /** 加密文件：明文 {@code plain} → 密文容器 {@code encrypted}（失败时清理半成品）。 */
    public static void encrypt(Path plain, Path encrypted, String passphrase) throws IOException {
        byte[] salt = randomBytes(SALT_LENGTH);
        byte[] iv = randomBytes(IV_LENGTH);
        try {
            SecretKey key = deriveKey(passphrase, salt);
            try (OutputStream raw = Files.newOutputStream(encrypted)) {
                raw.write(MAGIC);
                raw.write(salt);
                raw.write(iv);
                raw.flush();
                try (CipherOutputStream cipherOut = new CipherOutputStream(raw, initCipher(Cipher.ENCRYPT_MODE, key, iv))) {
                    Files.copy(plain, cipherOut);
                }
            }
        } catch (GeneralSecurityException e) {
            Files.deleteIfExists(encrypted);
            throw new IOException("加密同步载荷失败", e);
        }
    }

    /**
     * 解密容器：{@code encrypted} → 明文 {@code plain}（失败时清理半成品）。
     *
     * @throws IOException 容器头不合法、口令不匹配或文件损坏
     */
    public static void decrypt(Path encrypted, Path plain, String passphrase) throws IOException {
        try (InputStream in = Files.newInputStream(encrypted)) {
            byte[] header = in.readNBytes(MAGIC.length);
            byte[] salt = in.readNBytes(SALT_LENGTH);
            byte[] iv = in.readNBytes(IV_LENGTH);
            if (!Arrays.equals(header, MAGIC) || salt.length != SALT_LENGTH || iv.length != IV_LENGTH) {
                throw new IOException("不是有效的加密同步文件");
            }
            SecretKey key = deriveKey(passphrase, salt);
            try (CipherInputStream cipherIn = new CipherInputStream(in, initCipher(Cipher.DECRYPT_MODE, key, iv));
                 OutputStream out = Files.newOutputStream(plain)) {
                cipherIn.transferTo(out);
            }
        } catch (IOException | GeneralSecurityException e) {
            Files.deleteIfExists(plain);
            throw new IOException("解密同步载荷失败（口令不匹配或文件损坏）", e);
        }
    }

    /** 口令 + salt 派生 AES-256 密钥。 */
    private static SecretKey deriveKey(String passphrase, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            byte[] keyBytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static Cipher initCipher(int mode, SecretKey key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        return cipher;
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
