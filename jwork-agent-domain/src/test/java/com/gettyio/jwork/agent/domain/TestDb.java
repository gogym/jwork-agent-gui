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

package com.gettyio.jwork.agent.domain;

import com.gettyio.jwork.agent.domain.persist.SqlRunner;
import com.gettyio.jwork.agent.domain.persist.SqlSchemaInitializer;
import com.gettyio.jwork.agent.domain.persist.SqliteConnectionProvider;

import java.nio.file.Path;

/**
 * 测试脚手架：在指定文件上以 {@code db/schema.sql} 建库并返回 {@link SqlRunner}。
 * <p>因 {@link SqliteConnectionProvider} 每次操作开一个新连接，内存库（{@code :memory:}）不跨连接保留，
 * 故统一用 {@code @TempDir} 下的真实文件，既隔离又验证 SQL 落盘语义。</p>
 *
 * @author gogym
 */
public final class TestDb {

    private TestDb() {
    }

    public static SqlRunner runnerAt(Path dbFile) {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(dbFile);
        new SqlSchemaInitializer(cp).initialize();
        return new SqlRunner(cp);
    }
}
