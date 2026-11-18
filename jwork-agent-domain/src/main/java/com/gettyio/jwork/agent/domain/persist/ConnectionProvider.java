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

package com.gettyio.jwork.agent.domain.persist;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 连接提供者：抽象数据库连接来源，便于测试替换为内存库
 *
 * @author gogym
 */
public interface ConnectionProvider {

    Connection getConnection() throws SQLException;

    /** 释放底层资源（连接池/共享连接等），默认空实现 */
    default void close() {
    }
}
