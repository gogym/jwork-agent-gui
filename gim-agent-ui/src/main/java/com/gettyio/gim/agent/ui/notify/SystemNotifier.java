package com.gettyio.gim.agent.ui.notify;

import com.gettyio.gim.agent.core.task.TaskCompletedEvent;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.window.AppShell;
import com.gettyio.gim.agent.ui.window.GimDialog;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 系统通知：监听定时任务完成事件。优先走系统托盘气球（M5），
 * 托盘不可用时回退到 UI 线程弹窗。
 */
@Component
@Lazy(false) // 全局 lazy-init 下保持 eager：@EventListener 需在启动期注册，任务完成事件随时可能到达
public class SystemNotifier {

    private static final Logger log = LoggerFactory.getLogger(SystemNotifier.class);

    @Autowired
    private TrayNotifier trayNotifier;

    @Autowired
    private AppShell appShell;

    @EventListener
    public void onTaskCompleted(TaskCompletedEvent event) {
        log.info("收到任务完成事件: {}", event);
        String title = I18n.t("notify.trayTitle", event.getTaskName());
        String head = event.isSuccess()
                ? I18n.t("notify.done", event.getTaskName())
                : I18n.t("notify.failed", event.getTaskName());
        if (trayNotifier.isAvailable()) {
            trayNotifier.notify(title, head + "\n" + event.getSummary());
            return;
        }
        Platform.runLater(() -> GimDialog.message(appShell.getStage(),
                I18n.t("notify.windowTitle"), head, event.getSummary()));
    }

    /**
     * 用量预算告警：优先系统托盘气球，托盘不可用时回退弹窗；
     * 调用方（{@code MainController}）已按日去重，本方法不重复过滤。
     */
    public void notifyBudget(String message) {
        log.info("月度用量预算告警: {}", message);
        String title = I18n.t("usage.budget.notifyTitle");
        if (trayNotifier.isAvailable()) {
            trayNotifier.notify(title, message);
            return;
        }
        Platform.runLater(() -> GimDialog.message(appShell.getStage(), title, title, message));
    }
}
