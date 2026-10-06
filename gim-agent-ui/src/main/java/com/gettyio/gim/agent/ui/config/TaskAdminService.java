package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ScheduledTask;
import com.gettyio.gim.agent.domain.entity.TaskRun;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.enums.TaskStatus;
import com.gettyio.gim.agent.scheduler.TaskSchedulerService;
import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.util.List;

/**
 * 定时任务管理：任务表增删改查 + Quartz 调度联动 + 手动触发。
 */
public class TaskAdminService {

    private static final Logger log = LoggerFactory.getLogger(TaskAdminService.class);

    private final GimDatabase db;
    private final TaskSchedulerService schedulerService;
    private final ChatService chatService;

    public TaskAdminService(GimDatabase db, TaskSchedulerService schedulerService, ChatService chatService) {
        this.db = db;
        this.schedulerService = schedulerService;
        this.chatService = chatService;
    }

    public List<ScheduledTask> list() {
        return db.taskDao().findAll();
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
        boolean exists = db.taskDao().findByCode(task.getTaskCode()).isPresent();
        if (exists) {
            db.taskDao().update(task);
        } else {
            db.taskDao().insert(task);
        }
        syncSchedule(task);
    }

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

    /** 暂停/恢复 */
    public void setStatus(String taskCode, TaskStatus status) {
        db.taskDao().findByCode(taskCode).ifPresent(t -> {
            t.setStatus(status.name());
            db.taskDao().update(t);
            syncSchedule(t);
        });
    }

    /** 手动立即触发一次（异步，不等待结果） */
    public void runNow(ScheduledTask task) {
        String sessionId = "task-" + task.getTaskCode();
        Thread t = new Thread(() -> {
            try {
                chatService.proactiveRun(sessionId, task.getAgentCode(), task.getPrompt());
            } catch (Exception e) {
                log.error("手动触发任务失败: {}", task.getTaskCode(), e);
            }
        }, "task-run-" + task.getTaskCode());
        t.setDaemon(true);
        t.start();
    }

    private void syncSchedule(ScheduledTask task) {
        try {
            if (TaskStatus.READY.name().equals(task.getStatus())) {
                schedulerService.schedule(task);
            } else {
                schedulerService.unschedule(task.getTaskCode());
            }
        } catch (Exception e) {
            throw new IllegalStateException("调度失败: " + e.getMessage(), e);
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

    /** 校验触发定义：cron 与 run_at 至少居其一；cron 非空则必须合法。二者不可同时提供。 */
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
    }
}
