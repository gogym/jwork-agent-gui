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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按密文前缀路由的密钥库：写入统一用本机主推算法，读取按前缀找回当初加密它的那套算法。
 *
 * <p>为什么必须路由，而不是「只认本机这一种算法」：主推算法按操作系统选择（Windows 用 DPAPI，
 * 其余用 AES-GCM），而 DPAPI 密文还额外绑定当前 Windows 用户。同一条设置值可能在换平台、
 * 换用户、或启动时 DPAPI 自检降级后被本机算法认不出。此时按 {@code canHandle ? decrypt : stored}
 * 的写法会把整串密文当成明文密钥返回，随后被塞进 Authorization 头发给第三方——既解不开又外泄。
 * 本类的口径是：认出前缀就交给对应算法，认不出或解不开一律明确失败，绝不把密文当明文交出。</p>
 *
 * <p>没有已知前缀的值按历史明文处理（原样返回并记一次告警），保证手工写过密钥或用过旧版本的库
 * 仍能直接用；这类值下次保存时会被重新封存。</p>
 *
 * @author gogym
 */
public final class SecretStore implements SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretStore.class);

    /** 已知密文前缀：认出前缀即必须按对应算法解密，不存在「原样返回」的分支。 */
    private static final String[] KNOWN_PREFIXES = {
            AesGcmSecretCipher.PREFIX, WindowsDpapiSecretCipher.PREFIX};

    /** 新写入使用的算法（本机主推）。 */
    private final SecretCipher writer;
    /** AES-GCM 密钥文件；仅在真读到 {@code aes:} 存量密文时才按需构造读取器。 */
    private final Path aesKeyFile;
    private final Map<String, SecretCipher> readers = new LinkedHashMap<>();
    private volatile boolean legacyPlainWarned;

    /** 按操作系统选定主推算法（{@link SecretCipherFactory}）并包成可路由的密钥库。 */
    public static SecretStore create(Path aesKeyFile) {
        return new SecretStore(SecretCipherFactory.create(aesKeyFile), aesKeyFile);
    }

    public SecretStore(SecretCipher writer, Path aesKeyFile) {
        this.writer = writer;
        this.aesKeyFile = aesKeyFile;
        this.readers.put(writer.prefix(), writer);
    }

    /** 入库值相对本机的可读性，供设置页区分「未配置」与「已配置但本机解不开」。 */
    public enum State {
        /** 未配置（空值）。 */
        EMPTY,
        /** 密文，且本机可按前缀路由解开。 */
        READABLE,
        /** 密文认得前缀但本机解不开：换机器、换 Windows 用户或密钥文件丢失。 */
        UNREADABLE,
        /** 无算法前缀的历史明文。 */
        LEGACY_PLAIN
    }

    /** 用本机主推算法加密；{@code null} 语义交由实现类，前缀随算法而定。 */
    @Override
    public String encrypt(String plainText) {
        return writer.encrypt(plainText);
    }

    /**
     * 按前缀路由解密。前缀缺失、前缀无对应实现、实现解不开都抛
     * {@link SecurityOperationException}——任何一条路径都不会把密文当明文返回。
     */
    @Override
    public String decrypt(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        String prefix = prefixOf(cipherText);
        if (prefix == null) {
            throw new SecurityOperationException("密文缺少算法前缀，无法判定加密算法");
        }
        SecretCipher reader = readerFor(prefix);
        if (reader == null) {
            throw new SecurityOperationException("密文前缀 " + prefix + " 在本机无对应解密实现");
        }
        try {
            return reader.decrypt(cipherText);
        } catch (RuntimeException e) {
            // 只带前缀与原因，绝不把密文体写进日志
            throw new SecurityOperationException("密文无法用 " + prefix + " 算法解开（密钥文件丢失或换了 Windows 用户）", e);
        }
    }

    /** 是否为本库任一算法封存的密文；用于区分「密文」与「用户新填入的明文」。 */
    @Override
    public boolean canHandle(String cipherText) {
        return cipherText != null && prefixOf(cipherText) != null;
    }

    /** 新写入将使用的前缀。 */
    @Override
    public String prefix() {
        return writer.prefix();
    }

    /** 加密入库值：空白即空串，由调用方决定空串意味着「不修改」还是「清除」。 */
    public String seal(String plain) {
        if (plain == null || plain.isBlank()) {
            return "";
        }
        return encrypt(plain.trim());
    }

    /**
     * 读取入库值并给出明文：空值返回空串；密文按前缀解密，解不开记 ERROR 后返回空串（此时界面
     * 应提示「已配置但本机无法解密」并让用户重录，而不是把密文回填成可用值）；历史明文原样返回。
     *
     * @param what 人类可读的用途名，仅用于日志定位
     */
    public String open(String stored, String what) {
        if (stored == null || stored.isBlank()) {
            return "";
        }
        String value = stored.trim();
        if (prefixOf(value) == null) {
            warnLegacyPlain(what);
            return value;
        }
        try {
            return decrypt(value);
        } catch (RuntimeException e) {
            log.error("{} 本机无法解密，按未配置处理（请在设置页重新录入）: {}", what, e.getMessage());
            return "";
        }
    }

    /** 入库值的可读性；{@code UNREADABLE} 与 {@code EMPTY} 在界面上必须是两件事，否则用户会丢配置。 */
    public State state(String stored) {
        if (stored == null || stored.isBlank()) {
            return State.EMPTY;
        }
        String value = stored.trim();
        if (prefixOf(value) == null) {
            return State.LEGACY_PLAIN;
        }
        try {
            decrypt(value);
            return State.READABLE;
        } catch (RuntimeException e) {
            return State.UNREADABLE;
        }
    }

    private static String prefixOf(String value) {
        for (String prefix : KNOWN_PREFIXES) {
            if (value.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    private synchronized SecretCipher readerFor(String prefix) {
        SecretCipher reader = readers.get(prefix);
        if (reader != null) {
            return reader;
        }
        try {
            reader = build(prefix);
        } catch (Throwable t) {
            // DPAPI 在非 Windows 上加载即失败；AES 密钥文件损坏同理，不因此让整条读取链崩掉
            log.error("构造 {} 解密实现失败: {}", prefix, t.toString());
            return null;
        }
        if (reader != null) {
            readers.put(prefix, reader);
        }
        return reader;
    }

    private SecretCipher build(String prefix) {
        if (AesGcmSecretCipher.PREFIX.equals(prefix)) {
            return new AesGcmSecretCipher(aesKeyFile);
        }
        if (WindowsDpapiSecretCipher.PREFIX.equals(prefix)) {
            return SecretCipherFactory.isWindows() ? new WindowsDpapiSecretCipher() : null;
        }
        return null;
    }

    private void warnLegacyPlain(String what) {
        if (!legacyPlainWarned) {
            legacyPlainWarned = true;
            log.warn("检测到未加密的历史明文密钥（例如 {}）；下次保存该项时会自动改为密文", what);
        }
    }
}
