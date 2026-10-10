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
import com.gettyio.gim.agent.domain.enums.TaskOutputMode;
import com.gettyio.gim.agent.domain.enums.TaskStatus;
import io.agentscope.core.permission.PermissionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 自动化任务执行器：Quartz 调度触发与手动「立即执行」共用的执行语义。
 * <p>按任务定义派生会话（合并输出固定 {@code task-{code}} 复用同一会话；独立输出按执行时刻
 * 派发 {@code task-{code}-yyyyMMdd-HHmmss} 新会话）、按无人值守开关映射工具授权档位
 * （开启 → ACCEPT_EDITS + 自动放行；关闭 → EXPLORE 只读）、按模型覆盖透传提供方与模型。
 * 每次执行（成功或失败）写一条 {@code task_runs} 并发布 {@link TaskCompletedEvent} 供 UI 通知。</p>
 */
public class AgentTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskExecutor.class);

    private static final int SUMMARY_MAX_LEN = 120;
    private static final int ERROR_MAX_LEN = 500;

    /** 独立输出模式的新会话后缀格式 */
    private static final DateTimeFormatter SEPARATE_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final GimDatabase db;
    private final ChatService chatService;
    private final ApplicationEventPublisher eventPublisher;

    public AgentTaskExecutor(GimDatabase db, ChatService chatService, ApplicationEventPublisher eventPublisher) {
        this.db = db;
        this.chatService = chatService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 执行一次任务。
     *
     * @param taskCode     任务编码
     * @param startedAt    开始时刻（调度触发取 Quartz 触发时间；空取当前时刻）
     * @param scheduled    是否调度触发：调度触发更新最近/下次运行时间并完结一次性任务，手动触发只记最近运行时间
     * @param nextFireTime 调度触发时的下次触发时刻（手动触发传 null）
     * @return 执行结果；任务已不存在时返回 null
     */
    public Outcome run(String taskCode, LocalDateTime startedAt, boolean scheduled, LocalDateTime nextFireTime) {
        ScheduledTaskDao taskDao = db.taskDao();
        Optional<ScheduledTask> opt = taskDao.findByCode(taskCode);
        if (opt.isEmpty()) {
            log.warn("自动化任务已不存在，跳过执行: {}", taskCode);
            return null;
        }
        ScheduledTask task = opt.get();
        LocalDateTime started = startedAt == null ? LocalDateTime.now() : startedAt;
        String agentCode = resolveAgentCode(task);
        String sessionId = deriveSessionId(task);
        boolean unattended = EnabledStatus.isEnabled(task.getUnattended());
        // 无人值守：ACCEPT_EDITS + 自动放行（确认照常过拦截链，ASK 不弹窗）；否则走只读 EXPLORE
        PermissionMode mode = unattended ? PermissionMode.ACCEPT_EDITS : PermissionMode.EXPLORE;
        log.info("自动化任务执行: task={}, session={}, mode={}, unattended={}", taskCode, sessionId, mode, unattended);

        boolean success;
        String summary = null;
        String error = null;
        try {
            String reply = chatService.proactiveRun(sessionId, agentCode, task.getPrompt(), mode, unattended,
                    task.getProviderCode(), task.getModelCode());
            summary = summarize(reply);
            success = true;
            log.info("自动化任务完成: task={}, replyLength={}", taskCode, reply == null ? 0 : reply.length());
        } catch (Exception e) {
            error = trim(e.getMessage());
            success = false;
            log.error("自动化任务执行失败: task={}", taskCode, e);
        }
        LocalDateTime finishedAt = LocalDateTime.now();
        if (scheduled) {
            taskDao.markRun(taskCode, finishedAt, nextFireTime);
            finishIfOnce(taskDao, taskCode);
        } else {
            taskDao.markLastRun(taskCode, finishedAt);
        }
        recordRun(taskCode, sessionId, started, finishedAt, success, summary, error);
        publish(new TaskCompletedEvent(taskCode, task.getTaskName(), sessionId,
                success ? summary : "执行失败: " + error, success));
        return new Outcome(taskCode, task.getTaskName(), sessionId, success, summary, error, started, finishedAt);
    }

    /** 派发本次执行的会话：合并输出恒定复用同一会话，独立输出按执行时刻新建会话。 */
    private static String deriveSessionId(ScheduledTask task) {
        if (TaskOutputMode.fromCode(task.getOutputMode()) == TaskOutputMode.SEPARATE) {
            return "task-" + task.getTaskCode() + "-" + SEPARATE_SUFFIX.format(LocalDateTime.now());
        }
        return "task-" + task.getTaskCode();
    }

    /** 任务指定的智能体；未配置时回退内置默认智能体 */
    private static String resolveAgentCode(ScheduledTask task) {
        String code = task.getAgentCode();
        return (code == null || code.isBlank()) ? MultiAgentGateway.DEFAULT_AGENT_CODE : code;
    }

    /** 一次性任务执行后置为 FINISHED（重读实体，避免整行回写覆盖 markRun 刚写的 next_run_at）。 */
    private static void finishIfOnce(ScheduledTaskDao taskDao, String taskCode) {
        Optional<ScheduledTask> opt = taskDao.findByCode(taskCode);
        if (opt.isEmpty() || !EnabledStatus.isEnabled(opt.get().getOnce())) {
            return;
        }
        ScheduledTask t = opt.get();
        t.setStatus(TaskStatus.FINISHED.name());
        taskDao.update(t);
        log.info("一次性任务已完成: {}", taskCode);
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

    private void publish(TaskCompletedEvent event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            log.warn("发布任务完成事件失败: {}", event.getTaskCode(), e);
        }
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

    /** 一次执行的产出快照（供手动触发侧回执与测试断言）。 */
    public record Outcome(String taskCode, String taskName, String sessionId, boolean success,
                          String summary, String error, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }
}
