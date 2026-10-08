package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.backup.DbBackupService;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.control.ToggleSwitch;
import com.gettyio.gim.agent.ui.desktop.GlobalHotkeyService;
import com.gettyio.gim.agent.ui.export.ExportService;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
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

    /** 通用页「最近备份」值标签：备份完成后就地刷新。 */
    private final Label lastBackupValueLabel = new Label("-");

    GeneralSettingsPanel(SettingsWindow host, GimPaths gimPaths, AppLifecycle appLifecycle,
                         GlobalHotkeyService globalHotkeyService, DbBackupService dbBackupService,
                         ExportService exportService) {
        this.host = host;
        this.gimPaths = gimPaths;
        this.appLifecycle = appLifecycle;
        this.globalHotkeyService = globalHotkeyService;
        this.dbBackupService = dbBackupService;
        this.exportService = exportService;
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
