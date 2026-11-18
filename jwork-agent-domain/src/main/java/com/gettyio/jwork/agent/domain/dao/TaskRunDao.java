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

import com.gettyio.jwork.agent.domain.entity.TaskRun;
import com.gettyio.jwork.agent.domain.persist.SqlRunner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * task_runs 表 DAO：任务执行历史，写入 + 每任务滚动保留最近 {@value #KEEP_PER_TASK} 条。
 *
 * @author gogym
 */
public class TaskRunDao {

    /** 每个任务保留的历史条数上限 */
    public static final int KEEP_PER_TASK = 50;

    private static final String COLUMNS =
            "id, task_code, started_at, finished_at, success, summary, error, session_id";

    private final SqlRunner runner;

    public TaskRunDao(SqlRunner runner) {
        this.runner = runner;
    }

    public static TaskRun map(ResultSet rs) throws SQLException {
        TaskRun r = new TaskRun();
        r.setId(SqlRunner.getLong(rs, "id"));
        r.setTaskCode(rs.getString("task_code"));
        r.setStartedAt(SqlRunner.getDateTime(rs, "started_at"));
        r.setFinishedAt(SqlRunner.getDateTime(rs, "finished_at"));
        Integer ok = SqlRunner.getInt(rs, "success");
        r.setSuccess(ok != null && ok != 0);
        r.setSummary(rs.getString("summary"));
        r.setError(rs.getString("error"));
        r.setSessionId(rs.getString("session_id"));
        return r;
    }

    public long insert(TaskRun r) {
        long id = runner.insert(
                "INSERT INTO task_runs (task_code, started_at, finished_at, success, summary, error, session_id) "
                        + "VALUES (?,?,?,?,?,?,?)",
                r.getTaskCode(), r.getStartedAt(), r.getFinishedAt(),
                r.isSuccess() ? 1 : 0, r.getSummary(), r.getError(), r.getSessionId());
        r.setId(id);
        return id;
    }

    /** 某任务最近若干条执行记录（时间倒序）。 */
    public List<TaskRun> findRecentByCode(String taskCode, int limit) {
        return runner.query("SELECT " + COLUMNS + " FROM task_runs WHERE task_code=? ORDER BY id DESC LIMIT ?",
                TaskRunDao::map, taskCode, limit);
    }

    /** 某任务全部执行记录（时间倒序），供「执行历史」弹窗展示。 */
    public List<TaskRun> findByCode(String taskCode) {
        return findRecentByCode(taskCode, KEEP_PER_TASK);
    }

    /** 全库最近若干条执行记录（时间倒序），供「执行记录」页签展示。 */
    public List<TaskRun> findAllRecent(int limit) {
        return runner.query("SELECT " + COLUMNS + " FROM task_runs ORDER BY id DESC LIMIT ?",
                TaskRunDao::map, limit);
    }

    /** 删除超出保留条数的旧记录（写入后调用），返回删除行数。 */
    public int purge(String taskCode) {
        return runner.update(
                "DELETE FROM task_runs WHERE task_code=? AND id NOT IN ("
                        + "SELECT id FROM task_runs WHERE task_code=? ORDER BY id DESC LIMIT ?)",
                taskCode, taskCode, KEEP_PER_TASK);
    }

    /** 删除某任务的全部执行历史（任务删除时联动）。 */
    public int deleteByCode(String taskCode) {
        return runner.update("DELETE FROM task_runs WHERE task_code=?", taskCode);
    }

    /** 全库执行记录总数（备份/清理诊断用）。 */
    public int count() {
        return runner.queryOne("SELECT COUNT(*) AS n FROM task_runs", rs -> rs.getInt("n")).orElse(0);
    }
}
