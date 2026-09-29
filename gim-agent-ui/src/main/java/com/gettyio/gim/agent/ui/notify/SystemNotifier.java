package com.gettyio.gim.agent.ui.notify;

import com.gettyio.gim.agent.core.task.TaskCompletedEvent;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.window.GimDialog;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 系统通知：监听定时任务完成事件。优先走系统托盘气球（M5），
 * 托盘不可用时回退到 UI 线程弹窗。
 */
@Component
public class SystemNotifier {

    private static final Logger log = LoggerFactory.getLogger(SystemNotifier.class);

    @Autowired
    private TrayNotifier trayNotifier;

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
        Platform.runLater(() -> GimDialog.message(null,
                I18n.t("notify.windowTitle"), head, event.getSummary()));
    }
}
