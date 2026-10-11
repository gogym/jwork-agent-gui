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

package com.gettyio.gim.agent.domain.dao;

import com.gettyio.gim.agent.domain.entity.ScheduledTask;
import com.gettyio.gim.agent.domain.persist.SqlRunner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * tasks 表 DAO
 *
 * @author gogym
 */
public class ScheduledTaskDao {

    private static final String COLUMNS =
            "id, task_code, task_name, agent_code, prompt, cron_expression, run_at, end_at, status, once, output_mode, unattended, provider_code, model_code, last_run_at, next_run_at, created_at, updated_at";

    private final SqlRunner runner;

    public ScheduledTaskDao(SqlRunner runner) {
        this.runner = runner;
    }

    public static ScheduledTask map(ResultSet rs) throws SQLException {
        ScheduledTask t = new ScheduledTask();
        t.setId(SqlRunner.getLong(rs, "id"));
        t.setTaskCode(rs.getString("task_code"));
        t.setTaskName(rs.getString("task_name"));
        t.setAgentCode(rs.getString("agent_code"));
        t.setPrompt(rs.getString("prompt"));
        t.setCronExpression(rs.getString("cron_expression"));
        t.setRunAt(SqlRunner.getDateTime(rs, "run_at"));
        t.setEndAt(SqlRunner.getDateTime(rs, "end_at"));
        t.setStatus(rs.getString("status"));
        t.setOnce(SqlRunner.getInt(rs, "once"));
        t.setOutputMode(rs.getString("output_mode"));
        t.setUnattended(SqlRunner.getInt(rs, "unattended"));
        t.setProviderCode(rs.getString("provider_code"));
        t.setModelCode(rs.getString("model_code"));
        t.setLastRunAt(SqlRunner.getDateTime(rs, "last_run_at"));
        t.setNextRunAt(SqlRunner.getDateTime(rs, "next_run_at"));
        t.setCreatedAt(SqlRunner.getDateTime(rs, "created_at"));
        t.setUpdatedAt(SqlRunner.getDateTime(rs, "updated_at"));
        return t;
    }

    public long insert(ScheduledTask t) {
        LocalDateTime now = LocalDateTime.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        long id = runner.insert(
                "INSERT INTO tasks (task_code, task_name, agent_code, prompt, cron_expression, run_at, end_at, status, once, output_mode, unattended, provider_code, model_code, last_run_at, next_run_at, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                t.getTaskCode(), t.getTaskName(), t.getAgentCode(), t.getPrompt(), t.getCronExpression(),
                t.getRunAt(), t.getEndAt(), t.getStatus(), t.getOnce(), t.getOutputMode(), t.getUnattended(),
                t.getProviderCode(), t.getModelCode(), t.getLastRunAt(), t.getNextRunAt(), now, now);
        t.setId(id);
        return id;
    }

    public int update(ScheduledTask t) {
        t.setUpdatedAt(LocalDateTime.now());
        return runner.update(
                "UPDATE tasks SET task_name=?, agent_code=?, prompt=?, cron_expression=?, run_at=?, end_at=?, status=?, once=?, output_mode=?, unattended=?, provider_code=?, model_code=?, next_run_at=?, updated_at=? WHERE task_code=?",
                t.getTaskName(), t.getAgentCode(), t.getPrompt(), t.getCronExpression(), t.getRunAt(), t.getEndAt(),
                t.getStatus(), t.getOnce(), t.getOutputMode(), t.getUnattended(), t.getProviderCode(), t.getModelCode(),
                t.getNextRunAt(), t.getUpdatedAt(), t.getTaskCode());
    }

    public int markRun(String taskCode, LocalDateTime runAt, LocalDateTime nextRunAt) {
        return runner.update("UPDATE tasks SET last_run_at=?, next_run_at=?, updated_at=? WHERE task_code=?",
                runAt, nextRunAt, LocalDateTime.now(), taskCode);
    }

    /** 手动触发后只记录最近运行时间（不动 next_run_at，不消费一次性任务）。 */
    public int markLastRun(String taskCode, LocalDateTime runAt) {
        return runner.update("UPDATE tasks SET last_run_at=?, updated_at=? WHERE task_code=?",
                runAt, LocalDateTime.now(), taskCode);
    }

    public Optional<ScheduledTask> findByCode(String taskCode) {
        return runner.queryOne("SELECT " + COLUMNS + " FROM tasks WHERE task_code=?", ScheduledTaskDao::map, taskCode);
    }

    public List<ScheduledTask> findAll() {
        return runner.query("SELECT " + COLUMNS + " FROM tasks ORDER BY id", ScheduledTaskDao::map);
    }

    public List<ScheduledTask> findByStatus(String status) {
        return runner.query("SELECT " + COLUMNS + " FROM tasks WHERE status=? ORDER BY id", ScheduledTaskDao::map, status);
    }

    public int deleteByCode(String taskCode) {
        return runner.update("DELETE FROM tasks WHERE task_code=?", taskCode);
    }

    /** 引用该智能体的定时任务数（智能体删除前的引用检查） */
    public int countByAgent(String agentCode) {
        return runner.queryOne("SELECT COUNT(*) AS n FROM tasks WHERE agent_code=?",
                rs -> rs.getInt("n"), agentCode).orElse(0);
    }
}
