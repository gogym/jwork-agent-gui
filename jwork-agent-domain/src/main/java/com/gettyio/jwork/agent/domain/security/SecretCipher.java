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

/**
 * 密钥加解密抽象：API Key 等在库中以密文存储
 *
 * @author gogym
 */
public interface SecretCipher {

    /**
     * 加密明文，返回带算法前缀的密文（如 {@code aes:...} / {@code dpapi:...}）
     */
    String encrypt(String plainText);

    /**
     * 解密密文；无法识别前缀时抛出异常
     */
    String decrypt(String cipherText);

    /**
     * 是否为当前实现产生的密文
     */
    default boolean canHandle(String cipherText) {
        return cipherText != null && cipherText.startsWith(prefix());
    }

    /**
     * 密文前缀标识
     */
    String prefix();
}
