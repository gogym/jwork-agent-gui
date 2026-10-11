package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.core.chat.HarnessAgentGateway;
import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.ui.I18n;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * 对话与用量设置子面板（独立菜单页，抽自 {@link GeneralSettingsPanel}）：上下文窗口上限与月度用量预算。
 * <p>两者同属「使用量与成本控制」域——上限既是输入框占用条的分母、也是自动压缩阈值；
 * 预算超限提醒由 {@code ModelRateGovernor} 流完成后触发。修改上下文上限后失效全部运行体，
 * 下一轮对话按新阈值重新装配即时生效。</p>
 */
final class ChatUsageSettingsPanel {

    private final SettingsWindow host;
    /** 读应用级设置（上下文上限/月度预算键）；仅取 settingDao 用途 */
    private final GimDatabase database;
    /** 多智能体网关：上下文窗口上限变更后失效全部运行体，使新阈值于下一轮对话重新装配时生效。 */
    private final MultiAgentGateway agentGateway;

    ChatUsageSettingsPanel(SettingsWindow host, GimDatabase database, MultiAgentGateway agentGateway) {
        this.host = host;
        this.database = database;
        this.agentGateway = agentGateway;
    }

    VBox build() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.chat"), I18n.t("settings.chat.subtitle"));
        box.getChildren().add(contextCard());
        box.getChildren().add(budgetCard());
        return box;
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
}
