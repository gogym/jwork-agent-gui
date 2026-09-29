package com.gettyio.gim.agent.domain.persist;

/**
 * 持久层运行时异常，包装 SQLException
 */
public class PersistException extends RuntimeException {

    public PersistException(String message, Throwable cause) {
        super(message, cause);
    }

    public PersistException(String message) {
        super(message);
    }
}
