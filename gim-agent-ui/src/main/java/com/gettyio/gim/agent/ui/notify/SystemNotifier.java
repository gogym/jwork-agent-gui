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

package com.gettyio.gim.agent.ui.notify;

import com.gettyio.gim.agent.core.task.TaskCompletedEvent;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.window.AppShell;
import com.gettyio.gim.agent.ui.window.dialog.GimDialog;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 系统通知：接收定时任务完成回调。优先走系统托盘气球（M5），
 * 托盘不可用时回退到 UI 线程弹窗。
 *
 * @author gogym
 */
public class SystemNotifier {

    private static final Logger log = LoggerFactory.getLogger(SystemNotifier.class);

    private final TrayNotifier trayNotifier;
    private final AppShell appShell;

    /** 由组合根装配；任务执行器把本类的 {@link #onTaskCompleted} 作为完成回调。 */
    public SystemNotifier(TrayNotifier trayNotifier, AppShell appShell) {
        this.trayNotifier = trayNotifier;
        this.appShell = appShell;
    }

    /** 任务完成回调（原事件订阅）：成功/失败均通知，托盘不可用时回退弹窗。 */
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
