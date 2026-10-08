package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import com.gettyio.gim.agent.ui.control.ToggleSwitch;
import com.gettyio.gim.agent.ui.update.UpdateChecker;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 个人资料（关于）设置子面板：应用版本/数据目录/语言/主题只读概览，以及更新卡片
 * （服务器地址、每日静默检查、立即检查→下载→校验→拉起安装）。抽自 {@link SettingsWindow}，
 * 依赖 {@link UpdateChecker} 与宿主弹窗归属/提示回调。
 */
final class ProfileSettingsPanel {

    private final SettingsWindow host;
    private final GimPaths gimPaths;
    private final UpdateChecker updateChecker;

    ProfileSettingsPanel(SettingsWindow host, GimPaths gimPaths, UpdateChecker updateChecker) {
        this.host = host;
        this.gimPaths = gimPaths;
        this.updateChecker = updateChecker;
    }

    VBox build() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.profile"), I18n.t("settings.profile.subtitle"));

        HBox head = new HBox(16);
        head.getStyleClass().add("profile-head");
        Label avatar = new Label("A");
        avatar.getStyleClass().add("profile-avatar");
        avatar.setAlignment(Pos.CENTER);
        Label name = new Label(I18n.t("user.local"));
        name.getStyleClass().add("profile-name");
        Label badge = new Label(I18n.t("settings.profile.badge"));
        badge.getStyleClass().addAll("badge", "badge-green");
        VBox info = new VBox(6, name, badge);
        info.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(avatar, info);

        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.settingRow(I18n.t("settings.profile.appVersion"), null,
                SettingsLayout.valueLabel(updateChecker.currentVersion())));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.key.dataDir"), null,
                SettingsLayout.valueLabel(String.valueOf(gimPaths.root()))));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.profile.language"), null,
                SettingsLayout.valueLabel(I18n.current().nativeName())));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.profile.theme"), null,
                SettingsLayout.valueLabel(I18n.t(UiTheme.mode() == UiTheme.Mode.DARK ? "menu.mode.dark" : "menu.mode.light"))));

        box.getChildren().addAll(head, SettingsLayout.groupCard(I18n.t("settings.group.about"), rows), updateCard());
        return box;
    }

    /** 更新卡片：服务器地址、每日静默检查开关、立即检查按钮。 */
    private VBox updateCard() {
        Button check = SettingsLayout.iconTextButton("\uE777", I18n.t("settings.update.check"), this::handleCheckUpdate);

        ToggleSwitch auto = new ToggleSwitch();
        auto.setSelected(updateChecker.isAutoCheckEnabled());
        auto.selectedProperty().addListener((obs, o, sel) -> updateChecker.setAutoCheckEnabled(sel));

        TextField serverField = new TextField(updateChecker.getServerBaseUrl());
        serverField.setPromptText(I18n.t("settings.update.server.prompt"));
        serverField.setMaxWidth(360);
        Runnable saveServer = () -> updateChecker.setServerBaseUrl(serverField.getText());
        serverField.setOnAction(e -> saveServer.run());
        serverField.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                saveServer.run();
            }
        });

        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.settingRow(I18n.t("settings.update.server"), I18n.t("settings.update.server.desc"), serverField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.update.auto"), I18n.t("settings.update.auto.desc"), auto));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.update.check"), I18n.t("settings.update.check.desc"), check));
        return SettingsLayout.groupCard(I18n.t("settings.group.update"), rows);
    }

    /** 手动检查更新：向服务器查询新版，命中后下载并校验，再提示拉起安装。 */
    private void handleCheckUpdate() {
        Window owner = host.hostWindow();
        Thread t = new Thread(() -> {
            try {
                Optional<UpdateChecker.UpdateInfo> info = updateChecker.checkLatest();
                Platform.runLater(() -> {
                    if (info.isPresent()) {
                        if (info.get().forceUpdate()) {
                            updateChecker.beginForced(owner, info.get(), null);
                        } else {
                            confirmDownload(owner, info.get());
                        }
                    } else {
                        GimDialog.message(owner, I18n.t("settings.update.latest.title"), null,
                                I18n.t("settings.update.latest.body", updateChecker.currentVersion()));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> host.notify("settings.update.check",
                        I18n.t("settings.update.fail", String.valueOf(e.getMessage()))));
            }
        }, "gim-update-check");
        t.setDaemon(true);
        t.start();
    }

    /** 发现新版：询问是否下载，确认后后台下载并校验，完成再询问是否安装。 */
    private void confirmDownload(Window owner, UpdateChecker.UpdateInfo info) {
        int go = GimDialog.choose(owner, I18n.t("settings.update.found.title"), null,
                I18n.t("settings.update.found.body", updateChecker.currentVersion(), info.version()),
                I18n.t("settings.update.download"), I18n.t("common.cancel"));
        if (go != 0) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Path file = updateChecker.download(info, ratio -> { });
                Platform.runLater(() -> confirmInstall(owner, file));
            } catch (Exception e) {
                Platform.runLater(() -> host.notify("settings.update.check",
                        I18n.t("settings.update.downloadFail", String.valueOf(e.getMessage()))));
            }
        }, "gim-update-download");
        t.setDaemon(true);
        t.start();
    }

    /** 下载完成：询问是否立即拉起安装。 */
    private void confirmInstall(Window owner, Path file) {
        int go = GimDialog.choose(owner, I18n.t("settings.update.install.title"), null,
                I18n.t("settings.update.install.body", file.getFileName().toString()),
                I18n.t("settings.update.install"), I18n.t("common.cancel"));
        if (go == 0) {
            boolean ok = updateChecker.install(file);
            if (!ok) {
                host.notify("settings.update.check", I18n.t("settings.update.installFail"));
            }
        }
    }
}
