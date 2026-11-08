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

package com.gettyio.gim.agent.scheduler.tool;

import com.gettyio.gim.agent.domain.dao.ScheduledTaskDao;
import com.gettyio.gim.agent.domain.entity.ScheduledTask;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.enums.TaskStatus;
import com.gettyio.gim.agent.scheduler.TaskSchedulerService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 面向 Agent 的定时任务工具（自然语言设任务）：暴露 schedule_task / list_tasks / cancel_task
 * 三个 {@code @Tool} 方法，由 {@link com.gettyio.gim.agent.core.chat.HarnessAgentGateway} 注册进 Toolkit。
 * <p>每个智能体一份实例（构造时绑定其 agentCode）：工具新建的任务归属当前对话的智能体，
 * 而非一律挂到默认智能体。</p>
 * <p>置于 scheduler 模块以便复用 {@link TaskSchedulerService} 与 {@link ScheduledTaskDao}，
 * 依赖方向仍为 scheduler → domain。</p>
 *
 * @author gogym
 */
public class ScheduleTools {

    private static final Logger log = LoggerFactory.getLogger(ScheduleTools.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TaskSchedulerService schedulerService;
    private final ScheduledTaskDao taskDao;
    /** 工具归属的智能体编码：schedule_task 新建任务绑它（= 装配运行体的 agentId） */
    private final String agentCode;

    public ScheduleTools(TaskSchedulerService schedulerService, ScheduledTaskDao taskDao, String agentCode) {
        this.schedulerService = schedulerService;
        this.taskDao = taskDao;
        this.agentCode = agentCode == null || agentCode.isBlank()
                ? com.gettyio.gim.agent.core.chat.MultiAgentGateway.DEFAULT_AGENT_CODE : agentCode;
    }

    /** 工具入口（{@code schedule_task}）：校验 cron/run_at 互斥与格式后落库并调度；调度失败回滚删除记录；一切失败以文本返回。 */
    @Tool(name = "schedule_task", readOnly = false,
            description = "创建一个定时任务：把 prompt 指令定时投递给 Agent 执行。两种模式二选一——"
                    + "周期任务传 cron（Quartz 六/七字段，如每天9点 '0 0 9 * * ?'，每30分钟 '0 0/30 * * * ?'）；"
                    + "一次性到点任务传 run_at（'yyyy-MM-dd HH:mm:ss'，到点只执行一次）。")
    public String scheduleTask(
            @ToolParam(name = "task_name", description = "任务的可读名称") String taskName,
            @ToolParam(name = "cron", required = false, description = "Quartz cron 表达式，如 '0 0 9 * * ?'（周期任务用，与 run_at 互斥）") String cron,
            @ToolParam(name = "prompt", description = "到点投递给 Agent 的指令内容") String prompt,
            @ToolParam(name = "once", required = false, description = "是否一次性任务（配 cron 时生效：只触发一次后停用），默认 false") Boolean once,
            @ToolParam(name = "run_at", required = false, description = "一次性执行时刻 'yyyy-MM-dd HH:mm:ss'（与 cron 互斥）") String runAt) {
        boolean hasRunAt = runAt != null && !runAt.isBlank();
        boolean hasCron = cron != null && !cron.isBlank();
        if (taskName == null || taskName.isBlank() || prompt == null || prompt.isBlank()) {
            return "参数不完整：task_name、prompt 均不能为空。";
        }
        if (hasRunAt && hasCron) {
            return "cron 与 run_at 互斥，请二选一。";
        }
        if (!hasRunAt && !hasCron) {
            return "请提供 cron（周期任务）或 run_at（一次性到点任务）其中之一。";
        }
        LocalDateTime runAtTime = null;
        String expr = null;
        if (hasRunAt) {
            try {
                runAtTime = LocalDateTime.parse(runAt.trim(), FMT);
            } catch (DateTimeParseException e) {
                return "非法的 run_at 时间（应为 yyyy-MM-dd HH:mm:ss）：" + runAt;
            }
        } else {
            expr = cron.trim();
            if (!CronExpression.isValidExpression(expr)) {
                try {
                    new CronExpression(expr);
                } catch (ParseException e) {
                    return "非法的 cron 表达式：" + cron + "（" + e.getMessage() + "）";
                }
            }
        }
        String code = "task-" + System.currentTimeMillis()
                + "-" + ThreadLocalRandom.current().nextInt(1000, 9999);
        ScheduledTask task = new ScheduledTask();
        task.setTaskCode(code);
        task.setTaskName(taskName.trim());
        task.setAgentCode(agentCode);
        task.setPrompt(prompt);
        task.setCronExpression(expr);
        task.setRunAt(runAtTime);
        task.setStatus(TaskStatus.READY.name());
        // 指定时刻的一次性任务强制 once；否则按传入 once
        boolean oneShot = runAtTime != null || Boolean.TRUE.equals(once);
        task.setOnce(oneShot ? EnabledStatus.ENABLED : EnabledStatus.DISABLED);
        taskDao.insert(task);
        try {
            schedulerService.schedule(task);
        } catch (Exception e) {
            taskDao.deleteByCode(code);
            log.error("工具创建定时任务调度失败: {}", code, e);
            return "创建失败：调度异常 " + e.getMessage();
        }
        return "已创建定时任务 [" + task.getTaskName() + "] 编码=" + code
                + (runAtTime != null ? " 执行于=" + runAtTime.format(FMT) + "（一次性）"
                        : " cron=" + expr + (oneShot ? "（一次性）" : "")) + "。";
    }

    /** 工具入口（{@code list_tasks}）：列出全部任务的编码/名称/触发定义/状态/下次触发时刻。 */
    @Tool(name = "list_tasks", readOnly = true,
            description = "列出所有已配置的定时任务，逐条返回编码、名称、触发定义（cron= 周期表达式或"
                    + " run_at= 一次性时刻）、状态与下次触发时刻。取消时用编码（task- 开头那段）。")
    public String listTasks() {
        List<ScheduledTask> tasks = taskDao.findAll();
        if (tasks.isEmpty()) {
            return "当前没有任何定时任务。";
        }
        StringBuilder sb = new StringBuilder("定时任务列表：\n");
        for (ScheduledTask t : tasks) {
            sb.append("- ").append(t.getTaskCode())
                    .append(" | ").append(t.getTaskName())
                    .append(" | ").append(t.getRunAt() != null
                            ? "run_at=" + t.getRunAt().format(FMT) : "cron=" + t.getCronExpression())
                    .append(" | ").append(t.getStatus());
            if (t.getNextRunAt() != null) {
                sb.append(" | 下次=").append(t.getNextRunAt().format(FMT));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 工具入口（{@code cancel_task}）：按编码注销调度并删除任务记录；未找到时给出提示。 */
    @Tool(name = "cancel_task", readOnly = false,
            description = "按任务编码取消（删除）一个定时任务，停止其后续触发。")
    public String cancelTask(
            @ToolParam(name = "task_code", description = "要取消的任务编码") String taskCode) {
        if (taskCode == null || taskCode.isBlank()) {
            return "task_code 不能为空。";
        }
        var found = taskDao.findByCode(taskCode.trim());
        if (found.isEmpty()) {
            return "未找到任务：" + taskCode;
        }
        try {
            schedulerService.unschedule(taskCode.trim());
        } catch (Exception e) {
            log.warn("注销调度失败: {}", taskCode, e);
        }
        taskDao.deleteByCode(taskCode.trim());
        return "已取消任务 [" + found.get().getTaskName() + "]。";
    }
}
