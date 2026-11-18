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

package com.gettyio.jwork.agent.ui.window;

import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.security.LocalAuthService;
import com.gettyio.jwork.agent.domain.util.OsPlatform;
import com.gettyio.jwork.agent.ui.i18n.I18n;
import com.gettyio.jwork.agent.ui.notify.TrayNotifier;
import com.gettyio.jwork.agent.ui.util.UiStyles;
import com.gettyio.jwork.agent.ui.window.dialog.JworkDialog;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * 应用生命周期协调：托盘常驻（关闭行为可配）与开机自启。
 * <ul>
 *   <li><b>关闭行为</b>：AppSetting {@code app.close.action} = {@code exit|tray}。首次点关闭且未设置时
 *       弹询问框（含「不再询问」）；{@code tray} 只隐藏主窗、进程与调度继续，{@code exit} 真正退出。</li>
 *   <li><b>开机自启</b>：仅 Windows + 打包 exe 可用，读写 HKCU 的 Run 键指向当前可执行文件。</li>
 * </ul>
 * <p>依赖 {@link Platform#setImplicitExit(boolean)} 关闭隐式退出，令隐藏主窗不结束进程；
 * 真正的终止统一走 {@link #exit()}。</p>
 *
 * @author gogym
 */
public class AppLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AppLifecycle.class);

    private static final String KEY_CLOSE_ACTION = "app.close.action";
    public static final String CLOSE_EXIT = "exit";
    public static final String CLOSE_TRAY = "tray";

    /** HKCU 开机自启注册表位置与值名（Windows）。 */
    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String RUN_VALUE = "Jwork";

    private final JworkDatabase db;
    private final TrayNotifier trayNotifier;
    /** 本地登录认证（软锁）：唤回主窗时校验会话，超期/锁定先弹独立登录小窗。 */
    private final LocalAuthService localAuthService;

    public AppLifecycle(JworkDatabase db, TrayNotifier trayNotifier, LocalAuthService localAuthService) {
        this.db = db;
        this.trayNotifier = trayNotifier;
        this.localAuthService = localAuthService;
    }

    private Stage stage;

    /** 主窗装配完成后由 {@code JworkAgentApplication.start} 调用：记住主窗并挂接托盘菜单。 */
    public void attach(Stage stage) {
        this.stage = stage;
        // 关闭隐式退出：隐藏主窗（托盘常驻）不应结束进程；真正退出统一走 exit()
        Platform.setImplicitExit(false);
        trayNotifier.addMainWindowActions(this::openMainWindow, this::exit);
    }

    /**
     * 关闭按钮入口：按已配置行为执行；未配置则首次询问（含「不再询问」记忆选择）。
     * <p>仅在存在可用托盘时才提供「隐藏到托盘」，否则退化为直接退出，避免隐藏后无从唤回。</p>
     */
    public void requestClose() {
        readCloseAction().ifPresentOrElse(this::applyCloseAction, this::askFirstTime);
    }

    private void askFirstTime() {
        boolean trayCapable = trayNotifier.isAvailable();
        JworkDialog.Shell shell = JworkDialog.shell(stage, I18n.t("app.close.ask.title"));

        Label msg = new Label(I18n.t("app.close.ask.message"));
        msg.setWrapText(true);
        CheckBox remember = new CheckBox(I18n.t("app.close.ask.remember"));

        Button doTray = new Button(I18n.t("app.close.ask.tray"));
        Button doExit = new Button(I18n.t("app.close.ask.exit"));
        doExit.getStyleClass().add(UiStyles.BTN_PRIMARY);
        if (!trayCapable) {
            doTray.setDisable(true);
        }
        // 选定即记录行为（勾选「不再询问」才持久化）后关闭并执行
        doTray.setOnAction(e -> {
            String action = CLOSE_TRAY;
            if (remember.isSelected()) {
                persistCloseAction(action);
            }
            shell.stage().close();
            applyCloseAction(action);
        });
        doExit.setOnAction(e -> {
            String action = CLOSE_EXIT;
            if (remember.isSelected()) {
                persistCloseAction(action);
            }
            shell.stage().close();
            applyCloseAction(action);
        });

        // 「不再询问」与操作按钮同处一行：勾选框靠左、按钮靠右，垂直居中共线
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, remember, spacer, doTray, doExit);
        footer.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(12, msg, footer);
        content.setPadding(new Insets(4));
        shell.body().getChildren().add(content);
        shell.showAndWait();
    }

    private void applyCloseAction(String action) {
        if (CLOSE_TRAY.equals(action) && trayNotifier.isAvailable()) {
            hideToTray();
        } else {
            exit();
        }
    }

    /** 隐藏主窗到托盘；首次隐藏提示一条气泡帮助找回入口。 */
    public void hideToTray() {
        if (stage != null) {
            stage.hide();
        }
        trayNotifier.notify(I18n.t("notify.trayTitle", "Jwork"), I18n.t("notify.tray.hint"));
    }

    /**
     * 退出登录（安全页账号卡入口）：清除会话并隐藏主窗，弹出独立登录小窗——认证通过重新显示
     * 主窗，取消（关闭登录窗）则隐藏到托盘。
     */
    public void lockToLogin() {
        localAuthService.lock();
        if (stage == null) {
            return;
        }
        stage.hide();
        LoginGate.ensureAuthenticated(localAuthService, authed -> {
            if (!authed) {
                hideToTray();
                return;
            }
            stage.show();
            stage.toFront();
            stage.requestFocus();
        });
    }

    /** 从托盘唤回主窗（AWT 线程回调，转投 FX 线程）；会话超期/锁定时主窗保持隐藏、先弹登录小窗，取消则停留托盘。 */
    public void openMainWindow() {
        Platform.runLater(() -> {
            if (stage == null) {
                return;
            }
            Platform.setImplicitExit(false);
            if (stage.isShowing()) {
                // 已显示：直接置前
                stage.toFront();
                stage.requestFocus();
                return;
            }
            if (localAuthService.isSessionValid()) {
                localAuthService.touch();
                stage.show();
                stage.toFront();
                stage.requestFocus();
                return;
            }
            // 会话超期/锁定：独立登录小窗（主窗保持隐藏）；认证通过才显示主窗，取消则停留托盘
            LoginGate.ensureAuthenticated(localAuthService, authed -> {
                if (!authed) {
                    return;
                }
                stage.show();
                stage.toFront();
                stage.requestFocus();
            });
        });
    }

    /** 真正退出：走 JavaFX 正常终止流程（触发应用 {@code stop()} 组合根停机、撤托盘）。 */
    public void exit() {
        // 先挂看门狗：FX 线程若卡在弹框或事件队列里，stop() 就来不及跑，不能因此让进程吊着
        AppExit.armWatchdog();
        Platform.runLater(() -> {
            trayNotifier.dispose();
            Platform.exit();
        });
    }

    // ===================== 关闭行为设置 =====================

    public Optional<String> readCloseAction() {
        return db.settingDao().getValue(KEY_CLOSE_ACTION)
                .map(String::trim)
                .filter(v -> CLOSE_EXIT.equals(v) || CLOSE_TRAY.equals(v));
    }

    public void setCloseAction(String action) {
        persistCloseAction(CLOSE_TRAY.equals(action) ? CLOSE_TRAY : CLOSE_EXIT);
    }

    private void persistCloseAction(String action) {
        db.settingDao().upsert(KEY_CLOSE_ACTION, action, "关闭窗口行为：exit=退出程序，tray=最小化到托盘");
    }

    // ===================== 开机自启（仅 Windows + 打包 exe） =====================

    /** 当前是否可管理系统启动项：Windows 且以打包 exe 运行（开发模式 java 启动返回 false）。 */
    public boolean canManageAutostart() {
        return isWindows() && packagedExecutable().isPresent();
    }

    /** 开机自启当前是否已开启（读 HKCU Run 键）。 */
    public boolean isAutostartEnabled() {
        if (!canManageAutostart()) {
            return false;
        }
        return runReg("reg", "query", RUN_KEY, "/v", RUN_VALUE);
    }

    /** 开启/关闭开机自启；返回是否操作成功。 */
    public boolean setAutostart(boolean enable) {
        Optional<String> exe = packagedExecutable();
        if (!isWindows() || exe.isEmpty()) {
            return false;
        }
        if (enable) {
            // /d 值带引号包裹的路径，注册表字符串原样存 "<path>"，开机时按路径执行
            return runReg("reg", "add", RUN_KEY, "/v", RUN_VALUE, "/t", "REG_SZ",
                    "/d", "\"" + exe.get() + "\"", "/f");
        }
        // 关闭：值不存在时删除会失败，视为已达成
        boolean ok = runReg("reg", "delete", RUN_KEY, "/v", RUN_VALUE, "/f");
        return ok || !isAutostartEnabled();
    }

    private static boolean isWindows() {
        return OsPlatform.current().isWindows();
    }

    /**
     * 打包后的应用可执行文件路径：{@code ProcessHandle.current().info().command()} 指向自身 exe
     * 且不是 java/javaw（开发模式下 command 是 JDK 启动器，不可作为自启目标）。
     */
    private static Optional<String> packagedExecutable() {
        try {
            Optional<String> cmd = ProcessHandle.current().info().command();
            if (cmd.isEmpty()) {
                return Optional.empty();
            }
            Path p = Path.of(cmd.get());
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".exe") || name.equals("java.exe") || name.equals("javaw.exe")) {
                return Optional.empty();
            }
            if (!Files.isRegularFile(p)) {
                return Optional.empty();
            }
            return Optional.of(p.toAbsolutePath().toString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 执行 reg 命令，返回进程退出码 0（成功）；IO 失败记日志返回 false。 */
    private static boolean runReg(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            return proc.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            log.warn("注册表操作失败: {}", String.join(" ", command), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
