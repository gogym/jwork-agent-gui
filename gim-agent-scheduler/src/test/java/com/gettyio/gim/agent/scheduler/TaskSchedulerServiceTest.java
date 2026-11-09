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

import com.gettyio.gim.agent.domain.dao.ScheduledTaskDao;
import com.gettyio.gim.agent.domain.entity.ScheduledTask;
import com.gettyio.gim.agent.domain.enums.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TaskSchedulerService} 单测：cron/run_at 触发器选型与 misfire 语义、缺失触发定义报错、取消与恢复。
 * <p>用 RAMJobStore 内存调度器，不依赖 DB；触发时点均置未来，测试只检视调度配置不等待触发。</p>
 *
 * @author gogym
 */
class TaskSchedulerServiceTest {

    private static final String JOB_GROUP = "gim-agent-tasks";

    private Scheduler scheduler;
    private ScheduledTaskDao taskDao;
    private TaskSchedulerService service;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "TestScheduler-" + System.nanoTime());
        props.setProperty("org.quartz.scheduler.instanceId", "NON_CLUSTERED");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        scheduler = new StdSchedulerFactory(props).getScheduler();
        scheduler.start();
        taskDao = mock(ScheduledTaskDao.class);
        service = new TaskSchedulerService(scheduler, taskDao);
    }

    @AfterEach
    void tearDown() throws Exception {
        scheduler.shutdown(true);
    }

    private static ScheduledTask task(String code) {
        ScheduledTask t = new ScheduledTask();
        t.setTaskCode(code);
        t.setTaskName("任务-" + code);
        t.setAgentCode("gim-agent");
        t.setPrompt("做点什么");
        t.setStatus(TaskStatus.READY.name());
        t.setOnce(0);
        return t;
    }

    private Trigger triggerOf(String code) throws SchedulerException {
        return scheduler.getTrigger(new TriggerKey(code, JOB_GROUP));
    }

    @Test
    @DisplayName("cron 任务映射为 CronTrigger 且 misfire=DoNothing（错过不补）")
    void cronUsesDoNothing() throws Exception {
        ScheduledTask t = task("c1");
        t.setCronExpression("0 0 9 * * ?");
        service.schedule(t);
        Trigger trigger = triggerOf("c1");
        assertInstanceOf(CronTrigger.class, trigger);
        assertEquals(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING, trigger.getMisfireInstruction());
    }

    @Test
    @DisplayName("run_at 一次性任务映射为 SimpleTrigger 且 misfire=FireNow（重启补跑）")
    void runAtUsesFireNow() throws Exception {
        ScheduledTask t = task("r1");
        t.setRunAt(LocalDateTime.now().plusDays(1));
        t.setOnce(1);
        service.schedule(t);
        Trigger trigger = triggerOf("r1");
        assertInstanceOf(SimpleTrigger.class, trigger);
        assertEquals(SimpleTrigger.MISFIRE_INSTRUCTION_FIRE_NOW, trigger.getMisfireInstruction());
    }

    @Test
    @DisplayName("run_at 优先于 cron（二者同存走一次性分支）")
    void runAtTakesPrecedence() throws Exception {
        ScheduledTask t = task("p1");
        t.setCronExpression("0 0 9 * * ?");
        t.setRunAt(LocalDateTime.now().plusDays(1));
        service.schedule(t);
        assertInstanceOf(SimpleTrigger.class, triggerOf("p1"));
    }

    @Test
    @DisplayName("cron 与 run_at 均缺失时抛 SchedulerException")
    void missingTriggerDefinitionThrows() {
        ScheduledTask t = task("none");
        assertThrows(SchedulerException.class, () -> service.schedule(t));
    }

    @Test
    @DisplayName("unschedule 移除已注册作业")
    void unscheduleRemovesJob() throws Exception {
        ScheduledTask t = task("u1");
        t.setCronExpression("0 0 9 * * ?");
        service.schedule(t);
        assertTrue(scheduler.checkExists(org.quartz.JobKey.jobKey("u1", JOB_GROUP)));
        service.unschedule("u1");
        assertTrue(!scheduler.checkExists(org.quartz.JobKey.jobKey("u1", JOB_GROUP)));
    }

    @Test
    @DisplayName("unschedule 只摘同名作业：其他任务不受影响，未注册的编码为无操作")
    void unscheduleAffectsOnlyNamedJob() throws Exception {
        ScheduledTask keep = task("keep1");
        keep.setCronExpression("0 0 9 * * ?");
        ScheduledTask drop = task("drop1");
        drop.setCronExpression("0 0 10 * * ?");
        service.schedule(keep);
        service.schedule(drop);

        service.unschedule("drop1");
        service.unschedule("never-registered");

        assertTrue(!scheduler.checkExists(org.quartz.JobKey.jobKey("drop1", JOB_GROUP)));
        assertInstanceOf(CronTrigger.class, triggerOf("keep1"));
    }

    @Test
    @DisplayName("unscheduleQuietly 吞掉调度器异常：下线钩子是 Consumer，不能往外抛")
    void unscheduleQuietlySwallowsSchedulerFailure() throws Exception {
        Scheduler broken = mock(Scheduler.class);
        when(broken.deleteJob(any(org.quartz.JobKey.class))).thenThrow(new SchedulerException("调度器已关"));

        assertDoesNotThrow(() -> new TaskSchedulerService(broken, taskDao).unscheduleQuietly("z1"));
    }

    @Test
    @DisplayName("resumeReadyTasks 逐个调度 READY 任务")
    void resumeSchedulesReadyTasks() throws Exception {
        ScheduledTask a = task("a1");
        a.setCronExpression("0 0 8 * * ?");
        ScheduledTask b = task("b1");
        b.setRunAt(LocalDateTime.now().plusDays(2));
        when(taskDao.findByStatus(eq(TaskStatus.READY.name()))).thenReturn(List.of(a, b));

        service.resumeReadyTasks();

        assertInstanceOf(CronTrigger.class, triggerOf("a1"));
        assertInstanceOf(SimpleTrigger.class, triggerOf("b1"));
    }

    @Test
    @DisplayName("恢复容错：单个坏任务（缺触发定义）不阻断其余任务恢复")
    void resumeSkipsBrokenTask() throws Exception {
        ScheduledTask good = task("g1");
        good.setCronExpression("0 0 7 * * ?");
        ScheduledTask broken = task("x1"); // cron 与 run_at 均缺失
        when(taskDao.findByStatus(eq(TaskStatus.READY.name()))).thenReturn(List.of(broken, good));

        service.resumeReadyTasks(); // 不应抛出

        assertTrue(!scheduler.checkExists(org.quartz.JobKey.jobKey("x1", JOB_GROUP)));
        assertInstanceOf(CronTrigger.class, triggerOf("g1"));
    }

    @Test
    @DisplayName("run_at 为过去时点：调度成功且维持 FireNow 补跑语义")
    void runAtInThePastSchedules() throws Exception {
        ScheduledTask t = task("past1");
        t.setRunAt(LocalDateTime.now().minusHours(1));
        service.schedule(t);
        Trigger trigger = triggerOf("past1");
        assertInstanceOf(SimpleTrigger.class, trigger);
        assertEquals(SimpleTrigger.MISFIRE_INSTRUCTION_FIRE_NOW, trigger.getMisfireInstruction());
    }

    @Test
    @DisplayName("重复调度同一 taskCode：先删旧再建新，触发器随最新定义更新")
    void rescheduleReplacesExisting() throws Exception {
        ScheduledTask t = task("dup1");
        t.setCronExpression("0 0 9 * * ?");
        service.schedule(t);
        assertInstanceOf(CronTrigger.class, triggerOf("dup1"));

        t.setCronExpression(null);
        t.setRunAt(LocalDateTime.now().plusDays(1));
        service.schedule(t); // 不应因旧作业存在而抛异常
        assertInstanceOf(SimpleTrigger.class, triggerOf("dup1"));
    }
}
