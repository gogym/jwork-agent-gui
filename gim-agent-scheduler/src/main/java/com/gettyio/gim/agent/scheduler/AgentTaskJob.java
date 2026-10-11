package com.gettyio.gim.agent.scheduler;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 自动化任务作业（Quartz 触发入口）：到点委托 {@link AgentTaskExecutor} 执行。
 * <p>执行语义（会话派生、授权档位、模型覆盖、执行历史、完成事件）全部由执行器承担，
 * 本类只负责从触发上下文取任务编码与触发/下次触发时刻。</p>
 * <p>执行器经 {@link AgentTaskJobFactory} 在实例化时注入（替代原 Spring 字段注入）。</p>
 */
public class AgentTaskJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskJob.class);

    public static final String DATA_TASK_CODE = "taskCode";

    private AgentTaskExecutor executor;

    /** Quartz 作业工厂在创建实例后注入（包级可见，仅装配方使用）。 */
    void setExecutor(AgentTaskExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void execute(JobExecutionContext context) {
        String taskCode = context.getMergedJobDataMap().getString(DATA_TASK_CODE);
        log.info("自动化任务触发: task={}", taskCode);
        try {
            executor.run(taskCode, toLocalDateTime(context.getFireTime()), true,
                    toLocalDateTime(context.getNextFireTime()));
        } catch (Exception e) {
            // 执行器内部已兜底记失败；这里只兜底极端异常，避免污染 Quartz 调度线程
            log.error("自动化任务执行异常: {}", taskCode, e);
        }
    }

    private static LocalDateTime toLocalDateTime(Date date) {
        return date == null ? null
                : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
