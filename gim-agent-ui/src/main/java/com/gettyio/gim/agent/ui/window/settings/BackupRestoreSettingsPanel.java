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

package com.gettyio.gim.agent.ui.window.settings;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.backup.DbBackupService;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.ui.control.CardKit;
import com.gettyio.gim.agent.ui.control.SecretField;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.export.ExportService;
import com.gettyio.gim.agent.ui.export.SessionImageExporter;
import com.gettyio.gim.agent.ui.sync.WebDavSyncService;
import com.gettyio.gim.agent.ui.window.dialog.GimDialog;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 数据与备份设置子面板：数据与路径只读概览、本地数据维护（立即备份 / 导出会话（Markdown·PDF·DOCX·
 * 长图 PNG·独立 HTML）/ 导出全库 JSON / 打开目录）与 WebDAV 云同步（地址·账号·密码·加密口令、
 * 自动周期、测试连接 / 立即同步 / 从云端恢复）。抽自 {@link SettingsWindow}，依赖
 * {@link DbBackupService}/{@link ExportService}/{@link WebDavSyncService}；
 * 「最近备份」「最近同步」行就地刷新，同步结果经监听器回传（自动同步失败亦可见）。
 *
 * @author gogym
 */
final class BackupRestoreSettingsPanel {

    private final SettingsWindow host;
    private final GimPaths gimPaths;
    private final DbBackupService dbBackupService;
    private final ExportService exportService;
    private final WebDavSyncService webDavSyncService;

    /** 本页「最近备份」值标签：备份完成后就地刷新。 */
    private final Label lastBackupValueLabel = new Label("-");

    /** 本页「最近同步」值标签：手动/自动同步回调后就地刷新（失败显示失败原因）。 */
    private final Label webDavLastSyncLabel = new Label("-");

    /** 本页「待恢复」行：跨重启存活的恢复队列在此现形，附撤销入口。 */
    private final Label pendingRestoreValueLabel = new Label("-");

    /** 撤销待恢复按钮：无排队恢复时收起，避免点出一句「没什么可撤的」。 */
    private Button pendingCancelBtn;

    /** WebDAV 卡片操作按钮：同步/恢复进行中统一禁用以防连点排队重复操作。 */
    private Button webDavTestButton;
    private Button webDavSyncButton;
    private Button webDavRestoreButton;

    BackupRestoreSettingsPanel(SettingsWindow host, GimPaths gimPaths, DbBackupService dbBackupService,
                               ExportService exportService, WebDavSyncService webDavSyncService) {
        this.host = host;
        this.gimPaths = gimPaths;
        this.dbBackupService = dbBackupService;
        this.exportService = exportService;
        this.webDavSyncService = webDavSyncService;
        // 同步结果监听：成功刷新「最近同步」，失败就地展示失败原因（自动同步失败亦可见）
        webDavSyncService.addSyncListener(result -> Platform.runLater(() -> {
            if (result.success()) {
                refreshLastSyncRow();
            } else {
                webDavLastSyncLabel.setText(I18n.t("settings.sync.failed", result.message()));
            }
        }));
    }

