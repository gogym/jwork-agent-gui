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

import com.gettyio.jwork.agent.core.chat.ChatService;
import com.gettyio.jwork.agent.core.chat.HarnessAgentGateway;
import com.gettyio.jwork.agent.core.chat.MultiAgentGateway;
import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.ui.i18n.I18n;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * 对话与用量设置子面板（独立菜单页，抽自 {@link GeneralSettingsPanel}）：上下文窗口上限与月度用量预算。
 * <p>两者同属「使用量与成本控制」域——上限既是输入框占用条的分母、也是自动压缩阈值；
 * 预算超限提醒由 {@code ModelRateGovernor} 流完成后触发。修改上下文上限后失效全部运行体，
 * 下一轮对话按新阈值重新装配即时生效。</p>
 *
 * @author gogym
 */
final class ChatUsageSettingsPanel {

    private final SettingsWindow host;
    /** 读应用级设置（上下文上限/月度预算键）；仅取 settingDao 用途 */
    private final JworkDatabase database;
    /** 多智能体网关：上下文窗口上限变更后失效全部运行体，使新阈值于下一轮对话重新装配时生效。 */
    private final MultiAgentGateway agentGateway;

    ChatUsageSettingsPanel(SettingsWindow host, JworkDatabase database, MultiAgentGateway agentGateway) {
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
                        I18n.t("settings.context.limit.desc"), combo),
                stepLimitRow(),
                attachmentRow()));
    }

    /**
     * 附件注入方式行：摘要（默认）/ 全文。摘要只给「规模 + 表头样例 + 本机路径」，
     * 取数交给 {@code inspect_file} 与脚本——整表注入既撑爆窗口，也让后续每一步
     * 都在为这段一次性文本付注意力；全文档模式保留给需要逐字对照的场景。
     */
    private HBox attachmentRow() {
        ComboBox<String> combo = SettingsLayout.pillCombo(new String[]{
                I18n.t("settings.attachment.summary"), I18n.t("settings.attachment.full")});
        combo.getSelectionModel().select(currentAttachmentFullText() ? 1 : 0);
        combo.setOnAction(e -> saveAttachmentMode(combo.getSelectionModel().getSelectedIndex()));
        return SettingsLayout.settingRow(I18n.t("settings.attachment.label"),
                I18n.t("settings.attachment.desc"), combo);
    }

    private boolean currentAttachmentFullText() {
        return ChatService.ATTACHMENT_MODE_FULL.equalsIgnoreCase(database.settingDao()
                .getValue(ChatService.SETTING_ATTACHMENT_MODE).orElse("").trim());
    }

    /** 保存附件注入方式：下一轮对话即时生效（ChatService 每轮实时读取该键）。 */
    private void saveAttachmentMode(int index) {
        String mode = index == 1 ? ChatService.ATTACHMENT_MODE_FULL : ChatService.ATTACHMENT_MODE_SUMMARY;
        database.settingDao().upsert(ChatService.SETTING_ATTACHMENT_MODE, mode,
                "附件注入方式：summary 结构摘要+路径 / full 全文正文");
    }

    /**
     * 单轮步数上限输入框。写入 AppSetting {@code agent.maxIters}——框架内置默认 10 步，
     * 数据分析一类「探查→计算→产出文件」的任务会在半路被强制收尾，故必须可调。
     * 保存后失效全部运行体，下一轮对话按新上限重新装配即时生效。
     */
    private HBox stepLimitRow() {
        TextField field = new TextField();
        field.setText(String.valueOf(currentMaxIters()));
        field.setPromptText(I18n.t("settings.steps.prompt"));
        field.setMaxWidth(120);
        Runnable save = () -> saveMaxIters(field);
        field.setOnAction(e -> save.run());
        field.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                save.run();
            }
        });
        return SettingsLayout.settingRow(I18n.t("settings.steps.label"),
                I18n.t("settings.steps.desc"), field);
    }

    /** 步数档位允许区间，与网关 {@link HarnessAgentGateway#MIN_MAX_ITERS}–{@link HarnessAgentGateway#MAX_MAX_ITERS} 同源。 */
    private static final int MIN_STEP_LIMIT = HarnessAgentGateway.MIN_MAX_ITERS;
    private static final int MAX_STEP_LIMIT = HarnessAgentGateway.MAX_MAX_ITERS;

    /** 读取当前步数上限：未配置或非法回退到统一默认值。 */
    private int currentMaxIters() {
        return database.settingDao().getValue(HarnessAgentGateway.SETTING_MAX_ITERS)
                .map(raw -> {
                    try {
                        int v = Integer.parseInt(raw.trim());
                        return v > 0 ? v : HarnessAgentGateway.DEFAULT_MAX_ITERS;
                    } catch (NumberFormatException ex) {
                        return HarnessAgentGateway.DEFAULT_MAX_ITERS;
                    }
                })
                .orElse(HarnessAgentGateway.DEFAULT_MAX_ITERS);
    }

    /** 校验并保存步数上限：区间外或非法则回滚并提示。 */
    private void saveMaxIters(TextField field) {
        int value;
        try {
            value = Integer.parseInt(field.getText() == null ? "" : field.getText().trim());
            if (value < MIN_STEP_LIMIT || value > MAX_STEP_LIMIT) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            field.setText(String.valueOf(currentMaxIters()));
            host.notify("settings.steps", I18n.t("settings.steps.invalid", MIN_STEP_LIMIT, MAX_STEP_LIMIT));
            return;
        }
        database.settingDao().upsert(HarnessAgentGateway.SETTING_MAX_ITERS, String.valueOf(value),
                "单轮 ReAct 步数上限（触顶后自动续跑）");
        agentGateway.invalidateAll();
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
                .getValue(com.gettyio.jwork.agent.ui.throttle.ModelRateGovernor.KEY_MONTHLY_BUDGET).orElse(""));
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
                        .getValue(com.gettyio.jwork.agent.ui.throttle.ModelRateGovernor.KEY_MONTHLY_BUDGET).orElse(""));
                host.notify("usage.budget", I18n.t("usage.budget.invalid"));
                return;
            }
        }
        database.settingDao().upsert(
                com.gettyio.jwork.agent.ui.throttle.ModelRateGovernor.KEY_MONTHLY_BUDGET, toSave, "月度用量预算上限（元）");
    }
}
