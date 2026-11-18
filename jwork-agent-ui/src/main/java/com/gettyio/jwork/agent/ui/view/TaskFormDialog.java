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

package com.gettyio.jwork.agent.ui.view;

import com.gettyio.jwork.agent.domain.entity.AgentDefinition;
import com.gettyio.jwork.agent.domain.entity.PromptTemplate;
import com.gettyio.jwork.agent.domain.entity.ScheduledTask;
import com.gettyio.jwork.agent.domain.enums.EnabledStatus;
import com.gettyio.jwork.agent.domain.enums.TaskOutputMode;
import com.gettyio.jwork.agent.domain.enums.TaskStatus;
import com.gettyio.jwork.agent.ui.i18n.I18n;
import com.gettyio.jwork.agent.ui.config.AgentAdminService;
import com.gettyio.jwork.agent.ui.config.PromptTemplateAdminService;
import com.gettyio.jwork.agent.ui.config.TaskAdminService;
import com.gettyio.jwork.agent.ui.control.ToggleSwitch;
import com.gettyio.jwork.agent.ui.util.UiStyles;
import com.gettyio.jwork.agent.ui.voice.MicHoldButton;
import com.gettyio.jwork.agent.ui.voice.VoiceInputService;
import com.gettyio.jwork.agent.ui.window.dialog.JworkDialog;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.quartz.CronExpression;

import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 新建/编辑自动化任务弹窗（两栏布局，对标 Qoder「新建自动化」）：
 * 左栏名称 + 大指令编辑器 + 底部工具条（模板/智能体/模型/语音），
 * 右栏计划时间（友好构建器或自定义 cron）、到期日期、任务输出与执行授权。
 * <p>计划类型与 cron 互转：每天/每周/每月/每隔 N 小时/每隔 N 分钟由控件生成 Quartz 表达式，
 * 自定义类型原样保留；编辑时反向解析回填，无法识别的表达式落入自定义模式。</p>
 *
 * @author gogym
 */
final class TaskFormDialog {

    /** 计划类型（下拉顺序即展示顺序） */
    private enum SchedType {
        DAILY, WEEKLY, MONTHLY, EVERY_HOURS, EVERY_MINUTES, CUSTOM, ONCE
    }

