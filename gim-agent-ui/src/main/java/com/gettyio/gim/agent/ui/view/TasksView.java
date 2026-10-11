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

package com.gettyio.gim.agent.ui.view;

import com.gettyio.gim.agent.domain.entity.PromptTemplate;
import com.gettyio.gim.agent.domain.entity.ScheduledTask;
import com.gettyio.gim.agent.domain.entity.TaskRun;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.enums.TaskStatus;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.config.AgentAdminService;
import com.gettyio.gim.agent.ui.config.PromptTemplateAdminService;
import com.gettyio.gim.agent.ui.config.TaskAdminService;
import com.gettyio.gim.agent.ui.control.CardKit;
import com.gettyio.gim.agent.ui.control.ToggleSwitch;
import com.gettyio.gim.agent.ui.desktop.KeepAwakeService;
import com.gettyio.gim.agent.ui.voice.VoiceInputService;
import com.gettyio.gim.agent.ui.window.dialog.GimDialog;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.quartz.CronExpression;

import java.text.ParseException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动化整页（对标 Qoder「自动化」）：页头 + 保持设备唤醒横幅 + 三个页签。
 * <ul>
 *   <li><b>自动化</b>：任务卡片列表，支持状态筛选（全部/运行中/已暂停/已结束）与排序（最新创建等）。</li>
 *   <li><b>模板</b>：内置提示词模板，一键「使用模板」预填新建表单。</li>
 *   <li><b>执行记录</b>：全库最近执行，可跳转到对应会话查看完整过程。</li>
 * </ul>
 * <p>新建/编辑使用两栏表单弹窗（{@link TaskFormDialog}），操作直连 {@link TaskAdminService}
 * （含 Quartz 联动、手动触发与到期停摆）。全部文案走 {@link I18n}，语言切换时刷新并重绘。</p>
 *
 * @author gogym
 */
public class TasksView {

    private final TaskAdminService taskAdminService;
    private final PromptTemplateAdminService templateAdminService;
    private final AgentAdminService agentAdminService;
    private final KeepAwakeService keepAwakeService;
    private final VoiceInputService voiceInputService;

    public TasksView(TaskAdminService taskAdminService, PromptTemplateAdminService templateAdminService,
                     AgentAdminService agentAdminService, KeepAwakeService keepAwakeService,
                     VoiceInputService voiceInputService) {
        this.taskAdminService = taskAdminService;
        this.templateAdminService = templateAdminService;
        this.agentAdminService = agentAdminService;
        this.keepAwakeService = keepAwakeService;
        this.voiceInputService = voiceInputService;
    }

    /** 「查看会话」回调（由 MainController 注入）：跳转对话页并打开对应会话。 */
    private Consumer<String> sessionOpener;

    private static final DateTimeFormatter FMT_FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FMT_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FMT_HM = DateTimeFormatter.ofPattern("HH:mm");

    /** 常见 cron 形态识别（与 TaskFormDialog 的生成规则对称），用于卡片上的友好文案。 */
    private static final Pattern P_DAILY = Pattern.compile("^0 (\\d{1,2}) (\\d{1,2}) \\* \\* \\?$");
    private static final Pattern P_WEEKLY = Pattern.compile("^0 (\\d{1,2}) (\\d{1,2}) \\? \\* ([A-Za-z]{3})$");
    private static final Pattern P_MONTHLY = Pattern.compile("^0 (\\d{1,2}) (\\d{1,2}) (\\d{1,2}) \\* \\?$");
    private static final Pattern P_EVERY_HOURS = Pattern.compile("^0 0 0/(\\d{1,2}) \\* \\* \\?$");
    private static final Pattern P_EVERY_MINUTES = Pattern.compile("^0 0/(\\d{1,2}) \\* \\* \\* \\?$");

    private final VBox root = new VBox();
    private final VBox body = new VBox();

    private Label titleLabel;
    private Label descLabel;
    private Button refreshButton;
    private Button createButton;

