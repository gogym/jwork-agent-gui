package com.gettyio.gim.agent.ui.view;

import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.domain.enums.TimeRange;
import com.gettyio.gim.agent.ui.config.UsageStatsService;
import com.gettyio.gim.agent.ui.config.UsageStatsService.UsageRow;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * token 用量整页：全局概览卡片 + 维度切换（智能体 / 模型 / 会话）明细表。
 * <p>数据全部来自 {@link UsageStatsService} 的实时 SUM；语言切换时刷新文案与数字格式。</p>
 */
public class UsageView {

    private final UsageStatsService statsService;

    private final VBox root = new VBox();
    private final StackPane body = new StackPane();
    private final VBox summaryBox = new VBox();
    private final VBox tableBox = new VBox();
    private final ToggleGroup dimensionGroup = new ToggleGroup();
    private final ToggleGroup rangeGroup = new ToggleGroup();
    private final VBox tilesBox = new VBox();
    private Label titleLabel;
    private Label descLabel;
    private Button refreshButton;
    private Label globalTitleLabel;
    private ToggleButton range7Toggle;
    private ToggleButton range30Toggle;
    private ToggleButton rangeAllToggle;
    private ToggleButton agentToggle;
    private ToggleButton modelToggle;
    private ToggleButton sessionToggle;
    /** 当前选中的时间窗口（概览与明细表共用）；不跨会话持久化，每次进页默认「全部」。 */
    private TimeRange selectedRange = TimeRange.ALL;

    public UsageView(UsageStatsService statsService) {
        this.statsService = statsService;
        buildUi();
        I18n.onChange(lang -> {
            applyI18n();
            reload();
        });
    }

    public VBox getNode() {
        return root;
    }

    private void buildUi() {
        root.getStyleClass().add("content-page");

        titleLabel = new Label();
        titleLabel.getStyleClass().add("page-h1");
        descLabel = new Label();
        descLabel.getStyleClass().add("page-desc");
        // 窄窗口下描述不换行，压缩到极限后省略号隐藏，始终保住右侧操作区
        descLabel.setMinWidth(0);
        descLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        refreshButton = iconButton("\uE72C", this::reload);
        refreshButton.setMinWidth(Region.USE_PREF_SIZE);

        // 刷新按钮与描述同一行：行内中心对齐使按钮中心线与描述文字中心共线，标题独占上一行
        HBox descRow = new HBox(10, descLabel, spacer, refreshButton);
        descRow.setAlignment(Pos.CENTER_LEFT);
        VBox headText = new VBox(4, titleLabel, descRow);
        headText.setMaxWidth(Double.MAX_VALUE);

        HBox header = new HBox(headText);
        HBox.setHgrow(headText, Priority.ALWAYS);
        header.getStyleClass().add("page-header");

        summaryBox.getStyleClass().add("usage-summary");

        // 全局概览标题行：左侧标题 + 右侧时间档分段（近7天 / 近30天 / 全部）
        globalTitleLabel = new Label();
        globalTitleLabel.getStyleClass().add("usage-section-title");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        range7Toggle = rangeToggle("usage.range.last7", TimeRange.LAST_7_DAYS);
        range30Toggle = rangeToggle("usage.range.last30", TimeRange.LAST_30_DAYS);
        rangeAllToggle = rangeToggle("usage.range.all", TimeRange.ALL);
        rangeAllToggle.setSelected(true);
        HBox rangeSegmented = new HBox(range7Toggle, range30Toggle, rangeAllToggle);
        rangeSegmented.getStyleClass().add("usage-segmented");
        HBox globalHeader = new HBox(10, globalTitleLabel, titleSpacer, rangeSegmented);
        globalHeader.getStyleClass().add("usage-section-header");
        tilesBox.getStyleClass().add("usage-tiles-box");
        summaryBox.getChildren().addAll(globalHeader, tilesBox);

        // 维度切换 + 明细表
        agentToggle = dimensionToggle("usage.dimension.agent");
        modelToggle = dimensionToggle("usage.dimension.model");
        sessionToggle = dimensionToggle("usage.dimension.session");
        HBox segmented = new HBox(agentToggle, modelToggle, sessionToggle);
        segmented.getStyleClass().add("usage-segmented");
        agentToggle.setSelected(true);
        agentToggle.setOnAction(e -> reloadTable());
        modelToggle.setOnAction(e -> reloadTable());
        sessionToggle.setOnAction(e -> reloadTable());

        tableBox.getStyleClass().add("usage-table");
        VBox tableWrap = new VBox(8, segmented, tableBox);
        tableWrap.getStyleClass().add("usage-section");

        VBox content = new VBox(16, summaryBox, tableWrap);
        content.setPadding(new Insets(4, 2, 12, 2));

        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.getStyleClass().add("usage-scroll");
        body.getChildren().add(scroller);
        VBox.setVgrow(body, Priority.ALWAYS);

        root.getChildren().addAll(header, body);
        applyI18n();
    }

