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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 自动化任务执行器：Quartz 调度触发与手动「立即执行」共用的执行语义。
 * <p>按任务定义派生会话（合并输出固定 {@code task-{code}} 复用同一会话；独立输出按执行时刻
 * 派发 {@code task-{code}-yyyyMMdd-HHmmss} 新会话）、按无人值守开关映射工具授权档位
 * （开启 → ACCEPT_EDITS + 自动放行；关闭 → EXPLORE 只读）、按模型覆盖透传提供方与模型。
 * 每次执行（成功或失败）写一条 {@code task_runs} 并经 {@link TaskCompletedEvent} 回调通知 UI。</p>
 * <p>生命周期收口三件事，都只在<b>调度触发</b>路径生效（手动触发是用户明确要跑，一律放行）：
 * 状态非 READY 或已过到期时间的任务不再执行并顺手摘掉残留作业；一次性任务跑完即注销作业；
 * 连续失败到阈值置 {@link TaskStatus#FAILED} 并注销，免得一个坏任务按周期反复烧模型调用。</p>
 * <p>同一任务不重入：{@code @DisallowConcurrentExecution} 只管得住 Quartz 侧，手动触发走的是
 * 自己起的线程，两边叠起来仍会共用同一会话并发落盘，故执行器自带在跑集合兜底。</p>
 *
 * @author gogym
 */
public class AgentTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskExecutor.class);

    private static final int SUMMARY_MAX_LEN = 120;
    private static final int ERROR_MAX_LEN = 500;

    /** 连续失败停用阈值：达到即置 {@link TaskStatus#FAILED} 并注销作业，等用户在设置页处理。 */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** 独立输出模式的新会话后缀格式 */
    private static final DateTimeFormatter SEPARATE_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final GimDatabase db;
    private final ChatService chatService;
    /** 任务完成回调（组合根接系统通知；替代原 Spring 事件发布器）。 */
    private final Consumer<TaskCompletedEvent> onTaskCompleted;
    /** 正在执行的任务编码：同任务不重入（手动线程与调度线程共用本集合）。 */
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    /** 下线钩子：从调度器摘除作业，由组合根注入；未注入时只改状态，不影响单测。 */
    private volatile Consumer<String> unscheduleHook = taskCode -> {
    };

    public AgentTaskExecutor(GimDatabase db, ChatService chatService,
                             Consumer<TaskCompletedEvent> onTaskCompleted) {
        this.db = db;
        this.chatService = chatService;
        this.onTaskCompleted = onTaskCompleted;
    }

    /** 注入任务下线钩子（Quartz 注销）；传 {@code null} 表示不需要。 */
    public void setUnscheduleHook(Consumer<String> hook) {
        this.unscheduleHook = hook == null ? taskCode -> {
        } : hook;
    }

    /** 该任务此刻是否正在执行（手动触发的前置检查，用于给用户「正在执行中」的回执）。 */
    public boolean isRunning(String taskCode) {
        return taskCode != null && running.contains(taskCode);
    }

    /**
     * 执行一次任务。
     *
     * @param taskCode     任务编码
     * @param startedAt    开始时刻（调度触发取 Quartz 触发时间；空取当前时刻）
     * @param scheduled    是否调度触发：调度触发更新最近/下次运行时间并完结一次性任务，手动触发只记最近运行时间
     * @param nextFireTime 调度触发时的下次触发时刻（手动触发传 null）
     * @return 执行结果；任务已不存在、状态不允许执行或上一轮仍在跑时返回 null
     */
    public Outcome run(String taskCode, LocalDateTime startedAt, boolean scheduled, LocalDateTime nextFireTime) {
        ScheduledTask task = db.taskDao().findByCode(taskCode).orElse(null);
        if (task == null) {
            log.warn("自动化任务已不存在，跳过执行: {}", taskCode);
            return null;
        }
        if (!running.add(taskCode)) {
            log.warn("自动化任务上一轮仍未结束，跳过本次{}触发: {}", scheduled ? "调度" : "手动", taskCode);
            return null;
        }
        try {
            return doRun(task, startedAt, scheduled, nextFireTime);
        } finally {
            running.remove(taskCode);
        }
    }

    private Outcome doRun(ScheduledTask task, LocalDateTime startedAt, boolean scheduled,
                          LocalDateTime nextFireTime) {
        ScheduledTaskDao taskDao = db.taskDao();
        String taskCode = task.getTaskCode();
        LocalDateTime started = startedAt == null ? LocalDateTime.now() : startedAt;
        // 调度触发但库里已不是 READY（用户在设置页暂停过、一次性任务已消费、或已停用）：
        // 照跑等于无视用户的操作，这里顺手把残留作业摘掉
        if (scheduled && !TaskStatus.READY.name().equals(task.getStatus())) {
            log.info("任务状态为 {}，跳过本次调度并注销作业: {}", task.getStatus(), taskCode);
            retire(taskCode, task.getStatus(), "调度触发时状态非就绪");
            return null;
        }
        if (scheduled && isExpired(task)) {
            log.info("任务已过到期时间，跳过本次调度并置为暂停: {}", taskCode);
            retire(taskCode, TaskStatus.PAUSED.name(), "已过到期时间");
            return null;
        }
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
            finishOnce(taskCode);
        } else {
            taskDao.markLastRun(taskCode, finishedAt);
        }
        recordRun(taskCode, sessionId, started, finishedAt, success, summary, error);
        // 阈值判定要看含本轮在内的最近几条，必须排在 recordRun 之后
        if (scheduled && !success) {
            parkIfRepeatedFailure(taskCode);
        }
        publish(new TaskCompletedEvent(taskCode, task.getTaskName(), sessionId,
                success ? summary : "执行失败: " + error, success));
        return new Outcome(taskCode, task.getTaskName(), sessionId, success, summary, error, started, finishedAt);
    }

    /** 任务是否已过到期时间。 */
    private static boolean isExpired(ScheduledTask task) {
        return task.getEndAt() != null && task.getEndAt().isBefore(LocalDateTime.now());
    }

    /**
     * 停用任务：写状态（可选）并从调度器摘除作业。
     * <p>两步各自吞异常：注销失败不该把执行结果盖掉，作业残留也有下一次
     * {@link #run} 的状态检查兜着。</p>
     *
     * @param status 要写入的状态；与当前一致（如已是 PAUSED）时传同名，落库幂等
     */
    private void retire(String taskCode, String status, String reason) {
        try {
            // 重读再回写：整行 update 会带上 next_run_at，用内存里的旧值会把刚写的下次触发时间抹掉
            db.taskDao().findByCode(taskCode).ifPresent(t -> {
                t.setStatus(status);
                db.taskDao().update(t);
            });
        } catch (RuntimeException e) {
            log.warn("任务状态写入失败: {} -> {}", taskCode, status, e);
        }
        try {
            unscheduleHook.accept(taskCode);
        } catch (RuntimeException e) {
            log.warn("任务注销调度失败: {}", taskCode, e);
        }
        log.info("任务已停用: {} -> {}（{}）", taskCode, status, reason);
    }

    /** 连续 {@value #MAX_CONSECUTIVE_FAILURES} 次调度失败后停用为 FAILED（手动失败不计，用户就在旁边看着）。 */
    private void parkIfRepeatedFailure(String taskCode) {
        List<TaskRun> recent;
        try {
            recent = db.taskRunDao().findRecentByCode(taskCode, MAX_CONSECUTIVE_FAILURES);
        } catch (RuntimeException e) {
            log.warn("读取执行历史失败，跳过连续失败检查: {}", taskCode, e);
            return;
        }
        if (recent.size() < MAX_CONSECUTIVE_FAILURES || recent.stream().anyMatch(TaskRun::isSuccess)) {
            return;
        }
        retire(taskCode, TaskStatus.FAILED.name(), "连续 " + MAX_CONSECUTIVE_FAILURES + " 次执行失败");
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

    /** 一次性任务跑完即收工：置 FINISHED 并注销作业——cron+once 的组合否则会把重复触发器留在调度器里。 */
    private void finishOnce(String taskCode) {
        db.taskDao().findByCode(taskCode)
                .filter(t -> EnabledStatus.isEnabled(t.getOnce()))
                .ifPresent(t -> retire(taskCode, TaskStatus.FINISHED.name(), "一次性任务已消费"));
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
            onTaskCompleted.accept(event);
        } catch (Exception e) {
            log.warn("任务完成回调失败: {}", event.getTaskCode(), e);
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