    VBox build() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.backupRestore"),
                I18n.t("settings.backupRestore.subtitle"));
        box.getChildren().add(pathsCard());
        box.getChildren().add(dataMaintenanceCard());
        box.getChildren().add(webDavCard());
        refreshLastBackupRow();
        refreshLastSyncRow();
        return box;
    }

    /** 数据与路径卡片：各数据目录的只读概览（自通用页迁入，与备份/打开目录操作聚合于一页）。 */
    private VBox pathsCard() {
        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.dataDir"), gimPaths.root()));
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.workspace"), gimPaths.workspaceDir()));
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.skillsDir"), gimPaths.skillsDir()));
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.knowledgeDir"), gimPaths.knowledgeDir()));
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.logsDir"), gimPaths.logsDir()));
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.db"), gimPaths.dbFile()));
        return SettingsLayout.groupCard(I18n.t("settings.group.paths"), rows);
    }

    /**
     * 数据维护卡片：备份目录与最近备份信息 + 立即备份 / 导出会话 / 导出全库 /
     * 打开备份目录 / 打开数据目录。
     */
    private VBox dataMaintenanceCard() {
        lastBackupValueLabel.getStyleClass().add("row-value");
        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.backupsDir"), gimPaths.backupsDir()));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.backup.lastAt"), null, lastBackupValueLabel));

        Button backup = CardKit.iconTextButton("\uE7B5", I18n.t("settings.backup.now"), this::handleBackupNow);
        Button exportSession = CardKit.iconTextButton("\uE8A5", I18n.t("settings.export.session"), this::handleExportSession);
        Button exportFull = CardKit.iconTextButton("\uE8A5", I18n.t("settings.export.full"), this::handleExportFull);
        Button openBackup = CardKit.iconTextButton("\uE838", I18n.t("settings.data.openBackup"),
                () -> revealDir(gimPaths.backupsDir()));
        Button openRoot = CardKit.iconTextButton("\uE838", I18n.t("settings.data.openRoot"),
                () -> revealDir(gimPaths.root()));

        Label hint = new Label(I18n.t("settings.backup.hint"));
        hint.getStyleClass().add("row-desc");
        hint.setWrapText(true);

        FlowPane buttons = new FlowPane(8, 8, backup, exportSession, exportFull, openBackup, openRoot);
        VBox body = new VBox(8, hint, buttons);
        // 行式卡片的内容内衬 = 卡片 4px + settings-row 14px；本卡为手工内容无行包装，在内层无样式类
        // 容器上补足等量内衬（直接给卡片 setPadding 会被 settings-group 样式表的 -fx-padding 覆盖）
        body.setPadding(new Insets(14, 0, 14, 0));
        rows.add(body);
        return SettingsLayout.groupCard(I18n.t("settings.group.maintenance"), rows);
    }

    /**
     * WebDAV 云同步卡片：地址/账号/密码/加密口令（密码与口令经 SecretStore 加密存 settings、
     * 不回显也不因留空而清除）、
     * 自动同步周期、最近同步时间、测试连接 / 立即同步 / 从云端恢复。
     * 文本框失焦/回车即保存，避免额外确认步骤。
     */
    private VBox webDavCard() {
        TextField urlField = new TextField(webDavSyncService.url());
        urlField.setPromptText(I18n.t("settings.sync.url.prompt"));
        urlField.setMaxWidth(360);
        TextField userField = new TextField(webDavSyncService.username());
        userField.setMaxWidth(200);
        SecretField pwd = new SecretField(webDavSyncService::passwordState,
                webDavSyncService::savePassword, webDavSyncService::clearPassword);
        pwd.field().setPromptText(I18n.t("settings.secret.prompt"));
        pwd.field().setMaxWidth(200);
        SecretField pass = new SecretField(webDavSyncService::passphraseState,
                webDavSyncService::savePassphrase, webDavSyncService::clearPassphrase);
        pass.field().setPromptText(I18n.t("settings.secret.prompt"));
        pass.field().setMaxWidth(200);

        String[] intervalOpts = {I18n.t("settings.sync.interval.off"), I18n.t("settings.sync.interval.6h"),
                I18n.t("settings.sync.interval.12h"), I18n.t("settings.sync.interval.24h")};
        ComboBox<String> intervalCombo = SettingsLayout.pillCombo(intervalOpts);
        intervalCombo.setValue(intervalOpts[intervalIndex(webDavSyncService.intervalHours())]);

        // 地址/账号/周期直接覆盖；两个密钥项由 SecretField 各自提交（留空不改动已存值）
        Runnable saveBasics = () -> webDavSyncService.save(urlField.getText(), userField.getText(), "", "",
                intervalHours(intervalCombo.getSelectionModel().getSelectedIndex()));
        intervalCombo.setOnAction(e -> saveBasics.run());
        for (TextField f : List.of(urlField, userField)) {
            f.setOnAction(e -> saveBasics.run());
            f.focusedProperty().addListener((obs, was, now) -> {
                if (!now) {
                    saveBasics.run();
                }
            });
        }

        webDavTestButton = CardKit.iconTextButton("\uE73E", I18n.t("settings.sync.test"), this::handleTestConnection);
        webDavSyncButton = CardKit.iconTextButton("\uE895", I18n.t("settings.sync.syncNow"), this::handleSyncNow);
        webDavRestoreButton = CardKit.iconTextButton("\uE896", I18n.t("settings.sync.restore"), this::handleRestore);
        FlowPane buttons = new FlowPane(8, 8, webDavTestButton, webDavSyncButton, webDavRestoreButton);

        webDavLastSyncLabel.getStyleClass().add("row-value");
        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.settingRow(I18n.t("settings.sync.url"), I18n.t("settings.sync.url.desc"), urlField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.sync.username"),
                I18n.t("settings.sync.username.desc"), userField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.sync.password"),
                I18n.t("settings.sync.password.desc"), pwd.control()));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.sync.passphrase"),
                I18n.t("settings.sync.passphrase.desc"), pass.control()));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.sync.interval"),
                I18n.t("settings.sync.interval.desc"), intervalCombo));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.sync.lastAt"), null, webDavLastSyncLabel));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.sync.pending"), null, pendingRestoreControl()));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.sync.actions"), null, buttons));
        return SettingsLayout.groupCard(I18n.t("settings.sync.group"), rows);
    }

    /** 周期小时数转下拉索引：0/6/12/24 → 0..3，未知值归关闭。 */
    private static int intervalIndex(int hours) {
        return switch (hours) {
            case 6 -> 1;
            case 12 -> 2;
            case 24 -> 3;
            default -> 0;
        };
    }

    /** 下拉索引转周期小时数。 */
    private static int intervalHours(int index) {
        return switch (index) {
            case 1 -> 6;
            case 2 -> 12;
            case 3 -> 24;
            default -> 0;
        };
    }

    /** 测试 WebDAV 连通性（后台线程 PROPFIND 列目录）。 */
    private void handleTestConnection() {
        runInBackground("settings.sync.test", () -> {
            try {
                webDavSyncService.testConnection();
                return Optional.of(I18n.t("settings.sync.ok"));
            } catch (Exception e) {
                return Optional.of(I18n.t("settings.sync.fail", String.valueOf(e.getMessage())));
            }
        }, null);
    }

    /** 立即同步：快照 + workspace 压缩包上传（后台线程；进行中禁用按钮防连点排队）。 */
    private void handleSyncNow() {
        setSyncBusy(true);
        runInBackground("settings.sync.syncNow", () -> {
            try {
                Path p = webDavSyncService.syncNow();
                return Optional.of(I18n.t("settings.sync.done", p.getFileName().toString()));
            } catch (Exception e) {
                return Optional.of(I18n.t("settings.sync.failShort", String.valueOf(e.getMessage())));
            }
        }, () -> {
            setSyncBusy(false);
            refreshLastSyncRow();
        });
    }

    /**
     * 从云端恢复：后台列远端快照 → 选择 → 下载校验后落待恢复文件；
     * 提示重启应用完成替换（启动时由 RestoreApplier 应用，旧库自动先备份）。
     */
    private void handleRestore() {
        setSyncBusy(true);
        Thread t = new Thread(() -> {
            List<WebDavSyncService.RemoteSnapshot> snapshots;
            String listError = null;
            try {
                snapshots = webDavSyncService.listRemoteSnapshots();
            } catch (Exception e) {
                snapshots = List.of();
                listError = String.valueOf(e.getMessage());
            }
            final List<WebDavSyncService.RemoteSnapshot> list = snapshots;
            final String error = listError;
            Platform.runLater(() -> {
                if (error != null) {
                    setSyncBusy(false);
                    host.notify("settings.sync.restore", I18n.t("settings.sync.restore.listFail", error));
                    return;
                }
                if (list.isEmpty()) {
                    setSyncBusy(false);
                    host.notify("settings.sync.restore", I18n.t("settings.sync.restore.none"));
                    return;
                }
                String[] labels = list.stream()
                        .map(s -> s.display() + (s.name().endsWith(WebDavSyncService.ENC_SUFFIX)
                                ? " " + I18n.t("settings.sync.restore.encTag") : ""))
                        .toArray(String[]::new);
                int idx = GimDialog.choose(host.hostWindow(), I18n.t("settings.sync.restore"), null,
                        I18n.t("settings.sync.restore.pick"), labels);
                if (idx < 0 || idx >= list.size()) {
                    setSyncBusy(false);
                    return;
                }
                WebDavSyncService.RemoteSnapshot chosen = list.get(idx);
                if (chosen.name().endsWith(WebDavSyncService.ENC_SUFFIX)
                        && webDavSyncService.passphrasePlain().isEmpty()) {
                    setSyncBusy(false);
                    host.notify("settings.sync.restore", I18n.t("settings.sync.restore.noPassphrase"));
                    return;
                }
                // 这一步只下载并排队，换库发生在下次启动：此刻必须讲清「换成哪份、现有数据怎么办」，
                // 否则恢复成了用户自己埋下、启动时才炸的哑弹
                if (!GimDialog.confirm(host.hostWindow(), I18n.t("settings.sync.restore"),
                        I18n.t("settings.sync.restore.confirm", chosen.display()))) {
                    setSyncBusy(false);
                    return;
                }
                runInBackground("settings.sync.restore", () -> {
                    try {
                        webDavSyncService.restoreFromRemote(chosen.name());
                        return Optional.of(I18n.t("settings.sync.restore.ready"));
                    } catch (Exception e) {
                        return Optional.of(I18n.t("settings.sync.restore.fail", String.valueOf(e.getMessage())));
                    }
                }, () -> {
                    setSyncBusy(false);
                    refreshPendingRestoreRow();
                });
            });
        }, "gim-webdav-restore");
        t.setDaemon(true);
        t.start();
    }

    /** 同步相关操作进行中：三枚按钮统一禁用，避免重复排队的上传/下载。 */
    private void setSyncBusy(boolean busy) {
        if (webDavTestButton != null) {
            webDavTestButton.setDisable(busy);
        }
        if (webDavSyncButton != null) {
            webDavSyncButton.setDisable(busy);
        }
        if (webDavRestoreButton != null) {
            webDavRestoreButton.setDisable(busy);
        }
    }

    /** 刷新「最近同步」行文本（本页重建、手动同步完成与自动同步回调后调用）。 */
    private void refreshLastSyncRow() {
        String text = webDavSyncService.lastSyncAt()
                .map(i -> i.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .orElse(I18n.t("settings.sync.never"));
        webDavLastSyncLabel.setText(text);
    }

    /**
     * 「待恢复」行控件：待应用快照的说明 + 撤销按钮。
     * <p>恢复是排队到下次启动才生效的，中间这段时间本页一关就看不见，用户容易在毫不知情的情况下
     * 带着旧库启动、再被下次启动静默换掉。这里把它常驻呈现，并给一条反悔的路。</p>
     */
    private HBox pendingRestoreControl() {
        pendingRestoreValueLabel.getStyleClass().add("row-value");
        pendingCancelBtn = CardKit.iconTextButton("\uE711", I18n.t("settings.sync.pending.cancel"),
                this::handleCancelPendingRestore);
        pendingCancelBtn.setVisible(false);
        refreshPendingRestoreRow();
        return new HBox(8, pendingRestoreValueLabel, pendingCancelBtn);
    }

    /** 刷新「待恢复」行：无排队恢复时只留说明并收起按钮（重建页面与恢复排队/撤销后调用）。 */
    private void refreshPendingRestoreRow() {
        Optional<WebDavSyncService.PendingRestore> pending = webDavSyncService.pendingRestore();
        if (pending.isEmpty()) {
            pendingRestoreValueLabel.setText(I18n.t("settings.sync.pending.none"));
            pendingCancelBtn.setVisible(false);
            return;
        }
        WebDavSyncService.PendingRestore p = pending.get();
        String at = p.at().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        pendingRestoreValueLabel.setText(I18n.t("settings.sync.pending.desc",
                p.source().isBlank() ? "-" : p.source(), at));
        pendingCancelBtn.setVisible(true);
    }

    /** 撤销待恢复的快照：确认后删掉 pending，下次启动保持当前库不变。 */
    private void handleCancelPendingRestore() {
        if (!GimDialog.confirm(host.hostWindow(), I18n.t("settings.sync.pending.cancel"),
                I18n.t("settings.sync.pending.cancel.confirm"))) {
            return;
        }
        try {
            webDavSyncService.cancelPendingRestore();
            host.notify("settings.sync.pending", I18n.t("settings.sync.pending.cancelled"));
        } catch (Exception e) {
            host.notify("settings.sync.pending",
                    I18n.t("settings.data.fail", String.valueOf(e.getMessage())));
        }
        refreshPendingRestoreRow();
    }

    private String formatLastBackup() {
        return dbBackupService.lastBackupAt()
                .map(i -> i.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .orElse(I18n.t("settings.backup.none"));
    }

    /** 刷新「最近备份」行文本（本页重建与备份完成后调用）。 */
    private void refreshLastBackupRow() {
        lastBackupValueLabel.setText(formatLastBackup());
    }

    /** 立即备份（后台线程执行 VACUUM，完成后刷新本页并弹框告知）。 */
    private void handleBackupNow() {
        runInBackground("settings.backup.now",
                () -> dbBackupService.backupNow().map(p -> I18n.t("settings.backup.done", p.getFileName())),
                this::refreshLastBackupRow);
    }

    /** 导出单个会话为 Markdown/PDF/DOCX/长图 PNG/独立 HTML：先选会话，再选保存路径。 */
    private void handleExportSession() {
        List<ChatSession> sessions = exportServiceAllSessions();
        if (sessions.isEmpty()) {
            host.notify("settings.export.session", I18n.t("settings.export.noSession"));
            return;
        }
        String[] labels = sessions.stream()
                .map(s -> {
                    String t = s.getTitle() == null || s.getTitle().isBlank() ? s.getSessionId() : s.getTitle();
                    int n = s.getMessageCount() == null ? 0 : s.getMessageCount();
                    return t + "  （" + n + "）";
                })
                .toArray(String[]::new);
        int idx = GimDialog.choose(host.hostWindow(), I18n.t("settings.export.session"), null,
                I18n.t("settings.export.pickSession"), labels);
        if (idx < 0 || idx >= sessions.size()) {
            return;
        }
        ChatSession chosen = sessions.get(idx);
        String base = chosen.getTitle() == null || chosen.getTitle().isBlank() ? chosen.getSessionId() : chosen.getTitle();
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("settings.export.session"));
        chooser.setInitialFileName(exportService.suggestedFileName(base, ".md"));
        FileChooser.ExtensionFilter md = new FileChooser.ExtensionFilter("Markdown (*.md)", "*.md");
        FileChooser.ExtensionFilter pdf = new FileChooser.ExtensionFilter("PDF (*.pdf)", "*.pdf");
        FileChooser.ExtensionFilter docx = new FileChooser.ExtensionFilter("DOCX (*.docx)", "*.docx");
        FileChooser.ExtensionFilter png = new FileChooser.ExtensionFilter("PNG (*.png)", "*.png");
        FileChooser.ExtensionFilter html = new FileChooser.ExtensionFilter("HTML (*.html)", "*.html");
        chooser.getExtensionFilters().addAll(md, pdf, docx, png, html);
        File file = chooser.showSaveDialog(host.hostWindow());
        if (file == null) {
            return;
        }
        String sessionId = chosen.getSessionId();
        FileChooser.ExtensionFilter sel = chooser.getSelectedExtensionFilter();
        if (sel == png) {
            exportSessionPng(sessionId, withExtension(file.toPath(), ".png"));
            return;
        }
        if (sel == html) {
            Path htmlTarget = withExtension(file.toPath(), ".html");
            runInBackground("settings.export.session",
                    () -> Optional.of(I18n.t("settings.export.done",
                            exportService.exportSessionHtml(sessionId, htmlTarget).getFileName())),
                    null);
            return;
        }
        Path target = withExtension(file.toPath(), sel == pdf ? ".pdf" : sel == docx ? ".docx" : ".md");
        boolean asPdf = sel == pdf;
        boolean asDocx = sel == docx;
        runInBackground("settings.export.session",
                () -> Optional.of(I18n.t("settings.export.done",
                        (asPdf ? exportService.exportSessionPdf(sessionId, target)
                                : asDocx ? exportService.exportSessionDocx(sessionId, target)
                                        : exportService.exportSessionMarkdown(sessionId, target)).getFileName())),
                null);
    }

    /** 用户未按选中格式补后缀时自动补上，保证扩展名与导出内容一致。 */
    private static Path withExtension(Path p, String ext) {
        String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(ext) ? p : Path.of(p.toString() + ext);
    }

    /**
     * 导出会话为长图（PNG）：后台线程备好话题卡片 HTML，回 FX 线程交给
     * {@link SessionImageExporter} 离屏快照（其内部再异步写盘并回调反馈）。
     */
    private void exportSessionPng(String sessionId, Path target) {
        Thread t = new Thread(() -> {
            String html;
            try {
                html = exportService.renderSessionHtml(sessionId);
            } catch (RuntimeException e) {
                Platform.runLater(() -> host.notify("settings.export.session",
                        I18n.t("settings.data.fail", String.valueOf(e.getMessage()))));
                return;
            }
            Platform.runLater(() -> SessionImageExporter.export(host.hostWindow(), html, target,
                    saved -> host.notify("settings.export.session", I18n.t("settings.export.done", saved.getFileName())),
                    err -> host.notify("settings.export.session", err)));
        }, "gim-session-image");
        t.setDaemon(true);
        t.start();
    }

    /** 导出全库为 JSON（密钥类字段就地抹掉，导出件因此不是可回灌的备份）。 */
    private void handleExportFull() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("settings.export.full"));
        chooser.setInitialFileName(exportService.suggestedFileName("gim-agent-export", ".json"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        File file = chooser.showSaveDialog(host.hostWindow());
        if (file == null) {
            return;
        }
        Path target = file.toPath();
        runInBackground("settings.export.full",
                () -> Optional.of(I18n.t("settings.export.full.done",
                        exportService.exportFullJson(target).getFileName())),
                null);
    }

    /** 从库中取全部会话（导出选择用）。 */
    private List<ChatSession> exportServiceAllSessions() {
        return exportService.findSessions();
    }

    /** 用系统文件管理器打开目录；不支持时回落状态栏提示。 */
    private void revealDir(Path dir) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir.toFile());
            } else {
                host.notify("settings.menu.backupRestore", I18n.t("settings.data.openFail", "Desktop unsupported"));
            }
        } catch (Exception e) {
            host.notify("settings.menu.backupRestore", I18n.t("settings.data.openFail", e.getMessage()));
        }
    }

    /**
     * 后台线程执行耗时操作：完成或失败后以 {@code titleKey} 为标题弹框告知结果；
     * {@code onSuccess} 可空，非空时在 FX 线程回调（如刷新页面）。
     */
    private void runInBackground(String titleKey, Supplier<Optional<String>> work, Runnable onSuccess) {
        Thread t = new Thread(() -> {
            String result;
            try {
                result = work.get().orElse(null);
            } catch (RuntimeException e) {
                result = I18n.t("settings.data.fail", String.valueOf(e.getMessage()));
            }
            final String msg = result;
            Platform.runLater(() -> {
                if (msg != null && !msg.isBlank()) {
                    host.notify(titleKey, msg);
                }
                if (onSuccess != null) {
                    onSuccess.run();
                }
            });
        }, "gim-data-task");
        t.setDaemon(true);
        t.start();
    }
}
