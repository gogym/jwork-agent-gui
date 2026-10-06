package com.gettyio.gim.agent.domain;

import com.gettyio.gim.agent.domain.persist.SqlRunner;
import com.gettyio.gim.agent.domain.persist.SqlSchemaInitializer;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;

import java.nio.file.Path;

/**
 * 测试脚手架：在指定文件上以 {@code db/schema.sql} 建库并返回 {@link SqlRunner}。
 * <p>因 {@link SqliteConnectionProvider} 每次操作开一个新连接，内存库（{@code :memory:}）不跨连接保留，
 * 故统一用 {@code @TempDir} 下的真实文件，既隔离又验证 SQL 落盘语义。</p>
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
