package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.core.chat.HarnessAgentGateway;
import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.config.SecurityAdminService;
import com.gettyio.gim.agent.ui.control.ToggleSwitch;
import com.gettyio.gim.agent.ui.desktop.GlobalHotkeyService;
import com.gettyio.gim.agent.ui.voice.TtsService;
import com.gettyio.gim.agent.ui.voice.VoiceInputService;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用设置子面板：数据与路径概览、启动与关闭行为（含开机自启、全局热键）、上下文窗口上限、
 * 用量预算、语音输入/朗读与图像生成配置。抽自 {@link SettingsWindow}，依赖
 * {@link AppLifecycle}/{@link GlobalHotkeyService} 等与宿主弹窗归属/提示回调；
 * 本地备份/导出与云端同步已拆至 {@link BackupRestoreSettingsPanel}。
 */
final class GeneralSettingsPanel {

    private final SettingsWindow host;
    private final GimPaths gimPaths;
    private final AppLifecycle appLifecycle;
    private final GlobalHotkeyService globalHotkeyService;
    /** 读应用级设置（月度用量预算键）；仅取 settingDao 用途 */
    private final GimDatabase database;
    private final VoiceInputService voiceInputService;
    private final TtsService ttsService;
    /** 图像生成配置（地址/模型/Key）的读写后端：图像生成卡片保存即生效 */
    private final SecurityAdminService securityAdminService;
    /** 多智能体网关：上下文窗口上限变更后失效全部运行体，使新阈值于下一轮对话重新装配时生效。 */
    private final MultiAgentGateway agentGateway;

    GeneralSettingsPanel(SettingsWindow host, GimPaths gimPaths, AppLifecycle appLifecycle,
                         GlobalHotkeyService globalHotkeyService, GimDatabase database,
                         VoiceInputService voiceInputService, TtsService ttsService,
                         SecurityAdminService securityAdminService, MultiAgentGateway agentGateway) {
        this.host = host;
        this.gimPaths = gimPaths;
        this.appLifecycle = appLifecycle;
        this.globalHotkeyService = globalHotkeyService;
        this.database = database;
        this.voiceInputService = voiceInputService;
        this.ttsService = ttsService;
        this.securityAdminService = securityAdminService;
        this.agentGateway = agentGateway;
    }

    VBox build() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.general"), I18n.t("settings.general.subtitle"));
        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.dataDir"), gimPaths.root()));
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.workspace"), gimPaths.workspaceDir()));
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.skillsDir"), gimPaths.skillsDir()));
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.knowledgeDir"), gimPaths.knowledgeDir()));
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.logsDir"), gimPaths.logsDir()));
        rows.add(SettingsLayout.infoRow(I18n.t("settings.key.db"), gimPaths.dbFile()));
        box.getChildren().add(SettingsLayout.groupCard(I18n.t("settings.group.paths"), rows));
        box.getChildren().add(behaviorCard());
        box.getChildren().add(contextCard());
        box.getChildren().add(budgetCard());
        box.getChildren().add(voiceCard());
        box.getChildren().add(ttsCard());
        box.getChildren().add(imageCard());
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
     * 上下文窗口卡片：常用上限下拉。选中值写入 AppSetting {@code agent.context.maxTokens}，
     * 它既是输入框占用条的分母，也是运行体自动压缩历史（compaction）的阈值——两者同源。
     * 保存后失效全部运行体，下一轮对话按新上限重新装配即时生效。
     */
    private VBox contextCard() {
        String[] labels = contextLimitLabels();
        ComboBox<String> combo = SettingsLayout.pillCombo(labels);
        combo.setValue(formatTokenUnit(currentContextMaxTokens()));
        combo.setOnAction(e -> {
            int idx = combo.getSelectionModel().getSelectedIndex();
            if (idx >= 0 && idx < CONTEXT_LIMIT_OPTIONS.length) {
                saveContextMaxTokens(CONTEXT_LIMIT_OPTIONS[idx]);
            }
        });
        return SettingsLayout.groupCard(I18n.t("settings.context.group"), List.of(
                SettingsLayout.settingRow(I18n.t("settings.context.limit"),
                        I18n.t("settings.context.limit.desc"), combo)));
    }

    /** 常用上下文窗口上限档位（token）；标签按 {@link #formatTokenUnit} 生成（32K/128K/…/1M）。 */
    private static final int[] CONTEXT_LIMIT_OPTIONS = {32_000, 128_000, 200_000, 512_000, 1_000_000};

    /** 读取当前上限：未配置或非法回退到统一默认值。 */
    private int currentContextMaxTokens() {
        return database.settingDao().getValue(HarnessAgentGateway.SETTING_CONTEXT_MAX_TOKENS)
                .map(raw -> {
                    try {
                        int v = Integer.parseInt(raw.trim());
                        return v > 0 ? v : HarnessAgentGateway.DEFAULT_MAX_CONTEXT_TOKENS;
                    } catch (NumberFormatException ex) {
                        return HarnessAgentGateway.DEFAULT_MAX_CONTEXT_TOKENS;
                    }
                })
                .orElse(HarnessAgentGateway.DEFAULT_MAX_CONTEXT_TOKENS);
    }

