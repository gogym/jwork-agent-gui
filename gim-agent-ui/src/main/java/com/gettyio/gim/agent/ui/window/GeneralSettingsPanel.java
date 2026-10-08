package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.backup.DbBackupService;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.control.ToggleSwitch;
import com.gettyio.gim.agent.ui.desktop.GlobalHotkeyService;
import com.gettyio.gim.agent.ui.export.ExportService;
import com.gettyio.gim.agent.ui.sync.WebDavSyncService;
import com.gettyio.gim.agent.ui.voice.VoiceInputService;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
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
 * 通用设置子面板：数据与路径概览、启动与关闭行为（含开机自启、全局热键）、数据维护
 * （立即备份 / 导出会话（Markdown·PDF·DOCX）/ 导出全库 JSON / 打开目录）。抽自 {@link SettingsWindow}，
 * 依赖 {@link AppLifecycle}/{@link GlobalHotkeyService}/{@link DbBackupService}/{@link ExportService}
 * 与宿主弹窗归属/提示回调；「最近备份」行在备份完成后就地刷新。
 */
final class GeneralSettingsPanel {

    private final SettingsWindow host;
    private final GimPaths gimPaths;
    private final AppLifecycle appLifecycle;
    private final GlobalHotkeyService globalHotkeyService;
    private final DbBackupService dbBackupService;
    private final ExportService exportService;
    /** 读应用级设置（月度用量预算键）；仅取 settingDao 用途 */
    private final GimDatabase database;
    private final WebDavSyncService webDavSyncService;
    private final VoiceInputService voiceInputService;

    /** 通用页「最近备份」值标签：备份完成后就地刷新。 */
    private final Label lastBackupValueLabel = new Label("-");

    GeneralSettingsPanel(SettingsWindow host, GimPaths gimPaths, AppLifecycle appLifecycle,
                         GlobalHotkeyService globalHotkeyService, DbBackupService dbBackupService,
                         ExportService exportService, GimDatabase database,
                         WebDavSyncService webDavSyncService, VoiceInputService voiceInputService) {
        this.host = host;
        this.gimPaths = gimPaths;
        this.appLifecycle = appLifecycle;
        this.globalHotkeyService = globalHotkeyService;
        this.dbBackupService = dbBackupService;
        this.exportService = exportService;
        this.database = database;
        this.webDavSyncService = webDavSyncService;
        this.voiceInputService = voiceInputService;
    }

