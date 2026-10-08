package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.control.ToggleSwitch;
import com.gettyio.gim.agent.ui.update.UpdateChecker;
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
 * 更新设置子面板（独立「更新」菜单页，抽自 {@link ProfileSettingsPanel}）：当前版本、上次检查、
 * 更新服务器地址、每日静默检查、立即检查与实时状态，并承载「检查→下载→校验→拉起安装」的手动更新流程。
 * 依赖 {@link UpdateChecker} 与宿主弹窗归属/提示回调。
 */
final class UpdateSettingsPanel {

    private final SettingsWindow host;
    private final UpdateChecker updateChecker;

    /** 更新卡片动态控件：随检查/下载流程刷新状态与上次检查时间。 */
    private Button checkButton;
    private Label statusLabel;
    private Label lastCheckLabel;

    UpdateSettingsPanel(SettingsWindow host, UpdateChecker updateChecker) {
        this.host = host;
        this.updateChecker = updateChecker;
    }

    VBox build() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.update"), I18n.t("settings.update.subtitle"));
        box.getChildren().add(updateCard());
        return box;
    }

    /** 更新卡片：当前版本、上次检查、服务器地址、每日静默检查、立即检查与实时状态。 */
    private VBox updateCard() {
        Button check = SettingsLayout.iconTextButton("\uE777", I18n.t("settings.update.check"), this::handleCheckUpdate);
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
        rows.add(SettingsLayout.settingRow(I18n.t("settings.update.currentVersion"), null,
                SettingsLayout.valueLabel(updateChecker.currentVersion())));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.update.lastCheck"), null, lastCheckLabel));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.update.server"), I18n.t("settings.update.server.desc"), serverField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.update.auto"), I18n.t("settings.update.auto.desc"), auto));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.update.check"), I18n.t("settings.update.check.desc"), check));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.update.status"), null, statusLabel));
        // 独立页已有标题「更新」，分组标题留空避免重复
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
                    String err = String.valueOf(e.getMessage());
                    setStatus(I18n.t("settings.update.status.failed", err));
                    host.notify("settings.update.check", I18n.t("settings.update.fail", err));
                });
            }
        }, "gim-update-check");
        t.setDaemon(true);
        t.start();
    }

    /** 发现新版：询问是否下载，确认后后台下载并校验（进度写状态行），完成再询问是否安装。 */
    private void confirmDownload(Window owner, UpdateChecker.UpdateInfo info) {
        int go = GimDialog.choose(owner, I18n.t("settings.update.found.title"), null,
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
                    String err = String.valueOf(e.getMessage());
                    setStatus(I18n.t("settings.update.status.downloadFailed", err));
                    host.notify("settings.update.check", I18n.t("settings.update.downloadFail", err));
                });
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
