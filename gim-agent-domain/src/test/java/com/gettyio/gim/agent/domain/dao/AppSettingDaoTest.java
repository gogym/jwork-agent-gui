package com.gettyio.gim.agent.domain.dao;

import com.gettyio.gim.agent.domain.persist.SqlRunner;
import com.gettyio.gim.agent.domain.persist.SqlSchemaInitializer;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AppSettingDao} CRUD 单测，连带验证 {@link SqlSchemaInitializer} 幂等与 SqlRunner 时间往返。
 */
class AppSettingDaoTest {

    @TempDir
    Path tempDir;

    private AppSettingDao dao;

    @BeforeEach
    void setUp() {
        Path db = tempDir.resolve("gim.db");
        // 连续两次 initialize 应幂等（CREATE IF NOT EXISTS）
        SqliteConnectionProvider cp = new SqliteConnectionProvider(db);
        new SqlSchemaInitializer(cp).initialize();
        new SqlSchemaInitializer(cp).initialize();
        dao = new AppSettingDao(new SqlRunner(cp));
    }

    @Test
    @DisplayName("upsert 首次插入、再次更新覆盖")
    void insertThenUpdate() {
        dao.upsert("app.theme", "light", "主题");
        assertEquals("light", dao.getValue("app.theme").orElseThrow());

        dao.upsert("app.theme", "dark", "主题改");
        assertEquals("dark", dao.getValue("app.theme").orElseThrow());
    }

    @Test
    @DisplayName("缺失键返回空 Optional")
    void missingKeyIsEmpty() {
        assertTrue(dao.getValue("nope").isEmpty());
    }

    @Test
    @DisplayName("findAll 按键升序，updated_at 以 ISO 文本往返")
    void findAllOrdered() {
        dao.upsert("b.key", "2", null);
        dao.upsert("a.key", "1", null);
        List<com.gettyio.gim.agent.domain.entity.AppSetting> all = dao.findAll();
        assertEquals(2, all.size());
        assertEquals("a.key", all.get(0).getSettingKey());
        // updated_at 由 LocalDateTime 存 TEXT 后应能读回（非空）
        assertTrue(all.get(0).getUpdatedAt() != null);
    }
}
