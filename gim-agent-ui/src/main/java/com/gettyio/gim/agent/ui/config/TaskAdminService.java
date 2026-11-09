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

package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ScheduledTask;
import com.gettyio.gim.agent.domain.entity.TaskRun;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.enums.TaskOutputMode;
import com.gettyio.gim.agent.domain.enums.TaskStatus;
import com.gettyio.gim.agent.scheduler.AgentTaskExecutor;
import com.gettyio.gim.agent.scheduler.TaskSchedulerService;
import com.gettyio.gim.agent.ui.i18n.I18n;
import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动化任务管理：任务表增删改查 + Quartz 调度联动 + 手动触发。
 * <p>到期（end_at）语义：Quartz 触发器自动截止后不再触发，已过期的 READY 任务在列表读取
 * 与启动恢复两处被落为 PAUSED（「到期后自动暂停，不再创建新的执行」）。</p>
 *
 * @author gogym
 */
public class TaskAdminService {

    private static final Logger log = LoggerFactory.getLogger(TaskAdminService.class);

    private final GimDatabase db;
    private final TaskSchedulerService schedulerService;
    private final AgentTaskExecutor agentTaskExecutor;

    public TaskAdminService(GimDatabase db, TaskSchedulerService schedulerService,
                            AgentTaskExecutor agentTaskExecutor) {
        this.db = db;
        this.schedulerService = schedulerService;
        this.agentTaskExecutor = agentTaskExecutor;
    }

    /** 全部任务；顺便把已到期但仍是 READY 的任务落为 PAUSED。 */
    public List<ScheduledTask> list() {
        List<ScheduledTask> tasks = db.taskDao().findAll();
        LocalDateTime now = LocalDateTime.now();
        for (ScheduledTask t : tasks) {
            if (TaskStatus.READY.name().equals(t.getStatus())
                    && t.getEndAt() != null && t.getEndAt().isBefore(now)) {
                t.setStatus(TaskStatus.PAUSED.name());
                db.taskDao().update(t);
            }
        }
        return tasks;
    }

    /**
     * 保存任务并按状态联动调度：READY 注册 Quartz 作业，其他状态注销。
     */
    public void save(ScheduledTask task) {
        validateSchedule(task);
        if (task.getStatus() == null) {
            task.setStatus(TaskStatus.READY.name());
        }
        // 指定时刻的一次性任务强制 once（触发后置 FINISHED，避免重启反复补跑）
        if (task.getRunAt() != null) {
            task.setOnce(EnabledStatus.ENABLED);
        } else if (task.getOnce() == null) {
            task.setOnce(EnabledStatus.DISABLED);
        }
        if (task.getOutputMode() == null || task.getOutputMode().isBlank()) {
            task.setOutputMode(TaskOutputMode.MERGED.name());
        }
        if (task.getUnattended() == null) {
            task.setUnattended(EnabledStatus.DISABLED);
        }
        boolean exists = db.taskDao().findByCode(task.getTaskCode()).isPresent();
        if (exists) {
            db.taskDao().update(task);
        } else {
            db.taskDao().insert(task);
        }
        syncSchedule(task);
    }

    /** 删除任务：先摘除调度（失败仅告警）再删任务与其执行历史。 */
    public void delete(String taskCode) {
        try {
            schedulerService.unschedule(taskCode);
        } catch (Exception e) {
            log.warn("注销调度失败: {}", taskCode, e);
        }
        db.taskDao().deleteByCode(taskCode);
        db.taskRunDao().deleteByCode(taskCode);
    }

    /** 某任务的执行历史（时间倒序）。 */
    public List<TaskRun> history(String taskCode) {
        return db.taskRunDao().findByCode(taskCode);
    }

    /** 全库最近执行记录（时间倒序），供「执行记录」页签展示。 */
    public List<TaskRun> recentRuns(int limit) {
        return db.taskRunDao().findAllRecent(limit);
    }

    /** 暂停/恢复；已到期任务拒绝恢复（需先改到期时间再恢复）。 */
    public void setStatus(String taskCode, TaskStatus status) {
        db.taskDao().findByCode(taskCode).ifPresent(t -> {
            if (status == TaskStatus.READY && isExpired(t)) {
                throw new IllegalStateException(I18n.t("tasks.err.expiredResume"));
            }
            t.setStatus(status.name());
            db.taskDao().update(t);
            syncSchedule(t);
        });
    }

    /**
     * 手动立即执行一次（异步，不等待结果；执行语义、历史记录与完成通知与调度触发一致）。
     * <p>已有一轮在跑时不再提交第二轮：合并输出模式下两轮共用 {@code task-{code}} 会话，
     * 并发落盘会把历史写乱。</p>
     *
     * @return 是否已提交；{@code false} 表示任务不存在或上一轮尚未结束
     */
    public boolean runNow(String taskCode) {
        if (taskCode == null || db.taskDao().findByCode(taskCode).isEmpty()) {
            return false;
        }
        if (agentTaskExecutor.isRunning(taskCode)) {
            log.info("任务上一轮尚未结束，忽略重复的立即执行: {}", taskCode);
            return false;
        }
        Thread t = new Thread(() -> agentTaskExecutor.run(taskCode, null, false, null),
                "task-run-" + taskCode);
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** 执行记录对应的会话是否已存在（存在才可「查看会话」）。 */
    public boolean sessionExists(String sessionId) {
        return sessionId != null && !sessionId.isBlank()
                && db.sessionDao().findBySessionId(sessionId).isPresent();
    }

    /** 任务是否已到期。 */
    public static boolean isExpired(ScheduledTask task) {
        return task.getEndAt() != null && task.getEndAt().isBefore(LocalDateTime.now());
    }

    private void syncSchedule(ScheduledTask task) {
        try {
            if (TaskStatus.READY.name().equals(task.getStatus())) {
                schedulerService.schedule(task);
            } else {
                schedulerService.unschedule(task.getTaskCode());
            }
        } catch (Exception e) {
            throw new IllegalStateException(I18n.t("tasks.err.schedule", e.getMessage()), e);
        }
    }

    private static void validateCron(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("cron 表达式不能为空");
        }
        try {
            new CronExpression(cron.trim());
        } catch (ParseException e) {
            throw new IllegalArgumentException("非法 cron 表达式: " + cron);
        }
    }

    /** 校验触发定义：cron 与 run_at 至少居其一且互斥；cron 非空则必须合法；到期时间必须晚于当前。 */
    private static void validateSchedule(ScheduledTask task) {
        boolean hasCron = task.getCronExpression() != null && !task.getCronExpression().isBlank();
        boolean hasRunAt = task.getRunAt() != null;
        if (hasCron && hasRunAt) {
            throw new IllegalArgumentException("cron 与一次性时刻不能同时设置");
        }
        if (!hasCron && !hasRunAt) {
            throw new IllegalArgumentException("必须提供 cron 表达式或一次性时刻");
        }
        if (hasCron) {
            validateCron(task.getCronExpression());
        }
        if (task.getEndAt() != null) {
            if (!task.getEndAt().isAfter(LocalDateTime.now())) {
                throw new IllegalArgumentException("到期时间必须晚于当前时间");
            }
            if (hasRunAt && task.getEndAt().isBefore(task.getRunAt())) {
                throw new IllegalArgumentException("到期时间早于执行时刻，任务将不会执行");
            }
        }
    }
}
