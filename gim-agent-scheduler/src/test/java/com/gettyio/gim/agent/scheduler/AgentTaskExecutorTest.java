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
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ScheduledTask;
import com.gettyio.gim.agent.domain.enums.TaskStatus;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import io.agentscope.core.permission.PermissionMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentTaskExecutor} 的生命周期单测：调度触发时状态/到期闸门、一次性任务消费即注销、
 * 连续失败停用、同任务不重入，以及手动触发不受状态闸门约束。
 * <p>与 {@code AgentTaskJobTest} 同一套桩：真临时 SQLite + mock {@link ChatService}，
 * 下线钩子换成记录编码的列表，便于断言「哪些任务被摘掉了作业」。</p>
 *
 * @author gogym
 */
class AgentTaskExecutorTest {

    private static final String CRON = "0 0 9 * * ?";

    @TempDir
    Path tempDir;

    private GimDatabase db;
    private ChatService chatService;
    private AgentTaskExecutor executor;
    private List<String> retired;

    @BeforeEach
    void setUp() throws Exception {
        db = new GimDatabase(new SqliteConnectionProvider(tempDir.resolve("gim.db")));
        db.initSchema();
        chatService = mock(ChatService.class);
        retired = new ArrayList<>();
        executor = new AgentTaskExecutor(db, chatService, event -> { });
        executor.setUnscheduleHook(retired::add);
    }

    private ScheduledTask seed(String code, TaskStatus status, String cron, LocalDateTime endAt, Integer once) {
        ScheduledTask t = new ScheduledTask();
        t.setTaskCode(code);
        t.setTaskName("任务-" + code);
        t.setAgentCode("gim-agent");
        t.setPrompt("汇报天气");
        t.setCronExpression(cron);
        t.setEndAt(endAt);
        t.setStatus(status.name());
        t.setOnce(once);
        db.taskDao().insert(t);
        return t;
    }

    /** 成功应答桩。 */
    private void stubSuccess(String reply) {
        when(chatService.proactiveRun(anyString(), anyString(), anyString(),
                any(PermissionMode.class), anyBoolean(), nullable(String.class), nullable(String.class)))
                .thenReturn(reply);
    }

    /** 失败应答桩。 */
    private void stubFailure() {
        when(chatService.proactiveRun(anyString(), anyString(), anyString(),
                any(PermissionMode.class), anyBoolean(), nullable(String.class), nullable(String.class)))
                .thenThrow(new RuntimeException("模型连接失败"));
    }

    @Test
    @DisplayName("调度触发但已暂停：不执行、不留历史，只注销残留作业")
    void pausedTaskIsSkippedOnScheduledFire() {
        seed("p1", TaskStatus.PAUSED, CRON, null, 0);
        stubSuccess("不该被调用");

        assertNull(executor.run("p1", null, true, LocalDateTime.now().plusDays(1)));

        verify(chatService, never()).proactiveRun(anyString(), anyString(), anyString(),
                any(PermissionMode.class), anyBoolean(), nullable(String.class), nullable(String.class));
        assertTrue(db.taskRunDao().findByCode("p1").isEmpty(), "跳过的触发不该留执行历史");
        assertEquals(List.of("p1"), retired, "残留作业应被注销");
        assertEquals(TaskStatus.PAUSED.name(), db.taskDao().findByCode("p1").orElseThrow().getStatus(),
                "暂停任务不该被改回其他状态");
    }

    @Test
    @DisplayName("调度触发但已过到期时间：置为暂停并注销作业")
    void expiredTaskIsPausedOnScheduledFire() {
        seed("e1", TaskStatus.READY, CRON, LocalDateTime.now().minusMinutes(1), 0);
        stubSuccess("不该被调用");

        assertNull(executor.run("e1", null, true, null));

        assertEquals(TaskStatus.PAUSED.name(), db.taskDao().findByCode("e1").orElseThrow().getStatus());
        assertEquals(List.of("e1"), retired);
    }

    @Test
    @DisplayName("一次性任务跑完：置 FINISHED 并注销作业（cron+once 的重复触发器不能留下）")
    void onceTaskFinishedAndUnscheduled() {
        seed("o1", TaskStatus.READY, CRON, null, 1);
        stubSuccess("done");

        assertNotNull(executor.run("o1", null, true, null));

        assertEquals(TaskStatus.FINISHED.name(), db.taskDao().findByCode("o1").orElseThrow().getStatus());
        assertEquals(List.of("o1"), retired);
    }

