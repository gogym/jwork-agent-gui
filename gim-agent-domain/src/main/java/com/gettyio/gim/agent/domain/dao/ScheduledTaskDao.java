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
 */
public class ScheduledTaskDao {

    private static final String COLUMNS =
            "id, task_code, task_name, agent_code, prompt, cron_expression, status, once, last_run_at, next_run_at, created_at, updated_at";

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
        t.setStatus(rs.getString("status"));
        t.setOnce(SqlRunner.getInt(rs, "once"));
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
                "INSERT INTO tasks (task_code, task_name, agent_code, prompt, cron_expression, status, once, last_run_at, next_run_at, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                t.getTaskCode(), t.getTaskName(), t.getAgentCode(), t.getPrompt(), t.getCronExpression(),
                t.getStatus(), t.getOnce(), t.getLastRunAt(), t.getNextRunAt(), now, now);
        t.setId(id);
        return id;
    }

    public int update(ScheduledTask t) {
        t.setUpdatedAt(LocalDateTime.now());
        return runner.update(
                "UPDATE tasks SET task_name=?, agent_code=?, prompt=?, cron_expression=?, status=?, once=?, next_run_at=?, updated_at=? WHERE task_code=?",
                t.getTaskName(), t.getAgentCode(), t.getPrompt(), t.getCronExpression(), t.getStatus(),
                t.getOnce(), t.getNextRunAt(), t.getUpdatedAt(), t.getTaskCode());
    }

    public int markRun(String taskCode, LocalDateTime runAt, LocalDateTime nextRunAt) {
        return runner.update("UPDATE tasks SET last_run_at=?, next_run_at=?, updated_at=? WHERE task_code=?",
                runAt, nextRunAt, LocalDateTime.now(), taskCode);
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
