package com.gettyio.gim.agent.domain.security;

/**
 * 密钥加解密运行时异常
 */
public class SecurityOperationException extends RuntimeException {

    public SecurityOperationException(String message, Throwable cause) {
        super(message, cause);
    }

    public SecurityOperationException(String message) {
        super(message);
    }
}
