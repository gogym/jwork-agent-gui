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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 面向 Agent 的定时任务工具（自然语言设任务）：暴露 schedule_task / list_tasks / cancel_task
 * 三个 {@code @Tool} 方法，由 {@link com.gettyio.gim.agent.core.chat.HarnessAgentGateway} 注册进 Toolkit。
 * <p>置于 scheduler 模块以便复用 {@link TaskSchedulerService} 与 {@link ScheduledTaskDao}，
 * 依赖方向仍为 scheduler → domain。</p>
 */
public class ScheduleTools {

    private static final Logger log = LoggerFactory.getLogger(ScheduleTools.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TaskSchedulerService schedulerService;
    private final ScheduledTaskDao taskDao;

    public ScheduleTools(TaskSchedulerService schedulerService, ScheduledTaskDao taskDao) {
        this.schedulerService = schedulerService;
        this.taskDao = taskDao;
    }

    @Tool(name = "schedule_task", readOnly = false,
            description = "创建一个定时任务：到点按 cron 表达式自动把 prompt 指令投递给 Agent 执行。"
                    + "cron 为 Quartz 六/七字段表达式，例如每天9点 '0 0 9 * * ?'，每30分钟 '0 0/30 * * * ?'。")
    public String scheduleTask(
            @ToolParam(name = "task_name", description = "任务的可读名称") String taskName,
            @ToolParam(name = "cron", description = "Quartz cron 表达式，如 '0 0 9 * * ?'") String cron,
            @ToolParam(name = "prompt", description = "到点投递给 Agent 的指令内容") String prompt,
            @ToolParam(name = "once", required = false, description = "是否一次性任务，'true'/'false'，默认 false") String once) {
        if (taskName == null || taskName.isBlank() || cron == null || cron.isBlank()
                || prompt == null || prompt.isBlank()) {
            return "参数不完整：task_name、cron、prompt 均不能为空。";
        }
        String expr = cron.trim();
        if (!CronExpression.isValidExpression(expr)) {
            try {
                new CronExpression(expr);
            } catch (ParseException e) {
                return "非法的 cron 表达式：" + cron + "（" + e.getMessage() + "）";
            }
        }
        String code = "task-" + System.currentTimeMillis()
                + "-" + ThreadLocalRandom.current().nextInt(1000, 9999);
        ScheduledTask task = new ScheduledTask();
        task.setTaskCode(code);
        task.setTaskName(taskName.trim());
        task.setAgentCode("gim-agent");
        task.setPrompt(prompt);
        task.setCronExpression(expr);
        task.setStatus(TaskStatus.READY.name());
        task.setOnce("true".equalsIgnoreCase(once) ? EnabledStatus.ENABLED : EnabledStatus.DISABLED);
        taskDao.insert(task);
        try {
            schedulerService.schedule(task);
        } catch (Exception e) {
            taskDao.deleteByCode(code);
            log.error("工具创建定时任务调度失败: {}", code, e);
            return "创建失败：调度异常 " + e.getMessage();
        }
        return "已创建定时任务 [" + task.getTaskName() + "] 编码=" + code + " cron=" + expr
                + ("true".equalsIgnoreCase(once) ? "（一次性）" : "") + "。";
    }

    @Tool(name = "list_tasks", readOnly = true,
            description = "列出所有已配置的定时任务，返回其编码、名称、cron、状态。")
    public String listTasks() {
        List<ScheduledTask> tasks = taskDao.findAll();
        if (tasks.isEmpty()) {
            return "当前没有任何定时任务。";
        }
        StringBuilder sb = new StringBuilder("定时任务列表：\n");
        for (ScheduledTask t : tasks) {
            sb.append("- ").append(t.getTaskCode())
                    .append(" | ").append(t.getTaskName())
                    .append(" | cron=").append(t.getCronExpression())
                    .append(" | ").append(t.getStatus());
            if (t.getNextRunAt() != null) {
                sb.append(" | 下次=").append(t.getNextRunAt().format(FMT));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

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
