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

package com.gettyio.gim.agent.ui;

import com.gettyio.gim.agent.domain.security.LocalAuthService;
import com.gettyio.gim.agent.ui.config.AppContext;
import com.gettyio.gim.agent.ui.controller.MainController;
import com.gettyio.gim.agent.ui.desktop.GlobalHotkeyService;
import com.gettyio.gim.agent.ui.desktop.QuickAskWindow;
import com.gettyio.gim.agent.ui.desktop.SelectionAssistant;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.theme.UiTheme;
import com.gettyio.gim.agent.ui.window.AppShell;
import com.gettyio.gim.agent.ui.window.AppLifecycle;
import com.gettyio.gim.agent.ui.window.dialog.GimDialog;
import com.gettyio.gim.agent.ui.window.LoginGate;
import com.gettyio.gim.agent.ui.window.StageResizer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * JavaFX 应用入口：{@code init()} 装配应用上下文（无头服务 + 启动工序），
 * {@code start()} 于 FX 线程加载主窗 FXML（控制器由 {@link AppContext} 预构建后经控制器工厂返回），
 * 认证通过后完成更新检查与全局热键装配。
 *
 * @author gogym
 */
public class GimAgentApplication extends Application {

    private static final Logger log = LoggerFactory.getLogger(GimAgentApplication.class);

    private AppContext context;

    @Override
    public void init() {
        // 阶段文案推送给启动画面（notifyPreloader 可从 init 线程调用）
        notifyPreloader(new SplashPreloader.SplashProgress(I18n.t("splash.preparing")));
        context = new AppContext();
        context.runStartupTasks();
        notifyPreloader(new SplashPreloader.SplashProgress(I18n.t("splash.loading")));
    }

    /** JavaFX 启动入口：装配 FX 线程异常兜底、无边框透明主窗与登录门，认证通过后显示主窗并装配热键。 */
    @Override
    public void start(Stage stage) throws IOException {
        // FX 线程兜底：事件处理器/动画回调里的异常不再静默吞掉——记录后友好提示（JavaFX 无全局
        // ErrorHandler，未捕获异常走线程 UEH；这里替换 FX 线程的处理器，其余线程由 Launcher 的默认处理器接管）
        Thread.currentThread().setUncaughtExceptionHandler((thread, t) -> {
            log.error("JavaFX 线程未处理异常", t);
            try {
                Platform.runLater(() -> {
                    try {
                        GimDialog.message(stage, I18n.t("error.unhandled.title"), null, I18n.t("error.unhandled"));
                    } catch (RuntimeException nested) {
                        log.warn("错误提示弹窗失败，仅保留日志记录", nested);
                    }
                });
            } catch (RuntimeException nested) {
                log.warn("调度错误提示失败，仅保留日志记录", nested);
            }
        });
        // 登录门（软锁）凭证服务：首次启动须设置账号密码、会话超期/锁定后须重新登录
        LocalAuthService localAuth = context.localAuthService();
        // 窗口图标（任务栏/Alt+Tab）：随包应用徽标
        UiTheme.applyAppIcon(stage);
        // 去掉系统顶部程序 bar，改用自绘标题栏；TRANSPARENT 使窗口可呈现四角圆角（须在建 Scene 前设置）
        stage.initStyle(StageStyle.TRANSPARENT);
        // 关闭 OS 可调整大小：避免 Windows 为可调整的无边框/透明窗口叠加一圈不可见 resize 边框（WS_THICKFRAME），
        // 该隐形的内缩边框会在 Popup/ContextMenu 关闭重新获焦时让内容整体偏移。窗口缩放改由 StageResizer 手动接管。
        stage.setResizable(false);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
        // 控制器工厂：主控制器由组合根预构建（含全部视图/设置窗口），FXML 装配期仅做字段注入与 initialize()
        loader.setControllerFactory(type -> {
            if (type == MainController.class) {
                return context.mainController();
            }
            throw new IllegalStateException("未注册的 FXML 控制器: " + type.getName());
        });
        Parent appRoot = loader.load();

        AppShell shell = context.appShell();
        shell.configure(stage, appRoot);

        // 生命周期：主窗就绪后接管关闭行为（托盘常驻/退出）与托盘唤回入口
        AppLifecycle lifecycle = context.appLifecycle();
        lifecycle.attach(stage);
        shell.onTitleBarReady(tb -> tb.setCloseAction(lifecycle::requestClose));

        Scene scene = new Scene(shell.getNode(), 1080, 760, Color.TRANSPARENT);
        UiTheme.apply(scene);
        StageResizer.attach(scene, stage);
        // 最小宽度按聊天内容列兜底：聊天限宽列 #list max-width 800px + 滚动条 10px（web/chat.css），
        // 窗口→聊天视口固定差约 290px（左侧栏/内容边距 261 + 右缘 29），1100 = 810 + 290。
        // 低于此值视口不足 810，内容列被迫随视口收缩（气泡贴边/位移）——窗口缩到最小即内容列
        // 最完整状态，保证「内容区域始终保持一致」。
        stage.setMinWidth(1100);
        stage.setMinHeight(620);
        stage.setScene(scene);

        // 登录门（软锁）：独立小窗先行（主窗保持隐藏）——首次启动须设置账号密码、会话超期/锁定后须
        // 重新登录；取消认证视为放弃进入（直接退出）。认证通过才显示主窗并继续装配热键。
        LoginGate.ensureAuthenticated(localAuth, authed -> {
            if (!authed) {
                Platform.exit();
                return;
            }
            stage.show();
            // 依屏幕分辨率自适应启动尺寸并居中：TRANSPARENT 无边框窗口的首显尺寸由根 pref 决定，
            // 故在 show 之后显式设定宽高（与标题栏最大化同款手段，可靠生效）
            applyAdaptiveStartupSize(stage);
            setupAfterAuthenticated(stage, loader, localAuth, lifecycle);
        });
    }