    private HBox banner;
    private Label bannerTitle;
    private Label bannerDesc;
    private ToggleSwitch awakeToggle;

    private ToggleButton tabAuto;
    private ToggleButton tabTemplates;
    private ToggleButton tabRuns;

    private HBox filterBox;
    private ToggleButton fAll;
    private ToggleButton fReady;
    private ToggleButton fPaused;
    private ToggleButton fDone;
    private ComboBox<String> sortCombo;

    /** 当前页签：0=自动化 1=模板 2=执行记录 */
    private int activeTab;
    /** 当前状态筛选：ALL / READY / PAUSED / DONE */
    private String taskFilter = "ALL";
    /** 当前排序（值为 tasks.sort.* 键） */
    private String taskSort = "tasks.sort.newest";
    /** 最近一次加载的任务列表（自动化页签筛选与执行记录页任务名共用） */
    private List<ScheduledTask> allTasks = List.of();

    /** 装配期（FX 线程）由组合根调用：构建界面并挂接语言切换。 */
    public void init() {
        buildUi();
        I18n.onChange(lang -> {
            applyI18n();
            reload();
        });
    }

    public VBox getNode() {
        return root;
    }

    /** 注入「查看会话」跳转回调（MainController 在装配阶段调用一次）。 */
    public void setSessionOpener(Consumer<String> opener) {
        this.sessionOpener = opener;
    }

    /** 重新加载数据并重绘当前页签（切页或语言切换时调用）。 */
    public void reload() {
        allTasks = taskAdminService.list();
        renderBody();
    }

    // ---- 界面骨架 ----

    private void buildUi() {
        root.getStyleClass().add("content-page");

        titleLabel = new Label();
        titleLabel.getStyleClass().add("page-h1");
        descLabel = new Label();
        descLabel.getStyleClass().add("page-desc");
        // 窄窗口下描述不换行，压缩到极限后省略号隐藏，始终保住右侧操作区
        descLabel.setMinWidth(0);
        descLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        refreshButton = iconButton("\uE72C", this::reload);
        refreshButton.setMinWidth(Region.USE_PREF_SIZE);
        createButton = new Button();
        createButton.getStyleClass().add("btn-primary");
        createButton.setMinWidth(Region.USE_PREF_SIZE);
        createButton.setOnAction(e -> openForm(null, null));

        // 右侧控件与描述同一行：行内中心对齐使控件中心线与描述文字中心共线，标题独占上一行
        HBox descRow = new HBox(10, descLabel, spacer, refreshButton, createButton);
        descRow.setAlignment(Pos.CENTER_LEFT);
        VBox headText = new VBox(4, titleLabel, descRow);
        headText.setMaxWidth(Double.MAX_VALUE);

        HBox header = new HBox(headText);
        HBox.setHgrow(headText, Priority.ALWAYS);
        header.getStyleClass().add("page-header");

        banner = buildKeepAwakeBanner();

        tabAuto = pageTab(0);
        tabTemplates = pageTab(1);
        tabRuns = pageTab(2);
        tabAuto.setSelected(true);
        filterBox = buildFilterBox();
        Region tabSpacer = new Region();
        HBox.setHgrow(tabSpacer, Priority.ALWAYS);
        HBox tabsRow = new HBox(6, tabAuto, tabTemplates, tabRuns, tabSpacer, filterBox);
        tabsRow.setAlignment(Pos.CENTER_LEFT);
        tabsRow.getStyleClass().add("page-tabs");

        VBox.setVgrow(body, Priority.ALWAYS);

        root.getChildren().addAll(header, banner, tabsRow, body);
        applyI18n();
        renderBody();
    }