    private ToggleButton dimensionToggle(String labelKey) {
        ToggleButton tb = new ToggleButton();
        tb.setToggleGroup(dimensionGroup);
        tb.getStyleClass().add("usage-seg-btn");
        tb.setUserData(labelKey);
        tb.setText(I18n.t(labelKey));
        return tb;
    }

    private ToggleButton rangeToggle(String labelKey, TimeRange range) {
        ToggleButton tb = new ToggleButton();
        tb.setToggleGroup(rangeGroup);
        tb.getStyleClass().add("usage-seg-btn");
        tb.setUserData(labelKey);
        tb.setText(I18n.t(labelKey));
        tb.setOnAction(e -> {
            selectedRange = range;
            reload();
        });
        return tb;
    }

    /** 重新加载：全局概览 + 当前维度明细表。 */
    public void reload() {
        renderSummary();
        reloadTable();
    }

    private void applyI18n() {
        titleLabel.setText(I18n.t("usage.title"));
        descLabel.setText(I18n.t("usage.desc"));
        if (refreshButton.getTooltip() != null) {
            refreshButton.getTooltip().setText(I18n.t("common.refresh"));
        }
        globalTitleLabel.setText(I18n.t("usage.section.global"));
        range7Toggle.setText(I18n.t("usage.range.last7"));
        range30Toggle.setText(I18n.t("usage.range.last30"));
        rangeAllToggle.setText(I18n.t("usage.range.all"));
        agentToggle.setText(I18n.t("usage.dimension.agent"));
        modelToggle.setText(I18n.t("usage.dimension.model"));
        sessionToggle.setText(I18n.t("usage.dimension.session"));
    }

    // ---- 全局概览 ----

    private void renderSummary() {
        UsageRow g = statsService.global(selectedRange);
        int calls = g.llmCalls();
        String dash = "\u2014";
        tilesBox.getChildren().setAll(
                tiles(
                        tile("usage.metric.total", number(g.totalTokens())),
                        tile("usage.metric.input", number(g.tokensInput())),
                        tile("usage.metric.output", number(g.tokensOutput())),
                        tile("usage.metric.cached", number(g.tokensCached())),
                        tile("usage.metric.messages", number(g.messageCount())),
                        tile("usage.metric.cost", money(g.estCost()))),
                tiles(
                        tile("usage.metric.calls", number(calls)),
                        tile("usage.metric.elapsed", duration(g.llmElapsedMillis())),
                        tile("usage.metric.avgLatency", calls == 0
                                ? dash : duration(Math.round((double) g.llmElapsedMillis() / calls))),
                        tile("usage.metric.avgTokens", calls == 0
                                ? dash : number(Math.round((double) g.totalTokens() / calls)))));
    }

    private HBox tiles(Node... ts) {
        HBox box = new HBox(12, ts);
        box.getStyleClass().add("usage-tiles");
        box.setAlignment(Pos.TOP_LEFT);
        for (Node t : ts) {
            HBox.setHgrow(t, Priority.ALWAYS);
        }
        return box;
    }

    private VBox tile(String labelKey, String value) {
        Label v = new Label(value);
        v.getStyleClass().add("usage-tile-value");
        Label k = new Label(I18n.t(labelKey));
        k.getStyleClass().add("usage-tile-label");
        k.setWrapText(true);
        VBox box = new VBox(2, v, k);
        box.getStyleClass().add("usage-tile");
        return box;
    }

