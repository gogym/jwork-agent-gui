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

package com.gettyio.jwork.agent.ui.window.settings;

import com.gettyio.jwork.agent.core.JworkPaths;
import com.gettyio.jwork.agent.ui.control.CardKit;
import com.gettyio.jwork.agent.ui.i18n.I18n;
import com.gettyio.jwork.agent.ui.theme.UiTheme;
import com.gettyio.jwork.agent.ui.control.ToggleSwitch;
import com.gettyio.jwork.agent.ui.update.UpdateChecker;
import com.gettyio.jwork.agent.ui.window.dialog.JworkDialog;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 关于设置子面板（独立「关于」菜单页）：上层为「关于本机」只读概览（应用版本/数据目录/语言/主题，
 * 自原个人资料页并入），下层为更新卡片（上次检查、更新服务器地址、每日静默检查、立即检查与实时状态），
 * 并承载「检查→下载→校验→拉起安装」的手动更新流程。依赖 {@link UpdateChecker} 与宿主弹窗归属/提示回调。
 *
 * @author gogym
 */
final class UpdateSettingsPanel {

    private final SettingsWindow host;
    /** 本地数据/工作区路径（关于本机卡展示数据目录）。 */
    private final JworkPaths jworkPaths;
    private final UpdateChecker updateChecker;

    /** 更新卡片动态控件：随检查/下载流程刷新状态与上次检查时间。 */
    private Button checkButton;
    private Label statusLabel;
    private Label lastCheckLabel;

    UpdateSettingsPanel(SettingsWindow host, JworkPaths jworkPaths, UpdateChecker updateChecker) {
        this.host = host;
        this.jworkPaths = jworkPaths;
        this.updateChecker = updateChecker;
    }

    VBox build() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.about"), I18n.t("settings.about.subtitle"));
        box.getChildren().add(aboutCard());
        box.getChildren().add(updateCard());
        return box;
    }

    /** 关于本机卡片：应用版本 / 数据目录 / 当前语言 / 当前主题只读概览（自原个人资料页并入）。 */
    private VBox aboutCard() {
        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.settingRow(I18n.t("settings.profile.appVersion"), null,
                SettingsLayout.valueLabel(updateChecker.currentVersion())));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.key.dataDir"), null,
                SettingsLayout.valueLabel(String.valueOf(jworkPaths.root()))));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.profile.language"), null,
                SettingsLayout.valueLabel(I18n.current().nativeName())));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.profile.theme"), null,
                SettingsLayout.valueLabel(I18n.t(UiTheme.mode() == UiTheme.Mode.DARK ? "menu.mode.dark" : "menu.mode.light"))));
        return SettingsLayout.groupCard(I18n.t("settings.group.about"), rows);
    }

    /** 更新卡片：上次检查、服务器地址、每日静默检查、立即检查与实时状态。 */
    private VBox updateCard() {
        Button check = CardKit.iconTextButton("\uE777", I18n.t("settings.update.check"), this::handleCheckUpdate);
        this.checkButton = check;

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

        lastCheckLabel = SettingsLayout.valueLabel(lastCheckText());
        statusLabel = SettingsLayout.valueLabel("");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(520);

        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.settingRow(I18n.t("settings.update.lastCheck"), null, lastCheckLabel));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.update.server"), I18n.t("settings.update.server.desc"), serverField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.update.auto"), I18n.t("settings.update.auto.desc"), auto));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.update.check"), I18n.t("settings.update.check.desc"), check));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.update.status"), null, statusLabel));
        // 独立页已有标题「关于」，更新卡分组标题留空避免与「关于本机」卡重复
        return SettingsLayout.groupCard(null, rows);
    }

    /** 「上次检查」展示文本：ISO 日期；从未检查显示占位。 */
    private String lastCheckText() {
        String d = updateChecker.lastCheckDate();
        return (d == null || d.isBlank()) ? I18n.t("settings.update.lastCheck.none") : d;
    }

    /** 状态行写入（须 FX 线程调用）。 */
    private void setStatus(String text) {
        if (statusLabel != null) {
            statusLabel.setText(text);
        }
    }

    /** 刷新「上次检查」标签（须 FX 线程调用）。 */
    private void refreshLastCheck() {
        if (lastCheckLabel != null) {
            lastCheckLabel.setText(lastCheckText());
        }
    }

    /** 手动检查更新：驱动状态行；命中新版后下载并校验，再提示拉起安装。 */
    private void handleCheckUpdate() {
        Window owner = host.hostWindow();
        if (checkButton != null) {
            checkButton.setDisable(true);
        }
        setStatus(I18n.t("settings.update.checking"));
        Thread t = new Thread(() -> {
            try {
                Optional<UpdateChecker.UpdateInfo> info = updateChecker.checkLatest();
                updateChecker.markCheckedToday();
                Platform.runLater(() -> {
                    refreshLastCheck();
                    if (checkButton != null) {
                        checkButton.setDisable(false);
                    }
                    if (info.isPresent()) {
                        setStatus(I18n.t("settings.update.status.found", info.get().version()));
                        if (info.get().forceUpdate()) {
                            updateChecker.beginForced(owner, info.get(), this::setStatus);
                        } else {
                            confirmDownload(owner, info.get());
                        }
                    } else {
                        setStatus(I18n.t("settings.update.status.latest", updateChecker.currentVersion()));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (checkButton != null) {
                        checkButton.setDisable(false);
                    }
                    String err = UpdateChecker.describeError(e);
                    setStatus(I18n.t("settings.update.status.failed", err));
                    host.notify("settings.update.check", I18n.t("settings.update.fail", err), JworkDialog.Level.ERROR);
                });
            }
        }, "jwork-update-check");
        t.setDaemon(true);
        t.start();
    }

    /** 发现新版：询问是否下载，确认后后台下载并校验（进度写状态行），完成再询问是否安装。 */
    private void confirmDownload(Window owner, UpdateChecker.UpdateInfo info) {
        int go = JworkDialog.choose(owner, I18n.t("settings.update.found.title"), null,
                I18n.t("settings.update.found.body", updateChecker.currentVersion(), info.version()),
                I18n.t("settings.update.download"), I18n.t("common.cancel"));
        if (go != 0) {
            setStatus(I18n.t("settings.update.status.cancelled"));
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Path file = updateChecker.download(info, ratio ->
                        Platform.runLater(() -> setStatus(I18n.t(
                                "settings.update.status.downloading", (int) Math.round(ratio * 100)))));
                Platform.runLater(() -> {
                    setStatus(I18n.t("settings.update.status.downloaded"));
                    confirmInstall(owner, file);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    String err = UpdateChecker.describeError(e);
                    setStatus(I18n.t("settings.update.status.downloadFailed", err));
                    host.notify("settings.update.check", I18n.t("settings.update.downloadFail", err), JworkDialog.Level.ERROR);
                });
            }
        }, "jwork-update-download");
        t.setDaemon(true);
        t.start();
    }

    /** 下载完成：询问是否立即拉起安装。 */
    private void confirmInstall(Window owner, Path file) {
        int go = JworkDialog.choose(owner, I18n.t("settings.update.install.title"), null,
                I18n.t("settings.update.install.body", file.getFileName().toString()),
                I18n.t("settings.update.install"), I18n.t("common.cancel"));
        if (go == 0) {
            boolean ok = updateChecker.install(file);
            if (!ok) {
                host.notify("settings.update.check", I18n.t("settings.update.installFail"), JworkDialog.Level.ERROR);
            }
        }
    }
}
