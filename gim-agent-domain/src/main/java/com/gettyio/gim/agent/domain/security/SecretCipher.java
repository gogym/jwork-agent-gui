package com.gettyio.gim.agent.domain.security;

/**
 * 密钥加解密抽象：API Key 等在库中以密文存储
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
