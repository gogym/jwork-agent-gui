package com.gettyio.gim.agent.ui;

import com.gettyio.gim.agent.domain.security.LocalAuthService;
import com.gettyio.gim.agent.ui.window.AppShell;
import com.gettyio.gim.agent.ui.window.AppLifecycle;
import com.gettyio.gim.agent.ui.window.GimDialog;
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
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;

/**
 * JavaFX 应用入口：在 {@code start()} 内引导 Spring 容器（非 web 模式），
 * 通过 Spring 提供的控制器工厂加载主窗口 FXML。
 */
@SpringBootApplication
public class GimAgentApplication extends Application {

    private static final Logger log = LoggerFactory.getLogger(GimAgentApplication.class);

    private ConfigurableApplicationContext springContext;

    @Override
    public void init() {
        // 阶段文案推送给启动画面（notifyPreloader 可从 init 线程调用）
        notifyPreloader(new SplashPreloader.SplashProgress(I18n.t("splash.preparing")));
        springContext = new SpringApplicationBuilder(GimAgentApplication.class)
                .web(WebApplicationType.NONE)
                .headless(false)
                .run();
        notifyPreloader(new SplashPreloader.SplashProgress(I18n.t("splash.loading")));
    }

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
        LocalAuthService localAuth = springContext.getBean(LocalAuthService.class);
        // 去掉系统顶部程序 bar，改用自绘标题栏；TRANSPARENT 使窗口可呈现四角圆角（须在建 Scene 前设置）
        stage.initStyle(StageStyle.TRANSPARENT);
        // 关闭 OS 可调整大小：避免 Windows 为可调整的无边框/透明窗口叠加一圈不可见 resize 边框（WS_THICKFRAME），
        // 该隐形的内缩边框会在 Popup/ContextMenu 关闭重新获焦时让内容整体偏移。窗口缩放改由 StageResizer 手动接管。
        stage.setResizable(false);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
        loader.setControllerFactory(springContext::getBean);
        Parent appRoot = loader.load();

        AppShell shell = springContext.getBean(AppShell.class);
        shell.configure(stage, appRoot);

        // 生命周期：主窗就绪后接管关闭行为（托盘常驻/退出）与托盘唤回入口
        AppLifecycle lifecycle = springContext.getBean(AppLifecycle.class);
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
        springContext.getBean(com.gettyio.gim.agent.ui.update.UpdateChecker.class).maybeCheckOnStartup(stage);

        // 全局热键（仅 Windows）：Ctrl+Alt+Q 唤起快速问答小窗、Ctrl+Alt+A 截屏提问注入主窗输入框、
        // Ctrl+Alt+D 划词助手（抓取选中文本后浮出操作条）
        com.gettyio.gim.agent.ui.controller.MainController controller =
                (com.gettyio.gim.agent.ui.controller.MainController) loader.getController();
        com.gettyio.gim.agent.ui.desktop.QuickAskWindow quickAsk =
                springContext.getBean(com.gettyio.gim.agent.ui.desktop.QuickAskWindow.class);
        com.gettyio.gim.agent.ui.desktop.SelectionAssistant selection =
                springContext.getBean(com.gettyio.gim.agent.ui.desktop.SelectionAssistant.class);
        com.gettyio.gim.agent.ui.desktop.GlobalHotkeyService hotkeys =
                springContext.getBean(com.gettyio.gim.agent.ui.desktop.GlobalHotkeyService.class);
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

    @Override
    public void stop() {
        if (springContext != null) {
            springContext.close();
        }
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