    /** 保持设备唤醒横幅：图标 + 标题描述 + 开关；非 Windows 平台整条隐藏。 */
    private HBox buildKeepAwakeBanner() {
        Label icon = new Label("\uE945");
        icon.getStyleClass().add("automation-banner-icon");
        bannerTitle = new Label();
        bannerTitle.getStyleClass().add("automation-banner-title");
        bannerDesc = new Label();
        bannerDesc.getStyleClass().add("automation-banner-desc");
        bannerDesc.setWrapText(true);
        bannerDesc.setMinWidth(0);
        VBox text = new VBox(2, bannerTitle, bannerDesc);
        HBox.setHgrow(text, Priority.ALWAYS);

        // 先同步持久化状态再加监听，避免初始化回写设置
        awakeToggle = new ToggleSwitch();
        awakeToggle.setSelected(keepAwakeService.isEnabled());
        awakeToggle.selectedProperty().addListener((obs, o, nv) -> keepAwakeService.setEnabled(nv));

        HBox box = new HBox(12, icon, text, awakeToggle);
        box.getStyleClass().add("automation-banner");
        box.setAlignment(Pos.CENTER_LEFT);
        boolean supported = keepAwakeService.isSupported();
        box.setVisible(supported);
        box.setManaged(supported);
        return box;
    }

    private ToggleButton pageTab(int index) {
        ToggleButton tb = new ToggleButton();
        tb.getStyleClass().add("page-tab");
        tb.setOnAction(e -> switchTab(index));
        return tb;
    }

    /** 切换页签并重绘；筛选与排序控件仅在「自动化」页签可见。 */
    private void switchTab(int index) {
        activeTab = index;
        tabAuto.setSelected(index == 0);
        tabTemplates.setSelected(index == 1);
        tabRuns.setSelected(index == 2);
        boolean auto = index == 0;
        filterBox.setVisible(auto);
        filterBox.setManaged(auto);
        renderBody();
    }

