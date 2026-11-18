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

import com.gettyio.jwork.agent.domain.TestDb;
import com.gettyio.jwork.agent.domain.entity.TaskRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TaskRunDao} 单测：插入、倒序读取、每任务滚动保留 {@code KEEP_PER_TASK} 条、按任务删除。
 *
 * @author gogym
 */
class TaskRunDaoTest {

    @TempDir
    Path tempDir;

    private TaskRunDao dao;

    @BeforeEach
    void setUp() {
        dao = new TaskRunDao(TestDb.runnerAt(tempDir.resolve("jwork.db")));
    }

    private TaskRun newRun(String code, boolean ok) {
        TaskRun r = new TaskRun();
        r.setTaskCode(code);
        r.setStartedAt(LocalDateTime.now());
        r.setFinishedAt(LocalDateTime.now().plusSeconds(3));
        r.setSuccess(ok);
        r.setSummary(ok ? "done" : null);
        r.setError(ok ? null : "boom");
        r.setSessionId("task-" + code);
        return r;
    }

    @Test
    @DisplayName("插入读回：成功标志与会话/摘要/错误字段完整往返")
    void insertAndRead() {
        long id = dao.insert(newRun("t1", false));
        assertTrue(id > 0);
        TaskRun read = dao.findByCode("t1").get(0);
        assertFalse(read.isSuccess());
        assertEquals("boom", read.getError());
        assertEquals("task-t1", read.getSessionId());
    }

    @Test
    @DisplayName("findRecentByCode 按 id 倒序且遵守 limit")
    void recentOrderedDesc() {
        for (int i = 0; i < 5; i++) {
            dao.insert(newRun("t2", true));
        }
        List<TaskRun> recent = dao.findRecentByCode("t2", 3);
        assertEquals(3, recent.size());
        assertTrue(recent.get(0).getId() > recent.get(1).getId());
    }

    @Test
    @DisplayName("purge 后仅保留最近 KEEP_PER_TASK 条")
    void purgeKeepsLatestWindow() {
        int total = TaskRunDao.KEEP_PER_TASK + 10;
        for (int i = 0; i < total; i++) {
            dao.insert(newRun("t3", true));
        }
        assertEquals(total, dao.count());
        dao.purge("t3");
        assertEquals(TaskRunDao.KEEP_PER_TASK, dao.count());
        // 保留的应是最新那批：最小 id 已前移
        List<TaskRun> remaining = dao.findByCode("t3");
        assertEquals(TaskRunDao.KEEP_PER_TASK, remaining.size());
        assertTrue(remaining.get(remaining.size() - 1).getId() > 10);
    }

    @Test
    @DisplayName("purge 只影响目标任务，其他任务记录不动")
    void purgeScopedByCode() {
        for (int i = 0; i < 3; i++) {
            dao.insert(newRun("tA", true));
            dao.insert(newRun("tB", true));
        }
        dao.purge("tA");
        assertEquals(6, dao.count()); // 未超上限，purge 不删
        dao.deleteByCode("tA");
        assertEquals(3, dao.count());
        assertTrue(dao.findByCode("tA").isEmpty());
    }
}