    VBox build() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.general"), I18n.t("settings.general.subtitle"));
        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.dataDir"), gimPaths.root()));
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.workspace"), gimPaths.workspaceDir()));
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.skillsDir"), gimPaths.skillsDir()));
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.knowledgeDir"), gimPaths.knowledgeDir()));
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.logsDir"), gimPaths.logsDir()));
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.backupsDir"), gimPaths.backupsDir()));
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.db"), gimPaths.dbFile()));
        lastBackupValueLabel.getStyleClass().add("row-value");
        rows.add(SettingsLayout.settingRow(I18n.t("settings.backup.lastAt"), null, lastBackupValueLabel));
        box.getChildren().add(SettingsLayout.groupCard(I18n.t("settings.group.paths"), rows));
        box.getChildren().add(behaviorCard());
        box.getChildren().add(dataMaintenanceCard());
        box.getChildren().add(budgetCard());
        box.getChildren().add(webDavCard());
        box.getChildren().add(voiceCard());
        refreshLastBackupRow();
        return box;
    }

    /** 启动与关闭卡片：关闭行为（退出/最小化到托盘）与开机自启（仅打包 exe 可用）。 */
    private VBox behaviorCard() {
        String[] opts = {I18n.t("settings.close.tray"), I18n.t("settings.close.exit")};
        ComboBox<String> closeCombo = SettingsLayout.pillCombo(opts);
        boolean exitAction = AppLifecycle.CLOSE_EXIT.equals(
                appLifecycle.readCloseAction().orElse(AppLifecycle.CLOSE_TRAY));
        closeCombo.setValue(opts[exitAction ? 1 : 0]);
        closeCombo.setOnAction(e -> {
            boolean exit = closeCombo.getSelectionModel().getSelectedIndex() == 1;
            appLifecycle.setCloseAction(exit ? AppLifecycle.CLOSE_EXIT : AppLifecycle.CLOSE_TRAY);
        });

        boolean manageable = appLifecycle.canManageAutostart();
        Node autoControl;
        if (manageable) {
            ToggleSwitch autostart = new ToggleSwitch();
            autostart.setSelected(appLifecycle.isAutostartEnabled());
            autostart.selectedProperty().addListener((obs, o, sel) -> {
                if (!appLifecycle.setAutostart(sel)) {
                    autostart.setSelected(!sel);
                    host.notify("settings.autostart", I18n.t("settings.status.autostartFail"));
                }
            });
            autoControl = autostart;
        } else {
            Label na = new Label(I18n.t("settings.autostart.unavailable"));
            na.getStyleClass().add("row-value");
            autoControl = na;
        }

        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.settingRow(I18n.t("settings.close.action"), I18n.t("settings.close.action.desc"), closeCombo));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.autostart"), I18n.t("settings.autostart.desc"), autoControl));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.hotkey"), I18n.t("settings.hotkey.desc"), hotkeyControl()));
        return SettingsLayout.groupCard(I18n.t("settings.group.startupClose"), rows);
    }

    /**
     * 全局热键开关控件：仅 Windows 可用（与 {@link GlobalHotkeyService#isSupported()} 同口径）；
     * 切换即写 {@code app.hotkey.enabled} 并即时注册/反注册。不支持平台显示不可用文案。
     */
    private Node hotkeyControl() {
        if (!globalHotkeyService.isSupported()) {
            Label na = new Label(I18n.t("settings.hotkey.unavailable"));
            na.getStyleClass().add("row-value");
            return na;
        }
        ToggleSwitch hotkey = new ToggleSwitch();
        hotkey.setSelected(globalHotkeyService.isEnabled());
        hotkey.selectedProperty().addListener((obs, o, sel) -> globalHotkeyService.setEnabled(sel));
        return hotkey;
    }

    /**
     * 用量预算卡片：月预算上限（元），失焦/回车保存。空或 ≤0 表示不启用告警；
     * 非法输入回滚并提示。超限提醒由 {@code ModelRateGovernor} 流完成后触发。
     */
    private VBox budgetCard() {
        TextField budgetField = new TextField();
        budgetField.setText(database.settingDao()
                .getValue(com.gettyio.gim.agent.ui.throttle.ModelRateGovernor.KEY_MONTHLY_BUDGET).orElse(""));
        budgetField.setPromptText(I18n.t("usage.budget.prompt"));
        budgetField.setMaxWidth(160);
        Runnable save = () -> saveMonthlyBudget(budgetField);
        budgetField.setOnAction(e -> save.run());
        budgetField.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                save.run();
            }
        });
        return SettingsLayout.groupCard(I18n.t("usage.budget.group"), List.of(
                SettingsLayout.settingRow(I18n.t("usage.budget.label"),
                        I18n.t("usage.budget.desc"), budgetField)));
    }

    /** 校验并保存月预算输入：空串清零（不启用）、非负数入库，非法值回滚并提示。 */
    private void saveMonthlyBudget(TextField budgetField) {
        String raw = budgetField.getText() == null ? "" : budgetField.getText().trim();
        String toSave = raw;
        if (!raw.isEmpty()) {
            try {
                double d = Double.parseDouble(raw);
                if (d < 0) {
                    throw new NumberFormatException();
                }
                toSave = String.valueOf(d);
            } catch (NumberFormatException e) {
                budgetField.setText(database.settingDao()
                        .getValue(com.gettyio.gim.agent.ui.throttle.ModelRateGovernor.KEY_MONTHLY_BUDGET).orElse(""));
                host.notify("usage.budget", I18n.t("usage.budget.invalid"));
                return;
            }
        }
        database.settingDao().upsert(
                com.gettyio.gim.agent.ui.throttle.ModelRateGovernor.KEY_MONTHLY_BUDGET, toSave, "月度用量预算上限（元）");
    }

    /**
     * WebDAV 云同步卡片：地址/账号/密码（SecretCipher 加密存 settings）、自动同步周期、
     * 测试连接与立即同步。文本框失焦/回车即保存，避免额外确认步骤。
     */
    private VBox webDavCard() {
        TextField urlField = new TextField(webDavSyncService.url());
        urlField.setPromptText(I18n.t("settings.sync.url.prompt"));
        urlField.setMaxWidth(360);
        TextField userField = new TextField(webDavSyncService.username());
        userField.setMaxWidth(200);
        PasswordField pwdField = new PasswordField();
        pwdField.setText(webDavSyncService.passwordPlain());
        pwdField.setMaxWidth(200);

        String[] intervalOpts = {I18n.t("settings.sync.interval.off"), I18n.t("settings.sync.interval.6h"),
                I18n.t("settings.sync.interval.12h"), I18n.t("settings.sync.interval.24h")};
        ComboBox<String> intervalCombo = SettingsLayout.pillCombo(intervalOpts);
        intervalCombo.setValue(intervalOpts[intervalIndex(webDavSyncService.intervalHours())]);

        Runnable save = () -> webDavSyncService.save(urlField.getText(), userField.getText(),
                pwdField.getText(), intervalHours(intervalCombo.getSelectionModel().getSelectedIndex()));
        intervalCombo.setOnAction(e -> save.run());
        urlField.setOnAction(e -> save.run());
        urlField.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                save.run();
            }
        });
        userField.setOnAction(e -> save.run());
        userField.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                save.run();
            }
        });
        pwdField.setOnAction(e -> save.run());
        pwdField.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                save.run();
            }
        });

        Button test = SettingsLayout.iconTextButton("\uE895", I18n.t("settings.sync.test"), this::handleTestConnection);
        Button syncNow = SettingsLayout.iconTextButton("\uE895", I18n.t("settings.sync.syncNow"), this::handleSyncNow);
        FlowPane buttons = new FlowPane(8, 8, test, syncNow);

        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.settingRow(I18n.t("settings.sync.url"), I18n.t("settings.sync.url.desc"), urlField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.sync.username"),
                I18n.t("settings.sync.username.desc"), userField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.sync.password"),
                I18n.t("settings.sync.password.desc"), pwdField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.sync.interval"),
                I18n.t("settings.sync.interval.desc"), intervalCombo));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.sync.actions"), null, buttons));
        return SettingsLayout.groupCard(I18n.t("settings.sync.group"), rows);
    }

    /**
     * 语音输入卡片：转写服务地址 / 模型 / API Key（SecretCipher 加密存 settings），
     * 文本框失焦/回车即保存，改后按住说话按钮即生效。
     */
    private VBox voiceCard() {
        TextField urlField = new TextField(voiceInputService.transcriptionBaseUrl());
        urlField.setPromptText(I18n.t("settings.voice.url.prompt"));
        urlField.setMaxWidth(360);
        TextField modelField = new TextField(voiceInputService.transcriptionModel());
        modelField.setPromptText(I18n.t("settings.voice.model.prompt"));
        modelField.setMaxWidth(200);
        PasswordField keyField = new PasswordField();
        keyField.setText(voiceInputService.apiKeyPlain());
        keyField.setMaxWidth(200);

        Runnable save = () -> {
            voiceInputService.saveTranscriptionBaseUrl(urlField.getText());
            voiceInputService.saveTranscriptionModel(modelField.getText());
            voiceInputService.saveApiKey(keyField.getText());
        };
        for (TextField f : List.of(urlField, modelField, keyField)) {
            f.setOnAction(e -> save.run());
            f.focusedProperty().addListener((obs, was, now) -> {
                if (!now) {
                    save.run();
                }
            });
        }
        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.settingRow(I18n.t("settings.voice.url"),
                I18n.t("settings.voice.url.desc"), urlField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.voice.model"),
                I18n.t("settings.voice.model.desc"), modelField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.voice.apiKey"),
                I18n.t("settings.voice.apiKey.desc"), keyField));
        return SettingsLayout.groupCard(I18n.t("settings.voice.group"), rows);
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

    /** 立即同步：VACUUM INTO 快照 + workspace 压缩包上传（后台线程）。 */
    private void handleSyncNow() {
        runInBackground("settings.sync.syncNow", () -> {
            try {
                Path p = webDavSyncService.syncNow();
                return Optional.of(I18n.t("settings.sync.done", p.getFileName().toString()));
            } catch (Exception e) {
                return Optional.of(I18n.t("settings.sync.failShort", String.valueOf(e.getMessage())));
            }
        }, null);
    }

    /** 数据维护卡片：立即备份 / 导出会话 / 导出全库 / 打开备份目录 / 打开数据目录。 */
    private VBox dataMaintenanceCard() {
        Label gt = new Label(I18n.t("settings.group.maintenance"));
        gt.getStyleClass().add("settings-group-title");

        Button backup = SettingsLayout.iconTextButton("\uE7B5", I18n.t("settings.backup.now"), this::handleBackupNow);
        Button exportSession = SettingsLayout.iconTextButton("\uE8A5", I18n.t("settings.export.session"), this::handleExportSession);
        Button exportFull = SettingsLayout.iconTextButton("\uE8A5", I18n.t("settings.export.full"), this::handleExportFull);
        Button openBackup = SettingsLayout.iconTextButton("\uE838", I18n.t("settings.data.openBackup"),
                () -> revealDir(gimPaths.backupsDir()));
        Button openRoot = SettingsLayout.iconTextButton("\uE838", I18n.t("settings.data.openRoot"),
                () -> revealDir(gimPaths.root()));

        Label hint = new Label(I18n.t("settings.backup.hint"));
        hint.getStyleClass().add("row-desc");
        hint.setWrapText(true);

        FlowPane buttons = new FlowPane(8, 8, backup, exportSession, exportFull, openBackup, openRoot);
        VBox card = new VBox(8, hint, buttons);
        card.getStyleClass().add("settings-group");
        return new VBox(6, gt, card);
    }

    private String formatLastBackup() {
        return dbBackupService.lastBackupAt()
                .map(i -> i.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .orElse(I18n.t("settings.backup.none"));
    }

    /** 刷新「最近备份」行文本（通用页重建与备份完成后调用）。 */
    private void refreshLastBackupRow() {
        lastBackupValueLabel.setText(formatLastBackup());
    }

    /** 立即备份（后台线程执行 VACUUM，完成后刷新本页并弹框告知）。 */
    private void handleBackupNow() {
        runInBackground("settings.backup.now",
                () -> dbBackupService.backupNow().map(p -> I18n.t("settings.backup.done", p.getFileName())),
                this::refreshLastBackupRow);
    }

    /** 导出单个会话为 Markdown/PDF/DOCX：先选会话，再选保存路径。 */
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
        chooser.getExtensionFilters().addAll(md, pdf, docx);
        File file = chooser.showSaveDialog(host.hostWindow());
        if (file == null) {
            return;
        }
        String sessionId = chosen.getSessionId();
        FileChooser.ExtensionFilter sel = chooser.getSelectedExtensionFilter();
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

    /** 导出全库为 JSON。 */
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
                () -> Optional.of(I18n.t("settings.export.done",
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
                host.notify("settings.menu.general", I18n.t("settings.data.openFail", "Desktop unsupported"));
            }
        } catch (Exception e) {
            host.notify("settings.menu.general", I18n.t("settings.data.openFail", e.getMessage()));
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
