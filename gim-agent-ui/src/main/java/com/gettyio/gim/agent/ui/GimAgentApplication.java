package com.gettyio.gim.agent.ui;

import com.gettyio.gim.agent.ui.window.AppShell;
import com.gettyio.gim.agent.ui.window.AppLifecycle;
import com.gettyio.gim.agent.ui.window.GimDialog;
import com.gettyio.gim.agent.ui.window.StageResizer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
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
        springContext = new SpringApplicationBuilder(GimAgentApplication.class)
                .web(WebApplicationType.NONE)
                .headless(false)
                .run();
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
        stage.setMinWidth(980);
        stage.setMinHeight(620);
        stage.setScene(scene);
        stage.show();

        // 更新检查：若开关开启且当日未查过，后台静默比对 GitHub 最新版（发现新版仅发托盘气泡）
        springContext.getBean(com.gettyio.gim.agent.ui.update.UpdateChecker.class).maybeCheckOnStartup();
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
