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

package com.gettyio.jwork.agent.domain.dao;

import com.gettyio.jwork.agent.domain.persist.SqlRunner;
import com.gettyio.jwork.agent.domain.persist.SqlSchemaInitializer;
import com.gettyio.jwork.agent.domain.persist.SqliteConnectionProvider;
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
 *
 * @author gogym
 */
class AppSettingDaoTest {

    @TempDir
    Path tempDir;

    private AppSettingDao dao;

    @BeforeEach
    void setUp() {
        Path db = tempDir.resolve("jwork.db");
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
        List<com.gettyio.jwork.agent.domain.entity.AppSetting> all = dao.findAll();
        assertEquals(2, all.size());
        assertEquals("a.key", all.get(0).getSettingKey());
        // updated_at 由 LocalDateTime 存 TEXT 后应能读回（非空）
        assertTrue(all.get(0).getUpdatedAt() != null);
    }

    @Test
    @DisplayName("delete 精确键、deleteByPrefix 前缀批量删除")
    void deletesByKeyAndPrefix() {
        dao.upsert("fp.agent-a/doc.md", "1", null);
        dao.upsert("fp.agent-a/doc2.md", "2", null);
        dao.upsert("fp.agent-b/doc.md", "3", null);

        assertEquals(1, dao.delete("fp.agent-a/doc.md"));
        assertTrue(dao.getValue("fp.agent-a/doc.md").isEmpty());
        assertEquals(0, dao.delete("fp.agent-a/doc.md"), "再次删除应影响 0 行");

        assertEquals(1, dao.deleteByPrefix("fp.agent-a/"));
        assertTrue(dao.getValue("fp.agent-a/doc2.md").isEmpty());
        assertEquals("3", dao.getValue("fp.agent-b/doc.md").orElseThrow(), "其他前缀未被误删");
    }

    @Test
    @DisplayName("deleteByPrefix：下划线按字面匹配（LIKE 元字符转义生效）")
    void deleteByPrefixEscapesWildcard() {
        dao.upsert("t_100", "v1", null);
        dao.upsert("tX100", "v2", null);

        assertEquals(1, dao.deleteByPrefix("t_100"));
        assertTrue(dao.getValue("t_100").isEmpty());
        assertEquals("v2", dao.getValue("tX100").orElseThrow(), "不应被下划线通配误删");
    }
}