    /** 认证通过后的启动装配：更新检查 + 全局热键（主窗已显示、主界面此前已就绪）。 */
    private void setupAfterAuthenticated(Stage stage, FXMLLoader loader, LocalAuthService localAuth,
                                         AppLifecycle lifecycle) {
        // 更新检查：若开关开启且当日未查过，后台静默比对服务器最新版（发现新版：强更弹模态，否则发托盘气泡）
        context.updateChecker().maybeCheckOnStartup(stage);

        // 全局热键（仅 Windows）：Ctrl+Alt+Q 唤起快速问答小窗、Ctrl+Alt+A 截屏提问注入主窗输入框、
        // Ctrl+Alt+D 划词助手（抓取选中文本后浮出操作条）
        MainController controller = (MainController) loader.getController();
        QuickAskWindow quickAsk = context.quickAskWindow();
        SelectionAssistant selection = context.selectionAssistant();
        GlobalHotkeyService hotkeys = context.globalHotkeyService();
        hotkeys.setOnQuickAsk(() -> Platform.runLater(() -> {
            if (sessionActive(localAuth, lifecycle)) {
                quickAsk.toggle();
            }
        }));
        hotkeys.setOnScreenshot(() -> Platform.runLater(() -> {
            if (sessionActive(localAuth, lifecycle)) {
                controller.captureScreenshotToComposer();
            }
        }));
        hotkeys.setOnSelection(() -> {
            if (sessionActive(localAuth, lifecycle)) {
                selection.trigger();
            }
        });
        hotkeys.start();
    }

    /**
     * 全局热键的会话门：会话超期/已锁定时不放行热键功能，转走主窗登录门
     * （openMainWindow 内含同一认证门），避免绕过登录直接使用 AI。
     */
    private static boolean sessionActive(LocalAuthService auth, AppLifecycle lifecycle) {
        if (auth.isSessionValid()) {
            return true;
        }
        lifecycle.openMainWindow();
        return false;
    }

    /**
     * 依主屏可视区自适应启动窗口尺寸并居中：取可视区的约 60%×76% 作为目标，
     * 不小于最小尺寸、且四周留出边距不铺满整屏，最后在工作区内居中。
     */
    private void applyAdaptiveStartupSize(Stage stage) {
        Rectangle2D visual = Screen.getPrimary().getVisualBounds();
        double margin = 80;
        double width = clamp(visual.getWidth() * 0.60, stage.getMinWidth(), visual.getWidth() - margin);
        double height = clamp(visual.getHeight() * 0.76, stage.getMinHeight(), visual.getHeight() - margin);
        stage.setWidth(width);
        stage.setHeight(height);
        stage.setX(visual.getMinX() + (visual.getWidth() - width) / 2);
        stage.setY(visual.getMinY() + (visual.getHeight() - height) / 2);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    /** 应用退出回调：关闭组合根（AppContext）后终止 JavaFX 平台。 */
    @Override
    public void stop() {
        if (context != null) {
            context.close();
        }
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