    // ---- 维度明细表 ----

    private void reloadTable() {
        String dimKey = dimensionGroup.getSelectedToggle() == null
                ? "usage.dimension.agent"
                : (String) dimensionGroup.getSelectedToggle().getUserData();
        List<UsageRow> rows = switch (dimKey) {
            case "usage.dimension.model" -> statsService.byModel(selectedRange);
            case "usage.dimension.session" -> statsService.bySession(selectedRange);
            default -> statsService.byAgent(selectedRange);
        };
        tableBox.getChildren().clear();
        if (rows.isEmpty()) {
            Label empty = new Label(I18n.t("usage.empty"));
            empty.getStyleClass().add("usage-empty");
            tableBox.getChildren().add(empty);
            return;
        }
        tableBox.getChildren().add(tableHeader());
        for (UsageRow r : rows) {
            tableBox.getChildren().add(tableRow(r));
        }
    }

    private Node tableHeader() {
        GridPane head = new GridPane();
        head.getStyleClass().addAll("usage-row", "usage-row-head");
        head.setHgap(10);
        head.setVgap(4);
        head.addRow(0,
                col(I18n.t("usage.col.name")),
                num(I18n.t("usage.metric.input")),
                num(I18n.t("usage.metric.output")),
                num(I18n.t("usage.metric.total")),
                num(I18n.t("usage.metric.cached")),
                num(I18n.t("usage.metric.calls")),
                num(I18n.t("usage.metric.elapsed")),
                num(I18n.t("usage.metric.messages")),
                num(I18n.t("usage.metric.cost")));
        return head;
    }

    private Node tableRow(UsageRow r) {
        GridPane row = new GridPane();
        row.getStyleClass().add("usage-row");
        row.setHgap(10);
        row.setVgap(4);
        row.addRow(0,
                nameCol(displayName(r.name())),
                num(number(r.tokensInput())),
                num(number(r.tokensOutput())),
                num(number(r.totalTokens())),
                num(number(r.tokensCached())),
                num(number(r.llmCalls())),
                num(duration(r.llmElapsedMillis())),
                num(number(r.messageCount())),
                num(money(r.estCost())));
        return row;
    }

    private String displayName(String raw) {
        if (UsageRow.UNTAGGED.equals(raw)) {
            return I18n.t("usage.untagged");
        }
        return raw;
    }

    // ---- 列 / 小工具 ----

    private static Label col(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("usage-cell-name");
        l.setMaxWidth(260);
        l.setEllipsisString("\u2026");
        l.setTooltip(new Tooltip(text));
        GridPane.setHgrow(l, Priority.ALWAYS);
        return l;
    }

    private static Label nameCol(String text) {
        Label l = col(text);
        l.setText(text);
        l.getTooltip().setText(text);
        return l;
    }

    private static Label num(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("usage-cell-num");
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    private Button iconButton(String glyph, Runnable action) {
        Button b = new Button();
        b.getStyleClass().add("icon-btn");
        b.setTooltip(new Tooltip());
        Label icon = new Label(glyph);
        icon.getStyleClass().add("icon");
        b.setGraphic(icon);
        b.setOnAction(e -> action.run());
        return b;
    }

    private static String number(long v) {
        NumberFormat fmt = NumberFormat.getIntegerInstance(I18n.current().locale());
        return fmt.format(v);
    }

    private static String money(double v) {
        if (v == 0.0) {
            return "\u00A00";
        }
        DecimalFormat df = new DecimalFormat("#,##0.00####",
                java.text.DecimalFormatSymbols.getInstance(Locale.ROOT));
        return "\u00A0" + df.format(v);
    }

    /** 毫秒耗时格式化为秒（最多 1 位小数），单位用不间断空格贴紧。 */
    private static String duration(long ms) {
        if (ms <= 0) {
            return "0\u00A0s";
        }
        DecimalFormat df = new DecimalFormat("#,##0.#",
                java.text.DecimalFormatSymbols.getInstance(Locale.ROOT));
        return df.format(ms / 1000.0) + "\u00A0s";
    }
}
