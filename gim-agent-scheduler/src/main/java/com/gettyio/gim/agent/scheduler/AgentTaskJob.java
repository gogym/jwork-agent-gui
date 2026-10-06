package com.gettyio.gim.agent.scheduler;

import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.core.task.TaskCompletedEvent;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.dao.ScheduledTaskDao;
import com.gettyio.gim.agent.domain.dao.TaskRunDao;
import com.gettyio.gim.agent.domain.entity.ScheduledTask;
import com.gettyio.gim.agent.domain.entity.TaskRun;
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
 * <p>每次触发（成功或失败）都会写一条 {@code task_runs} 执行历史，并按任务滚动保留最近若干条。</p>
 */
public class AgentTaskJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskJob.class);

    public static final String DATA_TASK_CODE = "taskCode";
    public static final String DATA_SESSION_ID = "sessionId";
    public static final String DATA_PROMPT = "prompt";

    private static final int SUMMARY_MAX_LEN = 120;
    private static final int ERROR_MAX_LEN = 500;

    @Autowired
    private ChatService chatService;
    @Autowired
    private GimDatabase db;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        ScheduledTaskDao taskDao = db.taskDao();
        String taskCode = context.getMergedJobDataMap().getString(DATA_TASK_CODE);
        String sessionId = context.getMergedJobDataMap().getString(DATA_SESSION_ID);
        String prompt = context.getMergedJobDataMap().getString(DATA_PROMPT);
        log.info("定时任务触发: task={}, session={}", taskCode, sessionId);

        LocalDateTime startedAt = fireTimeOrDefault(context);
        String taskName = resolveTaskName(taskCode);
        try {
            String reply = chatService.proactiveRun(sessionId, resolveAgentCode(taskCode), prompt);
            LocalDateTime finishedAt = LocalDateTime.now();
            taskDao.markRun(taskCode, finishedAt, toLocalDateTime(context.getNextFireTime()));
            recordRun(taskCode, sessionId, startedAt, finishedAt, true, summarize(reply), null);
            publish(new TaskCompletedEvent(taskCode, taskName, sessionId, summarize(reply), true));
            log.info("定时任务完成: task={}, replyLength={}", taskCode, reply == null ? 0 : reply.length());
        } catch (Exception e) {
            LocalDateTime finishedAt = LocalDateTime.now();
            log.error("定时任务执行失败: task={}", taskCode, e);
            recordRun(taskCode, sessionId, startedAt, finishedAt, false, null, trim(e.getMessage()));
            publish(new TaskCompletedEvent(taskCode, taskName, sessionId, "执行失败: " + e.getMessage(), false));
        } finally {
            finishIfOnce(taskCode);
        }
    }

    /** 写执行历史并滚动清理（失败不影响主流程）。 */
    private void recordRun(String taskCode, String sessionId, LocalDateTime startedAt,
                           LocalDateTime finishedAt, boolean success, String summary, String error) {
        try {
            TaskRunDao runDao = db.taskRunDao();
            TaskRun run = new TaskRun();
            run.setTaskCode(taskCode);
            run.setSessionId(sessionId);
            run.setStartedAt(startedAt);
            run.setFinishedAt(finishedAt);
            run.setSuccess(success);
            run.setSummary(summary);
            run.setError(error);
            runDao.insert(run);
            runDao.purge(taskCode);
        } catch (RuntimeException e) {
            log.warn("写入任务执行历史失败: {}", taskCode, e);
        }
    }

    private LocalDateTime fireTimeOrDefault(JobExecutionContext context) {
        LocalDateTime fired = toLocalDateTime(context.getFireTime());
        return fired == null ? LocalDateTime.now() : fired;
    }

    private String resolveTaskName(String taskCode) {
        return db.taskDao().findByCode(taskCode).map(ScheduledTask::getTaskName).orElse(taskCode);
    }

    /** 任务指定的智能体；未配置时回退内置默认智能体 */
    private String resolveAgentCode(String taskCode) {
        return db.taskDao().findByCode(taskCode)
                .map(ScheduledTask::getAgentCode)
                .filter(code -> !code.isBlank())
                .orElse(MultiAgentGateway.DEFAULT_AGENT_CODE);
    }

    /** 一次性任务执行后置为 FINISHED */
    private void finishIfOnce(String taskCode) {
        ScheduledTaskDao taskDao = db.taskDao();
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

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= ERROR_MAX_LEN ? s : s.substring(0, ERROR_MAX_LEN) + "…";
    }
}