    private static final String[] WEEKDAYS = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};

    private static final Pattern P_DAILY = Pattern.compile("^0 (\\d{1,2}) (\\d{1,2}) \\* \\* \\?$");
    private static final Pattern P_WEEKLY = Pattern.compile("^0 (\\d{1,2}) (\\d{1,2}) \\? \\* ([A-Za-z]{3})$");
    private static final Pattern P_MONTHLY = Pattern.compile("^0 (\\d{1,2}) (\\d{1,2}) (\\d{1,2}) \\* \\?$");
    private static final Pattern P_EVERY_HOURS = Pattern.compile("^0 0 0/(\\d{1,2}) \\* \\* \\?$");
    private static final Pattern P_EVERY_MINUTES = Pattern.compile("^0 0/(\\d{1,2}) \\* \\* \\* \\?$");

    private TaskFormDialog() {
    }

    /**
     * 弹出表单。
     *
     * @param editing 编辑对象；null 为新建
     * @param presetPrompt 新建时的预填指令（模板 Tab「使用模板」）
     * @param onSaved 保存成功后的回调（刷新列表）
     */
    static void show(Window owner, ScheduledTask editing, String presetPrompt,
                     TaskAdminService taskAdminService,
                     PromptTemplateAdminService templateService,
                     AgentAdminService agentService,
                     VoiceInputService voiceInputService,
                     Runnable onSaved) {
        JworkDialog.Shell shell = JworkDialog.shell(owner, editing == null
                ? I18n.t("tasks.form.newTitle") : I18n.t("tasks.form.editTitle"));

        Label subtitle = new Label(I18n.t("tasks.form.subtitle"));
        subtitle.getStyleClass().add(UiStyles.ROW_DESC);

        // ---- 左栏：名称 + 指令编辑器 + 工具条 ----
        TextField nameField = new TextField(editing == null ? "" : editing.getTaskName());
        nameField.setPromptText(I18n.t("tasks.form.namePrompt"));
        nameField.getStyleClass().add("automation-name-field");

        TextArea promptArea = new TextArea(editing == null ? (presetPrompt == null ? "" : presetPrompt)
                : editing.getPrompt());
        promptArea.setPromptText(I18n.t("tasks.form.promptHint"));
        promptArea.setWrapText(true);
        promptArea.getStyleClass().add("automation-prompt");
        promptArea.setPrefHeight(360);
        VBox.setVgrow(promptArea, Priority.ALWAYS);

        ComboBox<AgentDefinition> agentCombo = new ComboBox<>();
        ComboBox<AgentAdminService.ModelOption> modelCombo = new ComboBox<>();
        HBox toolbar = buildToolbar(owner, promptArea, editing, templateService, agentService,
                voiceInputService, agentCombo, modelCombo);

        VBox left = new VBox(8, nameField, promptArea, toolbar);
        left.getStyleClass().add("automation-left");
        left.setPrefWidth(440);
        HBox.setHgrow(left, Priority.ALWAYS);

        // ---- 右栏：计划时间 / 到期 / 输出与授权 ----
        ScheduleControls sched = new ScheduleControls(editing);
        ExpireControls expire = new ExpireControls(editing);
        OutputControls out = new OutputControls(editing);
        VBox right = new VBox(16, sched.section(), expire.section(), out.section());
        right.getStyleClass().add("automation-side");
        right.setPrefWidth(286);
        right.setMinWidth(286);

        HBox columns = new HBox(18, left, right);
        HBox.setHgrow(left, Priority.ALWAYS);

        // ---- 底部：错误提示 + 按钮 ----
        Label err = new Label();
        err.getStyleClass().add("automation-error");
        err.setWrapText(true);
        HBox.setHgrow(err, Priority.ALWAYS);

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        Button save = new Button(editing == null ? I18n.t("tasks.form.create") : I18n.t("common.save"));
        save.getStyleClass().add(UiStyles.BTN_PRIMARY);
        save.setMinWidth(76);
        save.setOnAction(e -> {
            String error = submit(editing, nameField.getText(), promptArea.getText(), sched, expire, out,
                    agentCombo, modelCombo, agentService.defaultAgentCode(), taskAdminService);
            if (error != null) {
                err.setText(error);
                return;
            }
            shell.stage().close();
            onSaved.run();
        });
        HBox buttons = new HBox(10, err, cancel, save);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.getStyleClass().add("automation-actions");

        shell.body().getChildren().addAll(subtitle, columns, buttons);
        shell.show();
    }

    // ---- 左栏工具条 ----

    private static HBox buildToolbar(Window owner, TextArea promptArea, ScheduledTask editing,
                                     PromptTemplateAdminService templateService,
                                     AgentAdminService agentService,
                                     VoiceInputService voiceInputService,
                                     ComboBox<AgentDefinition> agentCombo,
                                     ComboBox<AgentAdminService.ModelOption> modelCombo) {
        Button templateBtn = new Button(I18n.t("tasks.form.template"));
        templateBtn.getStyleClass().add("automation-tool");
        templateBtn.setOnAction(e -> {
            ContextMenu menu = new ContextMenu();
            List<PromptTemplate> templates = templateService.listEnabled();
            if (templates.isEmpty()) {
                MenuItem empty = new MenuItem(I18n.t("tasks.templates.empty"));
                empty.setDisable(true);
                menu.getItems().add(empty);
            }
            for (PromptTemplate t : templates) {
                MenuItem item = new MenuItem(t.getTemplateName() == null || t.getTemplateName().isBlank()
                        ? t.getTemplateCode() : t.getTemplateName());
                item.setOnAction(a -> insertAtCaret(promptArea, t.getContent() == null ? "" : t.getContent()));
                menu.getItems().add(item);
            }
            menu.show(templateBtn, javafx.geometry.Side.TOP, 0, 0);
        });

        agentCombo.getItems().addAll(agentService.list());
        agentCombo.setConverter(new StringConverter<>() {
            /** 智能体下拉显示名：名称优先、空回退编码；null 显示空串。 */
            @Override
            public String toString(AgentDefinition a) {
                if (a == null) {
                    return "";
                }
                return a.getAgentName() == null || a.getAgentName().isBlank()
                        ? a.getAgentCode() : a.getAgentName();
            }

            /** 下拉框不可自行输入（回写恒 null，选择经 items 完成）。 */
            @Override
            public AgentDefinition fromString(String s) {
                return null;
            }
        });
        agentCombo.setPromptText(I18n.t("tasks.form.agent"));
        selectAgent(agentCombo, editing, agentService);
        agentCombo.getStyleClass().add("automation-combo");

        modelCombo.getItems().add(AgentAdminService.ModelOption.followDefault());
        modelCombo.getItems().addAll(agentService.modelOptions());
        modelCombo.setConverter(new StringConverter<>() {
            /** 模型下拉显示名：默认项显示「跟随默认」文案，否则模型标签；null 显示空串。 */
            @Override
            public String toString(AgentAdminService.ModelOption o) {
                if (o == null) {
                    return "";
                }
                return o.isDefault() ? I18n.t("tasks.form.modelDefault") : o.label();
            }

            @Override
            public AgentAdminService.ModelOption fromString(String s) {
                return null;
            }
        });
        selectModel(modelCombo, editing);
        modelCombo.getStyleClass().add("automation-combo");
        // 固定宽度：ComboBox 默认宽 = max(按钮文本, 最长下拉项)，且下拉项首次弹出才测量，
        // 点击展开时控件与弹出列表会被长模型名撑宽跳变；锁宽后恒定。
        fixComboWidth(modelCombo, 220);

        Button mic = new Button();
        MicHoldButton.install(mic, voiceInputService,
                text -> insertAtCaret(promptArea, text),
                () -> JworkDialog.message(owner, I18n.t("voice.title"), null, I18n.t("voice.notConfigured")),
                (title, msg) -> JworkDialog.message(owner, title, null, msg));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, templateBtn, spacer, agentCombo, modelCombo, mic);
        toolbar.getStyleClass().add("automation-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        return toolbar;
    }

    /**
     * 固定下拉框宽度：控件锁 min/pref/max 三值宽 + 弹出列表锁 pref 宽。
     * <p>ComboBox 默认宽度 = max(按钮文本, 最长列表项)，且列表项首次弹出时才完成测量——
     * 不锁宽时点击展开会被长模型名撑宽且不再回收；弹出层宽度同样取自列表内容宽。</p>
     */
    private static void fixComboWidth(ComboBox<?> combo, double width) {
        combo.setMinWidth(width);
        combo.setPrefWidth(width);
        combo.setMaxWidth(width);
        applyPopupWidth(combo, width);
        combo.skinProperty().addListener((obs, old, skin) -> applyPopupWidth(combo, width));
    }

    /** 弹出层宽度取自内部 ListView 的 pref 宽，固定它弹层才与控件保持同宽。 */
    private static void applyPopupWidth(ComboBox<?> combo, double width) {
        if (combo.getSkin() instanceof ComboBoxListViewSkin<?> skin
                && skin.getPopupContent() instanceof Region content) {
            content.setPrefWidth(width);
        }
    }

    private static void selectAgent(ComboBox<AgentDefinition> combo, ScheduledTask editing,
                                    AgentAdminService agentService) {
        String code = editing == null || editing.getAgentCode() == null || editing.getAgentCode().isBlank()
                ? agentService.defaultAgentCode() : editing.getAgentCode();
        combo.getItems().stream()
                .filter(a -> code.equals(a.getAgentCode()))
                .findFirst()
                .ifPresentOrElse(combo.getSelectionModel()::select,
                        () -> combo.getSelectionModel().select(0));
    }

    private static void selectModel(ComboBox<AgentAdminService.ModelOption> combo, ScheduledTask editing) {
        String provider = editing == null ? null : editing.getProviderCode();
        String model = editing == null ? null : editing.getModelCode();
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            combo.getSelectionModel().select(0);
            return;
        }
        combo.getItems().stream()
                .filter(o -> provider.equals(o.providerCode()) && model.equals(o.modelCode()))
                .findFirst()
                .ifPresentOrElse(combo.getSelectionModel()::select,
                        () -> combo.getSelectionModel().select(0));
    }

    // ---- 右栏：计划时间 ----

    /** 计划时间区块：类型下拉 + 动态详情 + 时区提示。 */
    private static final class ScheduleControls {

        private final ComboBox<String> typeCombo = new ComboBox<>();
        private final ComboBox<Integer> hourCombo = intCombo(0, 23, 9);
        private final ComboBox<Integer> minuteCombo = intCombo(0, 59, 0);
        private final ComboBox<Integer> dayOfMonthCombo = intCombo(1, 31, 1);
        private final ComboBox<String> weekdayCombo = weekdayCombo();
        private final ComboBox<Integer> nHoursCombo = intCombo(1, 23, 2);
        private final ComboBox<Integer> nMinutesCombo = intCombo(1, 59, 30);
        private final TextField cronField = new TextField();
        private final DatePicker oncePicker = new DatePicker();
        private final VBox detailBox = new VBox(6);

        ScheduleControls(ScheduledTask editing) {
            for (SchedType t : SchedType.values()) {
                typeCombo.getItems().add(label(t));
            }
            typeCombo.setMaxWidth(Double.MAX_VALUE);
            typeCombo.getStyleClass().add("automation-combo");
            cronField.setPromptText(I18n.t("tasks.form.cronPrompt"));
            oncePicker.setValue(LocalDate.now().plusDays(1));
            applyFrom(editing);
            typeCombo.setOnAction(e -> rebuildDetail());
            rebuildDetail();
        }

        private VBox section() {
            Label title = new Label(I18n.t("tasks.form.schedule"));
            title.getStyleClass().add("automation-section-title");
            Label tz = new Label(I18n.t("tasks.sched.timezone", ZoneId.systemDefault().getId()));
            tz.getStyleClass().add("automation-hint");
            VBox box = new VBox(8, title, typeCombo, detailBox, tz);
            box.getStyleClass().add("automation-section");
            return box;
        }

        private SchedType type() {
            int idx = typeCombo.getSelectionModel().getSelectedIndex();
            return idx < 0 ? SchedType.DAILY : SchedType.values()[idx];
        }

        /** 编辑回填：按 cron/run_at 反向解析预选类型与各控件值。 */
        private void applyFrom(ScheduledTask editing) {
            if (editing == null) {
                typeCombo.getSelectionModel().select(0);
                return;
            }
            if (editing.getRunAt() != null) {
                select(SchedType.ONCE);
                oncePicker.setValue(editing.getRunAt().toLocalDate());
                hourCombo.getSelectionModel().select(Integer.valueOf(editing.getRunAt().getHour()));
                minuteCombo.getSelectionModel().select(Integer.valueOf(editing.getRunAt().getMinute()));
                return;
            }
            String cron = editing.getCronExpression() == null ? "" : editing.getCronExpression().trim();
            Matcher m;
            if ((m = P_DAILY.matcher(cron)).matches()) {
                select(SchedType.DAILY);
                hourCombo.getSelectionModel().select(Integer.valueOf(m.group(2)));
                minuteCombo.getSelectionModel().select(Integer.valueOf(m.group(1)));
            } else if ((m = P_WEEKLY.matcher(cron)).matches()) {
                select(SchedType.WEEKLY);
                hourCombo.getSelectionModel().select(Integer.valueOf(m.group(2)));
                minuteCombo.getSelectionModel().select(Integer.valueOf(m.group(1)));
                weekdayCombo.getSelectionModel().select(m.group(3).toUpperCase(Locale.ROOT));
            } else if ((m = P_MONTHLY.matcher(cron)).matches()) {
                select(SchedType.MONTHLY);
                hourCombo.getSelectionModel().select(Integer.valueOf(m.group(2)));
                minuteCombo.getSelectionModel().select(Integer.valueOf(m.group(1)));
                dayOfMonthCombo.getSelectionModel().select(Integer.valueOf(m.group(3)));
            } else if ((m = P_EVERY_HOURS.matcher(cron)).matches()) {
                select(SchedType.EVERY_HOURS);
                nHoursCombo.getSelectionModel().select(Integer.valueOf(m.group(1)));
            } else if ((m = P_EVERY_MINUTES.matcher(cron)).matches()) {
                select(SchedType.EVERY_MINUTES);
                nMinutesCombo.getSelectionModel().select(Integer.valueOf(m.group(1)));
            } else {
                select(cron.isBlank() ? SchedType.DAILY : SchedType.CUSTOM);
                cronField.setText(cron);
            }
        }

        private void select(SchedType t) {
            typeCombo.getSelectionModel().select(t.ordinal());
        }

        /** 依类型重建详情区（时间选择 / 每周几 / 每月几日 / 间隔 / cron 输入 / 一次性日期）。 */
        private void rebuildDetail() {
            detailBox.getChildren().clear();
            switch (type()) {
                case DAILY -> detailBox.getChildren().add(timeRow());
                case WEEKLY -> detailBox.getChildren().add(new HBox(6, weekdayCombo, timeRow()));
                case MONTHLY -> detailBox.getChildren().add(new HBox(6, dayOfMonthCombo,
                        suffix(I18n.t("tasks.sched.daySuffix")), timeRow()));
                case EVERY_HOURS -> detailBox.getChildren().add(new HBox(6, nHoursCombo,
                        suffix(I18n.t("tasks.sched.hourSuffix"))));
                case EVERY_MINUTES -> detailBox.getChildren().add(new HBox(6, nMinutesCombo,
                        suffix(I18n.t("tasks.sched.minuteSuffix"))));
                case CUSTOM -> detailBox.getChildren().add(cronField);
                case ONCE -> detailBox.getChildren().add(new VBox(6, oncePicker, timeRow()));
            }
        }

        private HBox timeRow() {
            Label colon = new Label(":");
            colon.getStyleClass().add("automation-hint");
            HBox row = new HBox(4, hourCombo, colon, minuteCombo);
            row.setAlignment(Pos.CENTER_LEFT);
            return row;
        }

        private static Label suffix(String text) {
            Label l = new Label(text);
            l.getStyleClass().add("automation-hint");
            return l;
        }

        /** 生成 cron / run_at；错误时返回提示文案，成功返回 null。 */
        String resolve(String[] cronOut, LocalDateTime[] runAtOut) {
            switch (type()) {
                case ONCE -> {
                    LocalDate d = oncePicker.getValue();
                    if (d == null) {
                        return I18n.t("tasks.form.err.time");
                    }
                    runAtOut[0] = d.atTime(hourCombo.getValue(), minuteCombo.getValue());
                }
                case CUSTOM -> {
                    String cron = cronField.getText() == null ? "" : cronField.getText().trim();
                    if (cron.isBlank()) {
                        return I18n.t("tasks.form.err.cron");
                    }
                    try {
                        new CronExpression(cron);
                    } catch (ParseException e) {
                        return I18n.t("tasks.form.err.cron");
                    }
                    cronOut[0] = cron;
                }
                default -> cronOut[0] = buildCron(type(),
                        hourCombo.getValue(), minuteCombo.getValue(),
                        weekdayCombo.getValue(), dayOfMonthCombo.getValue(),
                        nHoursCombo.getValue(), nMinutesCombo.getValue());
            }
            return null;
        }

        private static String label(SchedType t) {
            return switch (t) {
                case DAILY -> I18n.t("tasks.sched.daily");
                case WEEKLY -> I18n.t("tasks.sched.weekly");
                case MONTHLY -> I18n.t("tasks.sched.monthly");
                case EVERY_HOURS -> I18n.t("tasks.sched.everyHours");
                case EVERY_MINUTES -> I18n.t("tasks.sched.everyMinutes");
                case CUSTOM -> I18n.t("tasks.sched.custom");
                case ONCE -> I18n.t("tasks.sched.once");
            };
        }
    }

    /** 由控件值生成 Quartz cron（每天/每周/每月/每隔 N 小时/每隔 N 分钟）。 */
    private static String buildCron(SchedType type, Integer hour, Integer minute, String weekday,
                                    Integer dayOfMonth, Integer nHours, Integer nMinutes) {
        int h = hour == null ? 9 : hour;
        int min = minute == null ? 0 : minute;
        return switch (type) {
            case WEEKLY -> String.format("0 %d %d ? * %s", min, h, weekday == null ? "MON" : weekday);
            case MONTHLY -> String.format("0 %d %d %d * ?", min, h, dayOfMonth == null ? 1 : dayOfMonth);
            case EVERY_HOURS -> String.format("0 0 0/%d * * ?", nHours == null ? 2 : nHours);
            case EVERY_MINUTES -> String.format("0 0/%d * * * ?", nMinutes == null ? 30 : nMinutes);
            default -> String.format("0 %d %d * * ?", min, h);
        };
    }

    // ---- 右栏：到期日期 ----

    /** 到期区块：开关 + 日期选择（开启才显示）；到期后由服务侧自动暂停。 */
    private static final class ExpireControls {

        private final ToggleSwitch toggle = new ToggleSwitch();
        private final DatePicker picker = new DatePicker();
        private final VBox section;

        ExpireControls(ScheduledTask editing) {
            Label title = new Label(I18n.t("tasks.form.expire"));
            title.getStyleClass().add("automation-section-title");
            Label hint = new Label(I18n.t("tasks.form.expire.hint"));
            hint.getStyleClass().add("automation-hint");
            hint.setWrapText(true);
            picker.setMaxWidth(Double.MAX_VALUE);

            boolean enabled = editing != null && editing.getEndAt() != null;
            picker.setValue(enabled ? editing.getEndAt().toLocalDate() : LocalDate.now().plusWeeks(1));
            toggle.setSelected(enabled);
            picker.visibleProperty().bind(toggle.selectedProperty());
            picker.managedProperty().bind(toggle.selectedProperty());

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(10, title, spacer, toggle);
            row.setAlignment(Pos.CENTER_LEFT);

            section = new VBox(8, row, hint, picker);
            section.getStyleClass().add("automation-section");
        }

        VBox section() {
            return section;
        }

        /** 所选到期日期；关闭开关返回 null。 */
        LocalDate selectedDate() {
            return toggle.isSelected() ? picker.getValue() : null;
        }
    }

    // ---- 右栏：任务输出 / 执行授权 ----

    /** 输出模式与无人值守开关的组合区块。 */
    private static final class OutputControls {
        private final ToggleGroup group = new ToggleGroup();
        private final RadioButton separate;
        private final RadioButton merged;
        private final ToggleSwitch unattended = new ToggleSwitch();
        private final VBox section;

        OutputControls(ScheduledTask editing) {
            boolean isSeparate = editing != null
                    && TaskOutputMode.fromCode(editing.getOutputMode()) == TaskOutputMode.SEPARATE;
            separate = new RadioButton(I18n.t("tasks.form.output.separate"));
            merged = new RadioButton(I18n.t("tasks.form.output.merged"));
            separate.setToggleGroup(group);
            merged.setToggleGroup(group);
            if (isSeparate) {
                separate.setSelected(true);
            } else {
                merged.setSelected(true);
            }

            Label title = new Label(I18n.t("tasks.form.output"));
            title.getStyleClass().add("automation-section-title");
            Label hint = new Label(I18n.t("tasks.form.output.hint"));
            hint.getStyleClass().add("automation-hint");
            hint.setWrapText(true);

            Label authTitle = new Label(I18n.t("tasks.form.auth"));
            authTitle.getStyleClass().add("automation-section-title");
            Label authRowTitle = new Label(I18n.t("tasks.form.auth.unattended"));
            authRowTitle.getStyleClass().add("row-title");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox authRow = new HBox(10, authRowTitle, spacer, unattended);
            authRow.setAlignment(Pos.CENTER_LEFT);
            unattended.setSelected(editing != null && EnabledStatus.isEnabled(editing.getUnattended()));
            Label authHint = new Label(I18n.t("tasks.form.auth.hint"));
            authHint.getStyleClass().add("automation-hint");
            authHint.setWrapText(true);

            section = new VBox(4, title, separate, merged, hint, authTitle, authRow, authHint);
            section.getStyleClass().add("automation-section");
        }

        VBox section() {
            return section;
        }

        TaskOutputMode outputMode() {
            return separate.isSelected() ? TaskOutputMode.SEPARATE : TaskOutputMode.MERGED;
        }

        boolean unattended() {
            return unattended.isSelected();
        }
    }

    // ---- 保存 ----

    /** 组装并保存；返回错误文案（null 表示成功）。 */
    private static String submit(ScheduledTask editing, String rawName, String rawPrompt,
                                 ScheduleControls sched, ExpireControls expire, OutputControls out,
                                 ComboBox<AgentDefinition> agentCombo,
                                 ComboBox<AgentAdminService.ModelOption> modelCombo,
                                 String defaultAgentCode,
                                 TaskAdminService taskAdminService) {
        String prompt = rawPrompt == null ? "" : rawPrompt.trim();
        if (prompt.isBlank()) {
            return I18n.t("tasks.form.err.prompt");
        }
        ScheduledTask t = editing != null ? editing : new ScheduledTask();
        if (editing == null) {
            t.setTaskCode("task-" + System.currentTimeMillis() + "-"
                    + ThreadLocalRandom.current().nextInt(1000, 9999));
            t.setStatus(TaskStatus.READY.name());
        }
        String name = rawName == null ? "" : rawName.trim();
        t.setTaskName(name.isBlank() ? deriveName(prompt) : name);
        t.setPrompt(prompt);

        AgentDefinition agent = agentCombo.getValue();
        t.setAgentCode(agent != null ? agent.getAgentCode() : defaultAgentCode);

        AgentAdminService.ModelOption model = modelCombo.getValue();
        boolean modelOverride = model != null && !model.isDefault();
        t.setProviderCode(modelOverride ? model.providerCode() : null);
        t.setModelCode(modelOverride ? model.modelCode() : null);

        String[] cronOut = new String[1];
        LocalDateTime[] runAtOut = new LocalDateTime[1];
        String err = sched.resolve(cronOut, runAtOut);
        if (err != null) {
            return err;
        }
        t.setCronExpression(cronOut[0]);
        t.setRunAt(runAtOut[0]);
        t.setOnce(runAtOut[0] != null ? EnabledStatus.ENABLED : EnabledStatus.DISABLED);

        t.setOutputMode(out.outputMode().name());
        t.setUnattended(out.unattended() ? EnabledStatus.ENABLED : EnabledStatus.DISABLED);

        // 到期日期：开关控制；日期为今天或更早视为无效
        LocalDate endDate = expire.selectedDate();
        if (endDate != null) {
            if (!endDate.isAfter(LocalDate.now())) {
                return I18n.t("tasks.form.err.date");
            }
            t.setEndAt(endDate.atTime(23, 59, 59));
        } else {
            t.setEndAt(null);
        }
        try {
            taskAdminService.save(t);
            return null;
        } catch (RuntimeException e) {
            return I18n.t("tasks.form.err.save", e.getMessage());
        }
    }

    private static String deriveName(String prompt) {
        String one = prompt.replaceAll("\\s+", " ").trim();
        return one.length() <= 30 ? one : one.substring(0, 30) + "…";
    }

    private static void insertAtCaret(TextArea area, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int pos = area.getCaretPosition();
        area.insertText(pos, text);
        area.positionCaret(pos + text.length());
    }

    private static ComboBox<Integer> intCombo(int from, int to, int selected) {
        ComboBox<Integer> combo = new ComboBox<>();
        for (int i = from; i <= to; i++) {
            combo.getItems().add(i);
        }
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer v) {
                return v == null ? "" : String.format("%02d", v);
            }

            @Override
            public Integer fromString(String s) {
                return null;
            }
        });
        combo.getSelectionModel().select(Integer.valueOf(selected));
        combo.setVisibleRowCount(10);
        combo.getStyleClass().add("automation-combo");
        return combo;
    }

    private static ComboBox<String> weekdayCombo() {
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().addAll(WEEKDAYS);
        combo.setConverter(new StringConverter<>() {
            /** 星期下拉显示名：code 转本地化文案；null 显示空串。 */
            @Override
            public String toString(String code) {
                if (code == null) {
                    return "";
                }
                return I18n.t("tasks.weekday." + code.toLowerCase(Locale.ROOT));
            }

            @Override
            public String fromString(String s) {
                return null;
            }
        });
        combo.getSelectionModel().select("MON");
        combo.getStyleClass().add("automation-combo");
        return combo;
    }
}