    /** 右侧筛选控件：状态分段 + 排序下拉（与用量页分段同款样式）。 */
    private HBox buildFilterBox() {
        fAll = filterToggle("ALL");
        fReady = filterToggle("READY");
        fPaused = filterToggle("PAUSED");
        fDone = filterToggle("DONE");
        fAll.setSelected(true);
        HBox segmented = new HBox(fAll, fReady, fPaused, fDone);
        segmented.getStyleClass().add("usage-segmented");

        sortCombo = new ComboBox<>();
        sortCombo.getStyleClass().add("pill-combo");
        sortCombo.setConverter(new StringConverter<>() {
            /** 排序键显示名：I18n 翻译；null 显示空串。 */
            @Override
            public String toString(String key) {
                return key == null ? "" : I18n.t(key);
            }

            /** 下拉框不可自行输入（回写恒 null，选择经 items 完成）。 */
            @Override
            public String fromString(String s) {
                return null;
            }
        });
        sortCombo.setOnAction(e -> {
            String sel = sortCombo.getSelectionModel().getSelectedItem();
            if (sel != null) {
                taskSort = sel;
                renderBody();
            }
        });

        HBox box = new HBox(10, segmented, sortCombo);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    private ToggleButton filterToggle(String value) {
        ToggleButton tb = new ToggleButton();
        tb.getStyleClass().add("usage-seg-btn");
        tb.setUserData(value);
        tb.setOnAction(e -> {
            selectFilter((String) tb.getUserData());
            renderBody();
        });
        return tb;
    }

    /** 单选语义：点击已选中项时 ToggleButton 会自行取消选中，这里统一回收敛。 */
    private void selectFilter(String value) {
        taskFilter = value;
        fAll.setSelected("ALL".equals(value));
        fReady.setSelected("READY".equals(value));
        fPaused.setSelected("PAUSED".equals(value));
        fDone.setSelected("DONE".equals(value));
    }

    private void applyI18n() {
        titleLabel.setText(I18n.t("tasks.title"));
        descLabel.setText(I18n.t("tasks.desc"));
        createButton.setText(I18n.t("tasks.new"));
        if (refreshButton.getTooltip() != null) {
            refreshButton.getTooltip().setText(I18n.t("common.refresh"));
        }
        bannerTitle.setText(I18n.t("tasks.keepAwake.title"));
        bannerDesc.setText(I18n.t("tasks.keepAwake.desc"));
        tabAuto.setText(I18n.t("tasks.tab.auto"));
        tabTemplates.setText(I18n.t("tasks.tab.templates"));
        tabRuns.setText(I18n.t("tasks.tab.runs"));
        fAll.setText(I18n.t("tasks.filter.all"));
        fReady.setText(I18n.t("tasks.filter.ready"));
        fPaused.setText(I18n.t("tasks.filter.paused"));
        fDone.setText(I18n.t("tasks.filter.done"));
        // 排序下拉：重建选项保留当前选中（程序化 select 不触发 onAction，需手动同步）
        String sel = sortCombo.getSelectionModel().getSelectedItem();
        sortCombo.getItems().setAll("tasks.sort.newest", "tasks.sort.oldest",
                "tasks.sort.lastRun", "tasks.sort.name");
        if (sel != null && sortCombo.getItems().contains(sel)) {
            sortCombo.getSelectionModel().select(sel);
        } else {
            sortCombo.getSelectionModel().selectFirst();
        }
        taskSort = sortCombo.getSelectionModel().getSelectedItem();
    }

    // ---- 页签内容 ----

    private void renderBody() {
        body.getChildren().setAll(switch (activeTab) {
            case 1 -> templatesPane();
            case 2 -> runsPane();
            default -> automationPane();
        });
    }

    private Node automationPane() {
        List<ScheduledTask> shown = allTasks.stream()
                .filter(this::matchFilter)
                .sorted(comparator())
                .toList();
        if (shown.isEmpty()) {
            return allTasks.isEmpty()
                    ? emptyState(I18n.t("tasks.empty.title"), I18n.t("tasks.empty.desc"))
                    : emptyState(I18n.t("tasks.filter.emptyTitle"), I18n.t("tasks.filter.emptyDesc"));
        }
        VBox list = new VBox(10);
        list.getStyleClass().add("automation-list");
        for (ScheduledTask t : shown) {
            list.getChildren().add(card(t));
        }
        return scroller(list);
    }

    private boolean matchFilter(ScheduledTask t) {
        String s = t.getStatus() == null ? "" : t.getStatus().toUpperCase(Locale.ROOT);
        return switch (taskFilter) {
            case "READY" -> "READY".equals(s);
            case "PAUSED" -> "PAUSED".equals(s);
            case "DONE" -> "FINISHED".equals(s) || "FAILED".equals(s);
            default -> true;
        };
    }

    private Comparator<ScheduledTask> comparator() {
        Comparator<ScheduledTask> byCreatedDesc = Comparator.comparing(ScheduledTask::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder()));
        return switch (taskSort == null ? "" : taskSort) {
            case "tasks.sort.oldest" -> Comparator.comparing(ScheduledTask::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "tasks.sort.lastRun" -> Comparator.comparing(ScheduledTask::getLastRunAt,
                    Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(byCreatedDesc);
            case "tasks.sort.name" -> Comparator.comparing(
                    (ScheduledTask t) -> t.getTaskName() == null ? "" : t.getTaskName(),
                    String.CASE_INSENSITIVE_ORDER);
            default -> byCreatedDesc;
        };
    }

    // ---- 自动化卡片 ----

    private Node card(ScheduledTask t) {
        VBox card = new VBox(6);
        card.getStyleClass().add("task-card");

        Label name = new Label(displayName(t));
        name.getStyleClass().add("card-title");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMinWidth(0);
        HBox.setHgrow(name, Priority.ALWAYS);

        Button menuBtn = iconButton("\uE712", null);
        menuBtn.setOnAction(e -> showCardMenu(menuBtn, t));

        HBox top = new HBox(10, name, statusBadge(t.getStatus()), menuBtn);
        top.setAlignment(Pos.CENTER_LEFT);

        Label sched = new Label(scheduleText(t));
        sched.getStyleClass().add("automation-sched");

        Label prompt = new Label(truncate(t.getPrompt(), 140));
        prompt.getStyleClass().add("card-desc");
        prompt.setWrapText(true);

        card.getChildren().addAll(top, sched, prompt);
        String timeline = timelineText(t);
        if (!timeline.isEmpty()) {
            Label meta = new Label(timeline);
            meta.getStyleClass().add("card-meta");
            card.getChildren().add(meta);
        }
        if (t.getEndAt() != null) {
            boolean expired = TaskAdminService.isExpired(t);
            Label expire = new Label(expired
                    ? I18n.t("tasks.card.expired")
                    : I18n.t("tasks.card.expireAt", FMT_MIN.format(t.getEndAt())));
            expire.getStyleClass().add(expired ? "automation-expire-warn" : "automation-expire");
            card.getChildren().add(expire);
        }
        return card;
    }

    /** 卡片右上角操作菜单：暂停/恢复、立即执行、执行历史、编辑、删除。 */
    private void showCardMenu(Button anchor, ScheduledTask t) {
        ContextMenu menu = new ContextMenu();
        boolean ready = TaskStatus.READY.name().equalsIgnoreCase(t.getStatus());
        boolean finished = TaskStatus.FINISHED.name().equalsIgnoreCase(t.getStatus());

        if (!finished) {
            MenuItem pause = new MenuItem(I18n.t(ready ? "tasks.action.pause" : "tasks.action.resume"));
            pause.setOnAction(e -> toggleStatus(t, ready));
            menu.getItems().add(pause);
        }
        MenuItem runNow = new MenuItem(I18n.t("tasks.action.runNow"));
        runNow.setOnAction(e -> taskAdminService.runNow(t.getTaskCode()));
        MenuItem history = new MenuItem(I18n.t("tasks.action.history"));
        history.setOnAction(e -> openHistory(t));
        MenuItem edit = new MenuItem(I18n.t("tasks.action.edit"));
        edit.setOnAction(e -> openForm(t, null));
        MenuItem delete = new MenuItem(I18n.t("tasks.action.delete"));
        delete.setOnAction(e -> confirmDelete(t));
        menu.getItems().addAll(runNow, history, edit, new SeparatorMenuItem(), delete);
        menu.show(anchor, Side.BOTTOM, 0, 4);
    }

    private void toggleStatus(ScheduledTask t, boolean ready) {
        try {
            taskAdminService.setStatus(t.getTaskCode(), ready ? TaskStatus.PAUSED : TaskStatus.READY);
        } catch (RuntimeException e) {
            GimDialog.message(owner(), I18n.t("tasks.action.failTitle"), null, e.getMessage());
        }
        reload();
    }

    private Label statusBadge(String status) {
        Label b = new Label();
        b.getStyleClass().add("badge");
        String s = status == null ? "" : status.toUpperCase(Locale.ROOT);
        switch (s) {
            case "READY" -> {
                b.setText(I18n.t("tasks.status.ready"));
                b.getStyleClass().add("badge-green");
            }
            case "PAUSED" -> {
                b.setText(I18n.t("tasks.status.paused"));
                b.getStyleClass().add("badge-gray");
            }
            case "FINISHED" -> {
                b.setText(I18n.t("tasks.status.finished"));
                b.getStyleClass().add("badge-gray");
            }
            case "FAILED" -> {
                b.setText(I18n.t("tasks.status.failed"));
                b.getStyleClass().add("badge-red");
            }
            default -> {
                b.setText(s);
                b.getStyleClass().add("badge-gray");
            }
        }
        return b;
    }

    /** 卡片调度行：常见 cron 形态转友好文案，无法识别原样展示。 */
    private String scheduleText(ScheduledTask t) {
        if (t.getRunAt() != null) {
            return I18n.t("tasks.sched.onceAt", FMT_MIN.format(t.getRunAt()));
        }
        String cron = t.getCronExpression() == null ? "" : t.getCronExpression().trim();
        Matcher m;
        if ((m = P_DAILY.matcher(cron)).matches()) {
            return I18n.t("tasks.sched.dailyAt", hm(m.group(2), m.group(1)));
        }
        if ((m = P_WEEKLY.matcher(cron)).matches()) {
            return I18n.t("tasks.sched.weeklyAt",
                    I18n.t("tasks.weekday." + m.group(3).toLowerCase(Locale.ROOT)), hm(m.group(2), m.group(1)));
        }
        if ((m = P_MONTHLY.matcher(cron)).matches()) {
            return I18n.t("tasks.sched.monthlyAt", m.group(3), hm(m.group(2), m.group(1)));
        }
        if ((m = P_EVERY_HOURS.matcher(cron)).matches()) {
            return I18n.t("tasks.sched.everyHoursAt", m.group(1));
        }
        if ((m = P_EVERY_MINUTES.matcher(cron)).matches()) {
            return I18n.t("tasks.sched.everyMinutesAt", m.group(1));
        }
        return I18n.t("tasks.sched.customAt", cron);
    }

    /** 时间线行：运行中任务给「下次执行」，有历史给「上次执行」，两者都有则并列。 */
    private String timelineText(ScheduledTask t) {
        List<String> parts = new ArrayList<>();
        if (TaskStatus.READY.name().equalsIgnoreCase(t.getStatus())) {
            String next = nextRunText(t);
            if (next != null) {
                parts.add(next);
            }
        }
        if (t.getLastRunAt() != null) {
            parts.add(I18n.t("tasks.lastRun", FMT_MIN.format(t.getLastRunAt())));
        }
        return String.join("  ·  ", parts);
    }

    private String nextRunText(ScheduledTask t) {
        // 一次性指定时刻任务：展示设定的执行时刻
        if (t.getRunAt() != null) {
            return I18n.t("tasks.nextRun", FMT_MIN.format(t.getRunAt()));
        }
        if (t.getCronExpression() != null) {
            try {
                Date next = new CronExpression(t.getCronExpression().trim()).getNextValidTimeAfter(new Date());
                if (next != null) {
                    return I18n.t("tasks.nextRun", FMT_MIN.format(
                            LocalDateTime.ofInstant(next.toInstant(), java.time.ZoneId.systemDefault())));
                }
            } catch (ParseException ignored) {
                // 非法 cron 交由保存时校验，这里降级不显示
            }
        }
        return null;
    }

    // ---- 模板页签 ----

    private Node templatesPane() {
        List<PromptTemplate> templates = templateAdminService.listEnabled();
        if (templates.isEmpty()) {
            return emptyState(I18n.t("tasks.templates.empty"), I18n.t("tasks.templates.emptyDesc"));
        }
        // 卡片网格：对标设置页信息卡片（provider-card），窄窗口自动换行
        FlowPane grid = new FlowPane(12, 12);
        grid.getStyleClass().add("automation-list");
        grid.setAlignment(Pos.TOP_LEFT);
        grid.setRowValignment(VPos.TOP);
        for (PromptTemplate tp : templates) {
            grid.getChildren().add(templateCard(tp));
        }
        return scroller(grid);
    }

    /** 模板卡片：对标设置页模板卡片——卡头（名称 + 状态徽标 + 使用入口）+ 信息行。 */
    private Node templateCard(PromptTemplate tp) {
        boolean enabled = EnabledStatus.isEnabled(tp.getEnabled());
        Label name = new Label(tp.getTemplateName() == null || tp.getTemplateName().isBlank()
                ? tp.getTemplateCode() : tp.getTemplateName());
        name.getStyleClass().add("provider-card-title");
        Label badge = new Label(I18n.t(enabled ? "settings.template.enabled" : "settings.template.disabled"));
        badge.getStyleClass().addAll("badge", enabled ? "badge-green" : "badge-gray");
        Button use = CardKit.iconTextButton("\uE73E", I18n.t("tasks.templates.use"),
                () -> openForm(null, tp.getContent()));
        use.getStyleClass().add("link-btn");

        VBox card = new VBox(8, CardKit.cardHeadRow(name, badge, use),
                CardKit.kvLine(I18n.t("tasks.templates.code"), tp.getTemplateCode()),
                CardKit.kvLine(I18n.t("settings.template.desc"), truncate(tp.getDescription(), 72)),
                CardKit.kvLine(I18n.t("tasks.templates.content"), truncate(tp.getContent(), 120)));
        card.getStyleClass().add("provider-card");
        card.setPrefWidth(400);
        card.setMaxWidth(400);
        return card;
    }

    // ---- 执行记录页签 ----

    private Node runsPane() {
        List<TaskRun> runs = taskAdminService.recentRuns(50);
        if (runs.isEmpty()) {
            return emptyState(I18n.t("tasks.runs.empty"), I18n.t("tasks.runs.emptyDesc"));
        }
        Map<String, String> names = new HashMap<>();
        for (ScheduledTask t : allTasks) {
            names.putIfAbsent(t.getTaskCode(), displayName(t));
        }
        VBox list = new VBox(10);
        list.getStyleClass().add("automation-list");
        for (TaskRun r : runs) {
            list.getChildren().add(runRow(r, names.getOrDefault(r.getTaskCode(), r.getTaskCode())));
        }
        return scroller(list);
    }

    private Node runRow(TaskRun r, String taskName) {
        VBox row = new VBox(6);
        row.getStyleClass().add("task-card");

        Label badge = new Label(I18n.t(r.isSuccess() ? "tasks.runs.ok" : "tasks.runs.fail"));
        badge.getStyleClass().addAll("badge", r.isSuccess() ? "badge-green" : "badge-red");

        Label name = new Label(taskName);
        name.getStyleClass().add("card-title");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMinWidth(0);
        HBox.setHgrow(name, Priority.ALWAYS);

        Label time = new Label(runTimeText(r));
        time.getStyleClass().add("card-meta");

        HBox head = new HBox(10, badge, name, time);
        head.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().add(head);

        String detail = r.isSuccess() ? r.getSummary() : r.getError();
        Label text = new Label(truncate(detail, 200));
        text.getStyleClass().add("card-desc");
        text.setWrapText(true);
        row.getChildren().add(text);

        if (sessionOpener != null && taskAdminService.sessionExists(r.getSessionId())) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button view = linkButton(I18n.t("tasks.runs.viewSession"),
                    () -> sessionOpener.accept(r.getSessionId()));
            HBox foot = new HBox(spacer, view);
            foot.setAlignment(Pos.CENTER_RIGHT);
            row.getChildren().add(foot);
        }
        return row;
    }

    private String runTimeText(TaskRun r) {
        if (r.getStartedAt() == null) {
            return "";
        }
        String base = FMT_MIN.format(r.getStartedAt());
        if (r.getFinishedAt() == null) {
            return base;
        }
        long seconds = Math.max(0, Duration.between(r.getStartedAt(), r.getFinishedAt()).toSeconds());
        return I18n.t("tasks.runs.time", base, String.valueOf(seconds));
    }

    // ---- 新建 / 编辑 / 历史 / 删除 ----

    private void openForm(ScheduledTask editing, String presetPrompt) {
        TaskFormDialog.show(owner(), editing, presetPrompt, taskAdminService,
                templateAdminService, agentAdminService, voiceInputService, this::reload);
    }

    /** 执行历史弹窗：列出该任务最近的触发记录（时间/成败/摘要）。 */
    private void openHistory(ScheduledTask t) {
        GimDialog.Shell shell = GimDialog.shell(owner(), I18n.t("tasks.history.title", displayName(t)));
        List<TaskRun> runs = taskAdminService.history(t.getTaskCode());
        VBox list = new VBox(6);
        if (runs.isEmpty()) {
            Label none = new Label(I18n.t("tasks.history.empty"));
            none.getStyleClass().add("card-desc");
            list.getChildren().add(none);
        }
        for (TaskRun r : runs) {
            Label time = new Label(r.getStartedAt() == null ? "-" : FMT_FULL.format(r.getStartedAt()));
            time.getStyleClass().add("card-meta");
            Label status = new Label(I18n.t(r.isSuccess() ? "tasks.history.ok" : "tasks.history.fail"));
            status.getStyleClass().addAll("badge", r.isSuccess() ? "badge-green" : "badge-red");
            String detail = r.isSuccess()
                    ? (r.getSummary() == null ? "" : r.getSummary())
                    : (r.getError() == null ? "" : r.getError());
            Label bodyLabel = new Label(truncate(detail, 200));
            bodyLabel.getStyleClass().add("card-desc");
            bodyLabel.setWrapText(true);
            bodyLabel.setMaxWidth(440);
            HBox head = new HBox(10, time, status);
            head.setAlignment(Pos.CENTER_LEFT);
            VBox row = new VBox(2, head, bodyLabel);
            row.getStyleClass().add("task-card");
            list.getChildren().add(row);
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(360);
        Button close = new Button(I18n.t("common.close"));
        close.setOnAction(e -> shell.stage().close());
        HBox actions = new HBox(close);
        actions.setAlignment(Pos.CENTER_RIGHT);
        shell.body().getChildren().addAll(scroll, actions);
        shell.show();
    }

    private void confirmDelete(ScheduledTask t) {
        if (GimDialog.confirm(owner(), I18n.t("nav.tasks"), I18n.t("tasks.deleteConfirm", displayName(t)))) {
            taskAdminService.delete(t.getTaskCode());
            reload();
        }
    }

    // ---- 小工具 ----

    private Window owner() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    private Node emptyState(String title, String desc) {
        VBox box = new VBox();
        box.getStyleClass().add("empty-state");
        box.setMaxWidth(Double.MAX_VALUE);
        // 子项不拉满宽度：图标/标题/描述按自身宽度水平居中（配合 .empty-state 的 -fx-alignment: center）
        box.setFillWidth(false);
        // 撑满 body 剩余高度：整块（图标+文字）在页面垂直方向居中
        VBox.setVgrow(box, Priority.ALWAYS);
        Label icon = new Label("\uE823");
        icon.getStyleClass().add("empty-icon");
        Label t = new Label(title);
        t.getStyleClass().add("empty-title");
        Label d = new Label(desc);
        d.getStyleClass().add("empty-desc");
        d.setWrapText(true);
        d.setMaxWidth(460);
        box.getChildren().addAll(icon, t, d);
        return box;
    }

    private ScrollPane scroller(Node content) {
        ScrollPane sc = new ScrollPane(content);
        sc.setFitToWidth(true);
        sc.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sc.getStyleClass().add("usage-scroll");
        return sc;
    }

    private Button iconButton(String iconText, Runnable action) {
        Button b = new Button();
        b.getStyleClass().add("icon-btn");
        b.setTooltip(new Tooltip());
        Label icon = new Label(iconText);
        icon.getStyleClass().add("icon");
        b.setGraphic(icon);
        if (action != null) {
            b.setOnAction(e -> action.run());
        }
        return b;
    }

    private Button linkButton(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("link-btn");
        b.setOnAction(e -> action.run());
        return b;
    }

    private static String displayName(ScheduledTask t) {
        return t.getTaskName() == null || t.getTaskName().isBlank() ? t.getTaskCode() : t.getTaskName();
    }

    private static String hm(String hour, String minute) {
        return String.format("%02d:%02d", Integer.parseInt(hour), Integer.parseInt(minute));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }
}