    @Test
    @DisplayName("连续 3 次调度失败后停用为 FAILED，之后不再触发模型")
    void repeatedFailuresParkTheTask() {
        seed("f1", TaskStatus.READY, CRON, null, 0);
        stubFailure();

        for (int i = 0; i < AgentTaskExecutor.MAX_CONSECUTIVE_FAILURES; i++) {
            assertNotNull(executor.run("f1", null, true, null), "第 " + (i + 1) + " 次应照常执行并记失败");
        }
        assertEquals(TaskStatus.FAILED.name(), db.taskDao().findByCode("f1").orElseThrow().getStatus());
        assertEquals(List.of("f1"), retired);

        assertNull(executor.run("f1", null, true, null), "停用后不该再触发");
        verify(chatService, times(AgentTaskExecutor.MAX_CONSECUTIVE_FAILURES))
                .proactiveRun(anyString(), anyString(), anyString(),
                        any(PermissionMode.class), anyBoolean(), nullable(String.class), nullable(String.class));
    }

    @Test
    @DisplayName("失败中途成功一次即清零：未到连续阈值就不停用")
    void successResetsFailureStreak() {
        seed("f2", TaskStatus.READY, CRON, null, 0);
        stubFailure();
        executor.run("f2", null, true, null);
        executor.run("f2", null, true, null);
        stubSuccess("恢复了");
        executor.run("f2", null, true, null);

        assertEquals(TaskStatus.READY.name(), db.taskDao().findByCode("f2").orElseThrow().getStatus());
        assertTrue(retired.isEmpty(), "未达阈值不该停用任务");
    }

    @Test
    @DisplayName("手动触发不看状态：用户点了就跑（暂停中的任务也允许试跑一次）")
    void manualRunIgnoresStatusGate() {
        seed("m1", TaskStatus.PAUSED, CRON, null, 0);
        stubSuccess("ok");

        AgentTaskExecutor.Outcome outcome = executor.run("m1", null, false, null);

        assertNotNull(outcome);
        assertTrue(outcome.success());
        assertTrue(retired.isEmpty(), "手动触发不该顺手改任务生命周期");
        assertEquals(TaskStatus.PAUSED.name(), db.taskDao().findByCode("m1").orElseThrow().getStatus(),
                "手动执行不消费一次性任务、不改状态");
    }

    @Test
    @DisplayName("同任务不重入：上一轮还在跑时第二次触发被拒，且不影响后续正常执行")
    void concurrentRunOfSameTaskIsRejected() throws Exception {
        seed("b1", TaskStatus.READY, CRON, null, 0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(chatService.proactiveRun(anyString(), anyString(), anyString(),
                any(PermissionMode.class), anyBoolean(), nullable(String.class), nullable(String.class)))
                .thenAnswer(inv -> {
                    entered.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return "ok";
                });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AgentTaskExecutor.Outcome> first = pool.submit(() -> executor.run("b1", null, false, null));
            assertTrue(entered.await(5, TimeUnit.SECONDS), "第一轮应已进入执行");
            assertTrue(executor.isRunning("b1"), "执行中应可见");

            assertNull(executor.run("b1", null, true, null), "重入的第二轮应被拒");
            assertNull(executor.run("b1", null, false, null), "手动重入同样被拒");

            release.countDown();
            assertNotNull(first.get(10, TimeUnit.SECONDS));
            assertFalse(executor.isRunning("b1"), "结束后应释放占位");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        verify(chatService, times(1)).proactiveRun(anyString(), anyString(), anyString(),
                any(PermissionMode.class), anyBoolean(), nullable(String.class), nullable(String.class));
    }

    @Test
    @DisplayName("任务已不存在：返回 null，不进执行、不碰钩子")
    void missingTaskReturnsNull() {
        assertNull(executor.run("gone", null, true, null));
        assertTrue(retired.isEmpty());
        verify(chatService, never()).proactiveRun(anyString(), anyString(), anyString(),
                any(PermissionMode.class), anyBoolean(), nullable(String.class), nullable(String.class));
    }

    @Test
    @DisplayName("下线钩子抛异常只记日志：不能盖掉已完成的执行结果")
    void hookFailureDoesNotBreakRun() {
        executor.setUnscheduleHook(code -> {
            throw new IllegalStateException("调度器已关");
        });
        seed("h1", TaskStatus.READY, CRON, null, 1);
        stubSuccess("ok");

        AgentTaskExecutor.Outcome outcome = executor.run("h1", null, true, null);

        assertNotNull(outcome);
        assertTrue(outcome.success());
        assertEquals(TaskStatus.FINISHED.name(), db.taskDao().findByCode("h1").orElseThrow().getStatus());
    }
}
