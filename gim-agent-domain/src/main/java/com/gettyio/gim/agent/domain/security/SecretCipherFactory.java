package com.gettyio.gim.agent.domain.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 按操作系统选择密钥加密实现：Windows 优先 DPAPI，失败或其他平台回退 AES-GCM。
 */
public final class SecretCipherFactory {

    private static final Logger log = LoggerFactory.getLogger(SecretCipherFactory.class);

    private SecretCipherFactory() {
    }

    /**
     * @param aesKeyFile 非 Windows 平台或 DPAPI 不可用时，AES-GCM 使用的密钥文件路径
     */
    public static SecretCipher create(Path aesKeyFile) {
        if (isWindows()) {
            try {
                SecretCipher dpapi = new WindowsDpapiSecretCipher();
                // 自检：加解密往返
                String probe = "gim-agent-self-test";
                if (probe.equals(dpapi.decrypt(dpapi.encrypt(probe)))) {
                    return dpapi;
                }
                log.warn("DPAPI 自检未通过，回退 AES-GCM");
            } catch (Throwable t) {
                log.warn("DPAPI 不可用，回退 AES-GCM: {}", t.getMessage());
            }
        }
        return new AesGcmSecretCipher(aesKeyFile);
    }

    public static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }
}
