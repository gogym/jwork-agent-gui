package com.gettyio.gim.agent.scheduler;

import com.gettyio.gim.agent.domain.dao.ScheduledTaskDao;
import com.gettyio.gim.agent.domain.entity.ScheduledTask;
import com.gettyio.gim.agent.domain.enums.TaskStatus;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * 任务调度服务：把 SQLite 任务表映射为 Quartz 作业，并支持启动时恢复。
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
     * 启动时恢复所有 READY 任务
     */
    public void resumeReadyTasks() {
        List<ScheduledTask> tasks = taskDao.findByStatus(TaskStatus.READY.name());
        for (ScheduledTask task : tasks) {
            try {
                schedule(task);
            } catch (Exception e) {
                log.error("恢复任务失败: {}", task.getTaskCode(), e);
            }
        }
        log.info("已恢复 {} 个定时任务", tasks.size());
    }

    public void schedule(ScheduledTask task) throws SchedulerException {
        unschedule(task.getTaskCode());
        JobDetail job = JobBuilder.newJob(AgentTaskJob.class)
                .withIdentity(jobKey(task.getTaskCode()))
                .usingJobData(AgentTaskJob.DATA_TASK_CODE, task.getTaskCode())
                .usingJobData(AgentTaskJob.DATA_SESSION_ID, sessionIdFor(task))
                .usingJobData(AgentTaskJob.DATA_PROMPT, task.getPrompt())
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(task.getTaskCode(), JOB_GROUP)
                .withSchedule(CronScheduleBuilder.cronSchedule(task.getCronExpression()))
                .build();

        scheduler.scheduleJob(job, trigger);
        log.info("已调度任务: {} cron={}", task.getTaskCode(), task.getCronExpression());
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

    private static String sessionIdFor(ScheduledTask task) {
        return "task-" + task.getTaskCode();
    }
}
