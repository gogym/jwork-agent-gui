package com.gettyio.gim.agent.scheduler;

import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ScheduledTask;
import com.gettyio.gim.agent.domain.entity.TaskRun;
import com.gettyio.gim.agent.domain.enums.TaskOutputMode;
import com.gettyio.gim.agent.domain.enums.TaskStatus;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import io.agentscope.core.permission.PermissionMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentTaskJob} 单测：成功与失败两路都写 {@code task_runs} 执行历史，字段随成败区分；
 * 另覆盖授权档位映射（无人值守 → ACCEPT_EDITS + 自动放行）与会话派生（独立输出按时刻新建）。
 * <p>{@code @Autowired} 私有字段以反射注入（无 Spring 上下文）；{@link JobExecutionContext} 用桩。</p>
 */
class AgentTaskJobTest {

    @TempDir
    Path tempDir;

    private GimDatabase db;
    private ChatService chatService;
    private ApplicationEventPublisher publisher;
    private AgentTaskJob job;

    @BeforeEach
    void setUp() throws Exception {
        db = new GimDatabase(new SqliteConnectionProvider(tempDir.resolve("gim.db")));
        db.initSchema();
        chatService = mock(ChatService.class);
        publisher = mock(ApplicationEventPublisher.class);

        job = new AgentTaskJob();
        inject("executor", new AgentTaskExecutor(db, chatService, publisher));
    }

    private void inject(String name, Object value) throws Exception {
        Field f = AgentTaskJob.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(job, value);
    }

    private static ScheduledTask newTask(String code, Integer once) {
        ScheduledTask t = new ScheduledTask();
        t.setTaskCode(code);
        t.setTaskName("任务-" + code);
        t.setAgentCode("gim-agent");
        t.setPrompt("汇报天气");
        t.setCronExpression("0 0 9 * * ?");
        t.setStatus(TaskStatus.READY.name());
        t.setOnce(once);
        return t;
    }

    private void seedTask(String code, Integer once) {
        db.taskDao().insert(newTask(code, once));
    }

    /** 伪造一次触发上下文：携带任务编码并给出触发与下次触发时间。 */
    private JobExecutionContext context(String code) {
        JobDataMap map = new JobDataMap();
        map.put(AgentTaskJob.DATA_TASK_CODE, code);
        JobExecutionContext ctx = mock(JobExecutionContext.class);
        when(ctx.getMergedJobDataMap()).thenReturn(map);
        when(ctx.getFireTime()).thenReturn(new Date());
        when(ctx.getNextFireTime()).thenReturn(new Date());
        return ctx;
    }

    /** 7 参 proactiveRun 通用桩：成功应答「今天晴」，授权档位/模型覆盖以 any 宽匹配。 */
    private void stubProactiveRun(String reply) {
        when(chatService.proactiveRun(anyString(), anyString(), anyString(),
                any(PermissionMode.class), anyBoolean(), nullable(String.class), nullable(String.class)))
                .thenReturn(reply);
    }

    @Test
    @DisplayName("成功：写 task_runs success=1，摘要取应答文本，会话与任务状态不回退")
    void successWritesRunHistory() {
        seedTask("ok1", 0);
        stubProactiveRun("今天晴");

        job.executeInternal(context("ok1"));

        List<TaskRun> runs = db.taskRunDao().findByCode("ok1");
        assertEquals(1, runs.size());
        TaskRun run = runs.get(0);
        assertTrue(run.isSuccess());
        assertEquals("今天晴", run.getSummary());
        assertNull(run.getError());
        assertEquals("task-ok1", run.getSessionId());
        assertNotNull(run.getStartedAt());
    }

    @Test
    @DisplayName("失败：proactiveRun 抛异常仍写 task_runs success=0 且记录 error")
    void failureWritesRunHistory() {
        seedTask("bad1", 0);
        when(chatService.proactiveRun(anyString(), anyString(), anyString(),
                any(PermissionMode.class), anyBoolean(), nullable(String.class), nullable(String.class)))
                .thenThrow(new RuntimeException("模型连接失败"));

        job.executeInternal(context("bad1"));

        List<TaskRun> runs = db.taskRunDao().findByCode("bad1");
        assertEquals(1, runs.size());
        TaskRun run = runs.get(0);
        assertFalse(run.isSuccess());
        assertNotNull(run.getError());
        assertTrue(run.getError().contains("模型连接失败"));
    }

    @Test
    @DisplayName("一次性任务成功执行后状态置 FINISHED")
    void onceTaskMarkedFinished() {
        seedTask("once1", 1);
        stubProactiveRun("done");

        job.executeInternal(context("once1"));

        assertEquals(TaskStatus.FINISHED.name(), db.taskDao().findByCode("once1").orElseThrow().getStatus());
    }

    @Test
    @DisplayName("无人值守：以 ACCEPT_EDITS + 自动放行执行，并透传模型覆盖")
    void unattendedRunsWithAcceptEdits() {
        ScheduledTask t = newTask("un1", 0);
        t.setUnattended(1);
        t.setProviderCode("p1");
        t.setModelCode("m1");
        db.taskDao().insert(t);

        when(chatService.proactiveRun(eq("task-un1"), eq("gim-agent"), anyString(),
                eq(PermissionMode.ACCEPT_EDITS), eq(true), eq("p1"), eq("m1")))
                .thenReturn("ok");

        job.executeInternal(context("un1"));

        verify(chatService).proactiveRun(eq("task-un1"), eq("gim-agent"), anyString(),
                eq(PermissionMode.ACCEPT_EDITS), eq(true), eq("p1"), eq("m1"));
    }

    @Test
    @DisplayName("独立输出：会话按执行时刻派生（task-{code}-时间戳），不复用固定会话")
    void separateOutputCreatesTimestampedSession() {
        ScheduledTask t = newTask("sep1", 0);
        t.setOutputMode(TaskOutputMode.SEPARATE.name());
        db.taskDao().insert(t);
        stubProactiveRun("ok");

        job.executeInternal(context("sep1"));

        List<TaskRun> runs = db.taskRunDao().findByCode("sep1");
        assertEquals(1, runs.size());
        String sessionId = runs.get(0).getSessionId();
        assertTrue(sessionId.startsWith("task-sep1-"), "独立输出应派生带时间戳的新会话: " + sessionId);
        assertTrue(sessionId.length() > "task-sep1-".length());
    }
}
