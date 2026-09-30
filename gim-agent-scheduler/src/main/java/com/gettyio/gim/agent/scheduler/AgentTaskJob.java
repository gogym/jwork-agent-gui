package com.gettyio.gim.agent.scheduler;

import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.core.task.TaskCompletedEvent;
import com.gettyio.gim.agent.domain.dao.ScheduledTaskDao;
import com.gettyio.gim.agent.domain.entity.ScheduledTask;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.enums.TaskStatus;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.quartz.QuartzJobBean;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 主动消息任务作业（AgentProactiveMessage 语义迁移）：
 * 到点以任务指令作为输入投递给 Agent，应答写入会话，并发布完成事件供 UI 通知。
 */
public class AgentTaskJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskJob.class);

    public static final String DATA_TASK_CODE = "taskCode";
    public static final String DATA_SESSION_ID = "sessionId";
    public static final String DATA_PROMPT = "prompt";

    private static final int SUMMARY_MAX_LEN = 120;

    @Autowired
    private ChatService chatService;
    @Autowired
    private ScheduledTaskDao taskDao;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        String taskCode = context.getMergedJobDataMap().getString(DATA_TASK_CODE);
        String sessionId = context.getMergedJobDataMap().getString(DATA_SESSION_ID);
        String prompt = context.getMergedJobDataMap().getString(DATA_PROMPT);
        log.info("定时任务触发: task={}, session={}", taskCode, sessionId);

        String taskName = resolveTaskName(taskCode);
        try {
            String reply = chatService.proactiveRun(sessionId, resolveAgentCode(taskCode), prompt);
            LocalDateTime runAt = LocalDateTime.now();
            taskDao.markRun(taskCode, runAt, toLocalDateTime(context.getNextFireTime()));
            publish(new TaskCompletedEvent(taskCode, taskName, sessionId, summarize(reply), true));
            log.info("定时任务完成: task={}, replyLength={}", taskCode, reply == null ? 0 : reply.length());
        } catch (Exception e) {
            log.error("定时任务执行失败: task={}", taskCode, e);
            publish(new TaskCompletedEvent(taskCode, taskName, sessionId, "执行失败: " + e.getMessage(), false));
        } finally {
            finishIfOnce(taskCode);
        }
    }

    private String resolveTaskName(String taskCode) {
        return taskDao.findByCode(taskCode).map(ScheduledTask::getTaskName).orElse(taskCode);
    }

    /** 任务指定的智能体；未配置时回退内置默认智能体 */
    private String resolveAgentCode(String taskCode) {
        return taskDao.findByCode(taskCode)
                .map(ScheduledTask::getAgentCode)
                .filter(code -> !code.isBlank())
                .orElse(MultiAgentGateway.DEFAULT_AGENT_CODE);
    }

    /** 一次性任务执行后置为 FINISHED */
    private void finishIfOnce(String taskCode) {
        Optional<ScheduledTask> opt = taskDao.findByCode(taskCode);
        if (opt.isPresent() && EnabledStatus.isEnabled(opt.get().getOnce())) {
            ScheduledTask t = opt.get();
            t.setStatus(TaskStatus.FINISHED.name());
            taskDao.update(t);
            log.info("一次性任务已完成: {}", taskCode);
        }
    }

    private void publish(TaskCompletedEvent event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            log.warn("发布任务完成事件失败: {}", event.getTaskCode(), e);
        }
    }

    private static LocalDateTime toLocalDateTime(java.util.Date date) {
        return date == null ? null
                : date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }

    private static String summarize(String reply) {
        if (reply == null || reply.isBlank()) {
            return "（无应答内容）";
        }
        String t = reply.trim();
        return t.length() <= SUMMARY_MAX_LEN ? t : t.substring(0, SUMMARY_MAX_LEN) + "…";
    }
}
