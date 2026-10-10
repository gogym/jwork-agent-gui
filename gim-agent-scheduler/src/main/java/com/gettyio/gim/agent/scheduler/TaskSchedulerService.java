package com.gettyio.gim.agent.scheduler;

import com.gettyio.gim.agent.domain.dao.ScheduledTaskDao;
import com.gettyio.gim.agent.domain.entity.ScheduledTask;
import com.gettyio.gim.agent.domain.enums.TaskStatus;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * 任务调度服务：把 SQLite 任务表映射为 Quartz 作业，并支持启动时恢复。
 * <p>两类触发器：周期性（cron）与一次性指定时刻（run_at）。misfire 语义：
 * 周期任务关机期间错过的不补（DoNothing），一次性任务重启后补跑一次（FireAndProceed）。
 * 到期时间（end_at）非空时由触发器 endAt 自动截止，恢复时已过期任务直接置为暂停。</p>
 */
public class TaskSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(TaskSchedulerService.class);

    private static final String JOB_GROUP = "gim-agent-tasks";

    private final Scheduler scheduler;
    private final ScheduledTaskDao taskDao;

    public TaskSchedulerService(Scheduler scheduler, ScheduledTaskDao taskDao) {
        this.scheduler = scheduler;
        this.taskDao = taskDao;
    }

    /**
     * 启动时恢复所有 READY 任务；已过期（end_at 早于当前）的任务不恢复，直接置为 PAUSED。
     */
    public void resumeReadyTasks() {
        List<ScheduledTask> tasks = taskDao.findByStatus(TaskStatus.READY.name());
        int recovered = 0;
        for (ScheduledTask task : tasks) {
            if (isExpired(task)) {
                task.setStatus(TaskStatus.PAUSED.name());
                taskDao.update(task);
                log.info("任务已到期，跳过恢复并置为暂停: {}", task.getTaskCode());
                continue;
            }
            try {
                schedule(task);
                recovered++;
            } catch (Exception e) {
                log.error("恢复任务失败: {}", task.getTaskCode(), e);
            }
        }
        log.info("已恢复 {} 个定时任务", recovered);
    }

    private static boolean isExpired(ScheduledTask task) {
        return task.getEndAt() != null && task.getEndAt().isBefore(LocalDateTime.now());
    }

    public void schedule(ScheduledTask task) throws SchedulerException {
        unschedule(task.getTaskCode());
        JobDetail job = JobBuilder.newJob(AgentTaskJob.class)
                .withIdentity(jobKey(task.getTaskCode()))
                .usingJobData(AgentTaskJob.DATA_TASK_CODE, task.getTaskCode())
                .build();

        TriggerBuilder<Trigger> tb = TriggerBuilder.newTrigger()
                .withIdentity(task.getTaskCode(), JOB_GROUP);
        // 到期时间：非空时触发器自动截止，过期后不再触发（状态由恢复扫描/列表扫描置为暂停）
        if (task.getEndAt() != null) {
            tb.endAt(Date.from(task.getEndAt().atZone(ZoneId.systemDefault()).toInstant()));
        }
        String cron = task.getCronExpression() == null ? null : task.getCronExpression().trim();
        boolean hasCron = cron != null && !cron.isBlank();

        if (task.getRunAt() != null) {
            // 一次性指定时刻：SimpleTrigger 触发一次；错过（关机）则重启后补跑一次
            Date fireAt = Date.from(task.getRunAt().atZone(ZoneId.systemDefault()).toInstant());
            tb.startAt(fireAt)
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withRepeatCount(0)
                            .withMisfireHandlingInstructionFireNow());
        } else if (hasCron) {
            // 周期任务：关机期间错过的不补，等下一个正常触发点
            tb.withSchedule(CronScheduleBuilder.cronSchedule(cron)
                    .withMisfireHandlingInstructionDoNothing());
        } else {
            throw new SchedulerException("任务缺少触发定义（cron 与 run_at 至少居其一）: " + task.getTaskCode());
        }

        Trigger trigger = tb.build();
        scheduler.scheduleJob(job, trigger);
        log.info("已调度任务: {} {}", task.getTaskCode(),
                task.getRunAt() != null ? "runAt=" + task.getRunAt() : "cron=" + cron);
    }

    public void unschedule(String taskCode) throws SchedulerException {
        Set<org.quartz.JobKey> keys = scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP));
        for (org.quartz.JobKey key : keys) {
            if (key.getName().equals(taskCode)) {
                scheduler.deleteJob(key);
            }
        }
    }

    private static org.quartz.JobKey jobKey(String taskCode) {
        return org.quartz.JobKey.jobKey(taskCode, JOB_GROUP);
    }
}