    /** 保存上限并失效全部运行体，使新阈值于下一轮对话重新装配时生效。 */
    private void saveContextMaxTokens(int tokens) {
        database.settingDao().upsert(HarnessAgentGateway.SETTING_CONTEXT_MAX_TOKENS,
                String.valueOf(tokens), "上下文窗口 token 上限（占用条分母与自动压缩阈值）");
        agentGateway.invalidateAll();
    }

    /** 下拉选项标签（语言无关的数字单位）。 */
    private static String[] contextLimitLabels() {
        String[] labels = new String[CONTEXT_LIMIT_OPTIONS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = formatTokenUnit(CONTEXT_LIMIT_OPTIONS[i]);
        }
        return labels;
    }

    /** token 数简写：整百万显示为 xM，整千显示为 xK，其余原样数字。 */
    private static String formatTokenUnit(int tokens) {
        if (tokens >= 1_000_000 && tokens % 1_000_000 == 0) {
            return (tokens / 1_000_000) + "M";
        }
        if (tokens % 1000 == 0) {
            return (tokens / 1000) + "K";
        }
        return String.valueOf(tokens);
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

    /**
     * 语音朗读卡片：合成服务地址 / 模型 / 音色 / API Key（SecretCipher 加密存 settings），
     * 文本框失焦/回车即保存；「试听」先落存当前输入再合成一段示例文本（播放中再点会被新一轮打断）。
     */
    private VBox ttsCard() {
        TextField urlField = new TextField(ttsService.baseUrl());
        urlField.setPromptText(I18n.t("settings.tts.url.prompt"));
        urlField.setMaxWidth(360);
        TextField modelField = new TextField(ttsService.model());
        modelField.setPromptText(I18n.t("settings.tts.model.prompt"));
        modelField.setMaxWidth(200);
        TextField voiceField = new TextField(ttsService.voice());
        voiceField.setPromptText(I18n.t("settings.tts.voice.prompt"));
        voiceField.setMaxWidth(200);
        PasswordField keyField = new PasswordField();
        keyField.setText(ttsService.apiKeyPlain());
        keyField.setMaxWidth(200);

        Runnable save = () -> {
            ttsService.saveBaseUrl(urlField.getText());
            ttsService.saveModel(modelField.getText());
            ttsService.saveVoice(voiceField.getText());
            ttsService.saveApiKey(keyField.getText());
        };
        for (TextField f : List.of(urlField, modelField, voiceField, keyField)) {
            f.setOnAction(e -> save.run());
            f.focusedProperty().addListener((obs, was, now) -> {
                if (!now) {
                    save.run();
                }
            });
        }
        Button preview = SettingsLayout.iconTextButton("\uE768", I18n.t("settings.tts.preview"), () -> {
            save.run();
            if (!ttsService.isConfigured()) {
                host.notify("settings.tts.group", I18n.t("voice.tts.notConfigured"));
                return;
            }
            ttsService.speak(I18n.t("settings.tts.preview.text"), err -> {
                if (err != null && !err.isBlank()) {
                    host.notify("settings.tts.group", I18n.t("voice.tts.failed", err));
                }
            });
        });

        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.settingRow(I18n.t("settings.tts.url"),
                I18n.t("settings.tts.url.desc"), urlField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.tts.model"),
                I18n.t("settings.tts.model.desc"), modelField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.tts.voice"),
                I18n.t("settings.tts.voice.desc"), voiceField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.tts.apiKey"),
                I18n.t("settings.tts.apiKey.desc"), keyField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.tts.preview"), null, preview));
        return SettingsLayout.groupCard(I18n.t("settings.tts.group"), rows);
    }

    /**
     * 图像生成卡片：服务地址 / 模型 / API Key（SecretCipher 加密存 settings），文本框失焦/回车即保存。
     * <p>OpenAI 兼容 {@code /images/generations} 协议；地址与模型齐备即视为可用（无鉴权的本地服务可留空 Key）。
     * 聊天页「生成图片」入口与 Agent 的 {@code generate_image} 工具共用这份配置，改配即生效。</p>
     */
    private VBox imageCard() {
        TextField urlField = new TextField(securityAdminService.getImageBaseUrl());
        urlField.setPromptText(I18n.t("settings.image.url.prompt"));
        urlField.setMaxWidth(360);
        TextField modelField = new TextField(securityAdminService.getImageModel());
        modelField.setPromptText(I18n.t("settings.image.model.prompt"));
        modelField.setMaxWidth(200);
        PasswordField keyField = new PasswordField();
        keyField.setText(securityAdminService.getImageApiKeyPlain());
        keyField.setMaxWidth(200);

        Runnable save = () -> {
            securityAdminService.setImageBaseUrl(urlField.getText());
            securityAdminService.setImageModel(modelField.getText());
            securityAdminService.setImageApiKey(keyField.getText());
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
        rows.add(SettingsLayout.settingRow(I18n.t("settings.image.url"),
                I18n.t("settings.image.url.desc"), urlField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.image.model"),
                I18n.t("settings.image.model.desc"), modelField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.image.apiKey"),
                I18n.t("settings.image.apiKey.desc"), keyField));
        return SettingsLayout.groupCard(I18n.t("settings.image.group"), rows);
    }

}
