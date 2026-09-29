package com.gettyio.gim.agent.domain.persist;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 连接提供者：抽象数据库连接来源，便于测试替换为内存库
 */
public interface ConnectionProvider {

    Connection getConnection() throws SQLException;

    /** 释放底层资源（连接池/共享连接等），默认空实现 */
    default void close() {
    }
}
