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

import com.gettyio.jwork.agent.core.capability.CapabilityCodec;
import com.gettyio.jwork.agent.core.capability.McpConnectionProbe;
import com.gettyio.jwork.agent.domain.entity.McpServerConfig;
import com.gettyio.jwork.agent.domain.enums.EnabledStatus;
import com.gettyio.jwork.agent.domain.enums.McpConnectionStatus;
import com.gettyio.jwork.agent.domain.enums.McpTransportType;
import com.gettyio.jwork.agent.ui.i18n.I18n;
import com.gettyio.jwork.agent.ui.config.McpAdminService;
import com.gettyio.jwork.agent.ui.control.CardKit;
import com.gettyio.jwork.agent.ui.control.KeyValueEditor;
import com.gettyio.jwork.agent.ui.control.ToggleSwitch;
import com.gettyio.jwork.agent.ui.market.MarketService;
import com.gettyio.jwork.agent.ui.market.McpMarketDialog;
import com.gettyio.jwork.agent.ui.util.UiStyles;
import com.gettyio.jwork.agent.ui.window.dialog.JworkDialog;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务页：可复用的自包含组件（页头 + 服务信息卡片 + 新增/编辑模态表单 + 删除）。
 * <p>直连 {@link McpAdminService}，状态提示内聚于本页底部，便于在「设置」与「拓展」两处
 * 各自独立实例化复用，互不干扰场景图父子关系。语言切换时自动刷新文案并重绘卡片。</p>
 *
 * @author gogym
 */
public class McpView {

    /** 信息卡片固定宽度：不铺满整行；有内容时左对齐，空状态居中。 */
    private static final double CARD_WIDTH = 400;

    private final McpAdminService mcpAdminService;
    /** 在线市场：从市场索引一键添加 MCP 配置模板（含依赖探测引导） */
    private final MarketService marketService;

    private final VBox root = new VBox();
    private final TilePane grid = new TilePane();
    private final StackPane body = new StackPane();
    private ScrollPane scroller;
    private final Label titleLabel = new Label();
    private final Label subtitleLabel = new Label();
    private final Button addBtn = new Button();
    private final Button marketBtn = new Button();
    private final Label statusLabel = new Label("");

    private final TextField codeField = new TextField();
    private final TextField nameField = new TextField();
    private final ComboBox<String> transportCombo = new ComboBox<>();
    private final TextField commandField = new TextField();
    private final TextField argsField = new TextField();
    private final TextField baseUrlField = new TextField();
    private final TextField timeoutField = new TextField();
    private final ToggleSwitch enabledCheck = new ToggleSwitch();
    /** STDIO 环境变量 / HTTP-SSE 请求头：键值对编辑器（值掩码+显形），Key 的输入口 */
    private final KeyValueEditor envEditor = new KeyValueEditor("API_KEY", "sk-…");
    private final KeyValueEditor headersEditor = new KeyValueEditor("Authorization", "Bearer sk-…");
    private final Label commandHint = hintLabel();
    private final Label argsHint = hintLabel();
    private final Label envHint = hintLabel();
    private final Label headersHint = hintLabel();
    /** 当前对话框内的表单栅格：切换传输类型时原地重建行（null 表示对话框未开） */
    private GridPane formGrid;

    public McpView(McpAdminService mcpAdminService, MarketService marketService) {
        this.mcpAdminService = mcpAdminService;
        this.marketService = marketService;
        buildUi();
        I18n.onChange(lang -> {
            applyI18n();
            reload();
        });
    }

    public VBox getNode() {
        return root;
    }

    /** 控制页头一级标题显隐（拓展页 Tab 内隐藏，避免与标签名重复；设置页保留）。 */
    public void showHeaderTitle(boolean visible) {
        titleLabel.setVisible(visible);
        titleLabel.setManaged(visible);
    }

    private void buildUi() {
        // 拓展页 Tab 内的 MCP 视图：容器内边距对齐 .content-page（技能页），不再吃 .settings-page 的 26px 顶距
        root.getStyleClass().addAll(UiStyles.SETTINGS_PAGE, "mcp-page");
        root.setMaxWidth(Double.MAX_VALUE);
        root.setMaxHeight(Double.MAX_VALUE);

        titleLabel.getStyleClass().add("page-title");
        subtitleLabel.getStyleClass().add("page-subtitle");
        // 窄窗口下描述不换行，压缩到极限后省略号截断，始终保住右侧操作区
        subtitleLabel.setMinWidth(0);
        subtitleLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        addBtn.getStyleClass().add(UiStyles.BTN_PRIMARY);
        addBtn.setMinWidth(Region.USE_PREF_SIZE);
        Label addIcon = new Label("\uE710");
        addIcon.getStyleClass().add("icon");
        addBtn.setGraphic(addIcon);
        addBtn.setOnAction(e -> openDialog(null));

        // 市场入口：默认中性样式，与右侧主按钮「添加」形成层次
        marketBtn.setMinWidth(Region.USE_PREF_SIZE);
        Label marketIcon = new Label("\uE719");
        marketIcon.getStyleClass().add("icon");
        marketBtn.setGraphic(marketIcon);
        marketBtn.setOnAction(e -> openMarket());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        // 新增按钮与描述同一行：行内中心对齐使按钮中心线与描述文字中心共线，标题独占上一行
        HBox descRow = new HBox(10, subtitleLabel, spacer, marketBtn, addBtn);
        descRow.setAlignment(Pos.CENTER_LEFT);
        VBox headText = new VBox(4, titleLabel, descRow);
        headText.setMaxWidth(Double.MAX_VALUE);

        HBox header = new HBox(headText);
        HBox.setHgrow(headText, Priority.ALWAYS);
        header.getStyleClass().add(UiStyles.PAGE_HEADER);

        transportCombo.getItems().setAll(
                McpTransportType.STDIO.name(), McpTransportType.HTTP.name(), McpTransportType.SSE.name());
        // 语义化 placeholder：命令是可执行程序，参数是按顺序传给它的 argv（两种写法都收）
        commandField.setPromptText("npx / uvx / node");
        argsField.setPromptText("[\"-y\", \"@scope/package\", \"arg\"]");
        baseUrlField.setPromptText("https://example.com/mcp");
        transportCombo.valueProperty().addListener((obs, was, now) -> rebuildForm());

        grid.getStyleClass().add("card-grid");
        grid.setHgap(14);
        grid.setVgap(14);
        grid.setPrefColumns(2);
        grid.setTileAlignment(Pos.TOP_LEFT);
        scroller = new ScrollPane(grid);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        body.getChildren().add(scroller);
        VBox.setVgrow(body, Priority.ALWAYS);

        statusLabel.getStyleClass().add("status-label");
        statusLabel.setPadding(new Insets(6, 0, 0, 0));

        root.getChildren().addAll(header, body, statusLabel);
        applyI18n();
        reload();
    }

    private void applyI18n() {
        titleLabel.setText(I18n.t("settings.menu.mcp"));
        subtitleLabel.setText(I18n.t("settings.mcp.subtitle"));
        addBtn.setText(I18n.t("settings.mcp.add"));
        marketBtn.setText(I18n.t("market.entry"));
    }

    /** 依据库中数据重绘 MCP 服务信息卡片。 */
    public void reload() {
        grid.getChildren().clear();
        List<McpServerConfig> servers = mcpAdminService.list();
        if (servers.isEmpty()) {
            body.getChildren().setAll(scroller, emptyState());
            return;
        }
        body.getChildren().setAll(scroller);
        for (McpServerConfig m : servers) {
            grid.getChildren().add(card(m));
        }
    }

    // ---- 卡片 ----

    private VBox emptyState() {
        VBox box = new VBox();
        box.getStyleClass().add(UiStyles.EMPTY_STATE);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFillWidth(false);
        Label icon = new Label("\uE71B");
        icon.getStyleClass().add(UiStyles.EMPTY_ICON);
        Label t = new Label(I18n.t("settings.mcp.empty"));
        t.getStyleClass().add(UiStyles.EMPTY_TITLE);
        t.setTextAlignment(TextAlignment.CENTER);
        Label d = new Label(I18n.t("settings.mcp.emptyDesc"));
        d.getStyleClass().add(UiStyles.EMPTY_DESC);
        d.setWrapText(true);
        d.setTextAlignment(TextAlignment.CENTER);
        d.setMaxWidth(460);
        box.getChildren().addAll(icon, t, d);
        return box;
    }

    private VBox card(McpServerConfig m) {
        boolean enabled = EnabledStatus.isEnabled(m.getEnabled());
        Label name = new Label(m.getServerName() == null || m.getServerName().isBlank()
                ? m.getServerCode() : m.getServerName());
        name.getStyleClass().add(UiStyles.PROVIDER_CARD_TITLE);
        Label badge = new Label(I18n.t(enabled ? "settings.mcp.enabled" : "settings.mcp.disabled"));
        badge.getStyleClass().addAll("badge", enabled ? UiStyles.BADGE_GREEN : UiStyles.BADGE_GRAY);
        // 测试连接：手动重试入口；结果摘要行常显，工具清单点「详情」弹窗查看（参考技能页）
        Label probeSummary = new Label(I18n.t("settings.mcp.tools.hint"));
        probeSummary.getStyleClass().addAll(UiStyles.CARD_META, "status-unknown");
        probeSummary.setWrapText(true);
        probeSummary.setMinWidth(0);
        Button probeDetail = new Button(I18n.t("settings.mcp.tools.show"));
        probeDetail.getStyleClass().add(UiStyles.LINK_BTN);
        probeDetail.setMinWidth(Region.USE_PREF_SIZE);
        probeDetail.setVisible(false);
        probeDetail.setManaged(false);
        HBox probeRow = new HBox(8, probeSummary, probeDetail);
        probeRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(probeSummary, Priority.ALWAYS);
        ProbeFeedback probe = new ProbeFeedback(probeSummary, probeDetail);
        probeDetail.setOnAction(e -> openToolDetail(m, probe.tools));
        // 状态行值单独持有引用：探测后即时同步，避免与工具清单反馈不一致
        Label statusVal = new Label(m.getStatus() == null || m.getStatus().isBlank() ? "-" : m.getStatus());
        statusVal.getStyleClass().add("provider-card-val");
        // 探测回调需要按钮引用自身做禁用/恢复，故保留手写构造而非 CardKit.iconTextButton 一步式
        Button test = new Button(I18n.t("settings.mcp.test"));
        test.getStyleClass().add(UiStyles.LINK_BTN);
        Label testIcon = new Label("\uE72C");
        testIcon.getStyleClass().add("icon");
        test.setGraphic(testIcon);
        test.setOnAction(e -> handleProbe(m, probe, statusVal, test));

        Button edit = CardKit.iconTextButton("\uE70F", I18n.t("common.edit"), () -> openDialog(m));
        edit.getStyleClass().add(UiStyles.LINK_BTN);
        Button del = CardKit.iconTextButton("\uE74D", I18n.t("common.delete"), () -> handleDelete(m));
        del.getStyleClass().addAll(UiStyles.LINK_BTN, UiStyles.LINK_BTN_DANGER);
        HBox head = CardKit.cardHeadRow(name, badge, test, edit, del);

        // 卡片只摆与传输类型相关的地址行；配了密钥再补一行计数（不露值）
        boolean stdio = McpTransportType.STDIO.name().equals(m.getTransportType());
        int secretCount = stdio ? mcpAdminService.envOf(m).size() : mcpAdminService.headersOf(m).size();
        List<Node> lines = new ArrayList<>(List.of(head,
                CardKit.kvLine(I18n.t("settings.mcp.code"), m.getServerCode()),
                CardKit.kvLine(I18n.t("settings.mcp.transport"), m.getTransportType()),
                CardKit.kvLine(I18n.t(stdio ? "settings.mcp.command" : "settings.mcp.baseUrl"),
                        stdio ? m.getCommand() : m.getBaseUrl()),
                CardKit.kvLine(I18n.t("settings.mcp.timeout"), String.valueOf(m.getTimeout())),
                CardKit.kvNodeLine(I18n.t("common.status"), statusVal),
                probeRow));
        if (secretCount > 0) {
            lines.add(4, CardKit.kvLine(I18n.t(stdio ? "settings.mcp.env" : "settings.mcp.headers"),
                    String.valueOf(secretCount)));
        }
        VBox card = new VBox(8);
        card.getChildren().addAll(lines);
        card.getStyleClass().add(UiStyles.PROVIDER_CARD);
        card.setMinWidth(340);
        card.setMaxWidth(CARD_WIDTH);
        return card;
    }

    // ---- 新增 / 编辑 ----

    /** 打开 MCP 市场对话框（添加成功回调刷新本页卡片）。 */
    private void openMarket() {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        new McpMarketDialog(owner, marketService, this::reload).show();
    }

    /** 弹出无边框模态窗口新增/编辑 MCP 服务；{@code editing} 为 null 表示新增。 */
    private void openDialog(McpServerConfig editing) {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        JworkDialog.Shell shell = JworkDialog.shell(owner, I18n.t(editing == null
                ? "settings.mcp.addTitle" : "settings.mcp.editTitle"));

        if (editing == null) {
            formGrid = null;
            clearForm();
        } else {
            formGrid = null;
            loadToForm(editing);
        }

        GridPane form = buildForm();
        form.setPrefWidth(640);

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        Button save = new Button(I18n.t("common.save"));
        save.getStyleClass().add(UiStyles.BTN_PRIMARY);
        save.setOnAction(e -> {
            if (commitForm()) {
                shell.stage().close();
            }
        });
        HBox btns = new HBox(8, cancel, save);
        btns.setAlignment(Pos.CENTER_RIGHT);

        shell.body().getChildren().addAll(form, btns);
        shell.showAndWait();
    }

    private GridPane buildForm() {
        GridPane g = new GridPane();
        g.getStyleClass().add("form-card");
        g.setHgap(14);
        g.setVgap(10);
        formGrid = g;
        fillForm(g);
        return g;
    }

    /** 切换传输类型：只摆当前分区的行重建表单，隐藏行不占 GridPane 行距；重建后对话框跟随自适应尺寸。 */
    private void rebuildForm() {
        if (formGrid == null) {
            return;
        }
        boolean comboFocused = transportCombo.isFocused();
        formGrid.getChildren().clear();
        fillForm(formGrid);
        if (comboFocused) {
            transportCombo.requestFocus();
        }
        Platform.runLater(() -> {
            if (formGrid.getScene() != null && formGrid.getScene().getWindow() instanceof Stage stage) {
                stage.sizeToScene();
            }
        });
    }

    /** 表单行：公共行 + 当前传输类型分区行 + 收尾行；控件节点复用，重建不丢已填值。 */
    private void fillForm(GridPane g) {
        int r = 0;
        g.addRow(r++, formLabel("settings.mcp.code"), grow(codeField));
        g.addRow(r++, formLabel("settings.mcp.name"), grow(nameField));
        g.addRow(r++, formLabel("settings.mcp.transport"), grow(transportCombo));
        // 按传输类型分区：STDIO 才摆命令/参数/环境变量，HTTP/SSE 才摆基础地址/请求头
        if (McpTransportType.STDIO.name().equals(transportCombo.getValue())) {
            Label commandLabel = formLabel("settings.mcp.command");
            commandHint.setText(I18n.t("settings.mcp.command.hint"));
            VBox commandCell = new VBox(4, commandField, commandHint);
            g.addRow(r++, commandLabel, grow(commandCell));
            centerOnFirst(commandLabel, commandField);
            Label argsLabel = formLabel("settings.mcp.args");
            argsHint.setText(I18n.t("settings.mcp.args.hint"));
            VBox argsCell = new VBox(4, argsField, argsHint);
            g.addRow(r++, argsLabel, grow(argsCell));
            centerOnFirst(argsLabel, argsField);
            Label envLabel = formLabel("settings.mcp.env");
            envHint.setText(I18n.t("settings.mcp.env.hint"));
            VBox envCell = new VBox(4, envEditor.getNode(), envHint);
            g.addRow(r++, envLabel, grow(envCell));
            centerOnFirst(envLabel, envEditor.anchorHeightProperty());
        } else {
            g.addRow(r++, formLabel("settings.mcp.baseUrl"), grow(baseUrlField));
            Label headersLabel = formLabel("settings.mcp.headers");
            headersHint.setText(I18n.t("settings.mcp.headers.hint"));
            VBox headersCell = new VBox(4, headersEditor.getNode(), headersHint);
            g.addRow(r++, headersLabel, grow(headersCell));
            centerOnFirst(headersLabel, headersEditor.anchorHeightProperty());
        }
        g.addRow(r++, formLabel("settings.mcp.timeout"), grow(timeoutField));
        g.addRow(r, formLabel("common.enable"), enabledCheck);
    }

    /** 表单标题：最小宽锁预宽，「参数（JSON 数组）」这类长标题不被列压缩省略号截断。 */
    private static Label formLabel(String key) {
        Label label = new Label(I18n.t(key));
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    /** 标题与输入框同水平中线：顶对齐单元格后按首个控件的高度差平移（灰字提示不参与对齐）。 */
    private static void centerOnFirst(Label label, Region first) {
        centerOnFirst(label, first.heightProperty());
    }

    /** 同口径的锚高度版：键值对编辑器以首行输入框（无行时添加按钮）为锚。 */
    private static void centerOnFirst(Label label, javafx.beans.property.ReadOnlyDoubleProperty anchorHeight) {
        GridPane.setValignment(label, VPos.TOP);
        label.translateYProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(0, (anchorHeight.get() - label.getHeight()) / 2),
                anchorHeight, label.heightProperty()));
    }

    /** 第二列单元随表单宽度拉伸（键值对编辑器需要整行宽）。 */
    private static <T extends Node> T grow(T cell) {
        GridPane.setHgrow(cell, Priority.ALWAYS);
        if (cell instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        return cell;
    }

    /** 表单内语义提示：小字灰文、可换行。 */
    private static Label hintLabel() {
        Label label = new Label();
        label.getStyleClass().add(UiStyles.ROW_DESC);
        label.setWrapText(true);
        label.setMinWidth(0);
        return label;
    }

    private void clearForm() {
        codeField.clear();
        nameField.clear();
        commandField.clear();
        argsField.clear();
        baseUrlField.clear();
        timeoutField.setText("30");
        envEditor.setEntries(Map.of());
        headersEditor.setEntries(Map.of());
        transportCombo.setValue(McpTransportType.STDIO.name());
        enabledCheck.setSelected(true);
        codeField.setDisable(false);
        statusLabel.setText(I18n.t("settings.status.newMcp"));
    }

    private void loadToForm(McpServerConfig m) {
        codeField.setText(m.getServerCode());
        codeField.setDisable(true);
        nameField.setText(m.getServerName());
        transportCombo.setValue(m.getTransportType());
        commandField.setText(m.getCommand());
        argsField.setText(m.getArgsJson());
        baseUrlField.setText(m.getBaseUrl());
        envEditor.setEntries(mcpAdminService.envOf(m));
        headersEditor.setEntries(mcpAdminService.headersOf(m));
        timeoutField.setText(String.valueOf(m.getTimeout()));
        enabledCheck.setSelected(EnabledStatus.isEnabled(m.getEnabled()));
    }

    private boolean commitForm() {
        if (codeField.getText().isBlank()) {
            statusLabel.setText(I18n.t("settings.status.mcpCodeEmpty"));
            return false;
        }
        boolean stdio = McpTransportType.STDIO.name().equals(transportCombo.getValue());
        if (stdio && commandField.getText().isBlank()) {
            statusLabel.setText(I18n.t("settings.status.mcpCommandEmpty"));
            return false;
        }
        if (!stdio && baseUrlField.getText().isBlank()) {
            statusLabel.setText(I18n.t("settings.status.mcpUrlEmpty"));
            return false;
        }
        McpServerConfig m = new McpServerConfig();
        m.setServerCode(codeField.getText().trim());
        m.setServerName(nameField.getText().trim());
        m.setTransportType(transportCombo.getValue());
        m.setCommand(commandField.getText().trim());
        m.setArgsJson(argsField.getText().trim());
        m.setBaseUrl(baseUrlField.getText().trim());
        m.setTimeout(parseInt(timeoutField.getText(), 30));
        m.setEnabled(enabledCheck.isSelected() ? 1 : 0);
        // 键值对编辑器录入的密钥（此处收明文，save 时由服务加密入库）
        m.setEnvJson(CapabilityCodec.writeStringMap(envEditor.collect()));
        m.setHeadersJson(CapabilityCodec.writeStringMap(headersEditor.collect()));
        // 保留库中未暴露于表单的连接状态
        mcpAdminService.list().stream()
                .filter(ex -> ex.getServerCode().equals(m.getServerCode()))
                .findFirst().ifPresent(ex -> m.setStatus(ex.getStatus()));
        mcpAdminService.save(m);
        statusLabel.setText(I18n.t("settings.status.savedMcp", m.getServerCode()));
        reload();
        return true;
    }

    private void handleDelete(McpServerConfig sel) {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        if (!JworkDialog.confirm(owner, I18n.t("settings.menu.mcp"),
                I18n.t("settings.mcp.deleteConfirm", sel.getServerCode()))) {
            return;
        }
        mcpAdminService.delete(sel.getServerCode());
        statusLabel.setText(I18n.t("settings.status.deletedMcp", sel.getServerCode()));
        reload();
    }

    // ---- 测试连接 ----

    /** 后台探测连接：成功后展示工具清单并回写状态；连接恢复时让引用它的智能体运行时失效（手动重试生效）。 */
    private void handleProbe(McpServerConfig m, ProbeFeedback probe, Label statusVal, Button trigger) {
        trigger.setDisable(true);
        setProbeTone(probe.summary(), "status-unknown");
        probe.summary().setText(I18n.t("settings.mcp.testing"));
        collapseProbe(probe);
        String code = m.getServerCode();
        Thread worker = new Thread(() -> {
            McpConnectionProbe.ProbeResult result = mcpAdminService.probe(m);
            Platform.runLater(() -> {
                trigger.setDisable(false);
                renderProbeResult(probe, result);
                boolean wasConnected = McpConnectionStatus.CONNECTED.name().equalsIgnoreCase(m.getStatus());
                mcpAdminService.recordStatus(code, result.success());
                String nextStatus = McpConnectionStatus.of(result.success()).name();
                m.setStatus(nextStatus);
                statusVal.setText(nextStatus);
                if (result.success() && !wasConnected) {
                    // 手动重试成功：让引用该服务的智能体重建，下轮对话以新连接重连
                    mcpAdminService.refreshAgentRuntimes(code);
                }
            });
        }, "mcp-probe-" + code);
        worker.setDaemon(true);
        worker.start();
    }

    private void renderProbeResult(ProbeFeedback probe, McpConnectionProbe.ProbeResult result) {
        if (!result.success()) {
            setProbeTone(probe.summary(), UiStyles.STATUS_BAD);
            probe.summary().setText(I18n.t("settings.mcp.test.fail", result.error()));
            collapseProbe(probe);
            return;
        }
        setProbeTone(probe.summary(), "status-ok");
        if (result.tools().isEmpty()) {
            probe.summary().setText(I18n.t("settings.mcp.test.ok.none"));
            collapseProbe(probe);
            return;
        }
        // 成功且有工具：摘要行只显示数量，完整清单点「详情」弹窗查看（参考技能页）
        probe.summary().setText(I18n.t("settings.mcp.test.ok", result.tools().size()));
        probe.tools = result.tools();
        probe.detail().setVisible(true);
        probe.detail().setManaged(true);
    }

    /** 隐藏「详情」按钮并清空缓存工具（失败/无工具/测试中时回到单行摘要）。 */
    private static void collapseProbe(ProbeFeedback probe) {
        probe.tools = List.of();
        probe.detail().setVisible(false);
        probe.detail().setManaged(false);
    }

    /** 测试连接反馈：摘要行（常显）+「详情」按钮（弹窗查看工具清单）+ 最近一次探测到的工具。 */
    private static final class ProbeFeedback {
        private final Label summary;
        private final Button detail;
        private List<McpConnectionProbe.ToolInfo> tools = List.of();

        ProbeFeedback(Label summary, Button detail) {
            this.summary = summary;
            this.detail = detail;
        }

        Label summary() {
            return summary;
        }

        Button detail() {
            return detail;
        }
    }

    private static void setProbeTone(Label label, String tone) {
        label.getStyleClass().removeAll("status-ok", UiStyles.STATUS_BAD, "status-unknown");
        label.getStyleClass().add(tone);
    }

    /** 工具清单详情弹窗：逐条「名称 + 描述」，参考技能页 openDetail 的对话框呈现。 */
    private void openToolDetail(McpServerConfig m, List<McpConnectionProbe.ToolInfo> tools) {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        JworkDialog.Shell shell = JworkDialog.shell(owner, I18n.t("market.tools.title"));
        String name = m.getServerName() == null || m.getServerName().isBlank()
                ? m.getServerCode() : m.getServerName();
        Label head = new Label(name + " · " + I18n.t("settings.mcp.test.ok", tools.size()));
        head.getStyleClass().add(UiStyles.DIALOG_HEADER);
        VBox list = new VBox(8);
        list.setPrefWidth(520);
        for (McpConnectionProbe.ToolInfo t : tools) {
            Label tn = new Label(t.name());
            tn.getStyleClass().add(UiStyles.PROVIDER_CARD_TITLE);
            list.getChildren().add(tn);
            String desc = t.description() == null ? "" : t.description().replaceAll("\\s+", " ").trim();
            if (!desc.isBlank()) {
                Label d = new Label(desc);
                d.getStyleClass().add(UiStyles.CARD_DESC);
                d.setWrapText(true);
                list.getChildren().add(d);
            }
        }
        ScrollPane sp = new ScrollPane(list);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setPrefHeight(360);
        Button close = new Button(I18n.t("common.close"));
        close.setOnAction(e -> shell.stage().close());
        HBox btns = new HBox(8, close);
        btns.setAlignment(Pos.CENTER_RIGHT);
        shell.body().getChildren().addAll(head, sp, btns);
        shell.show();
    }

    // ---- 小工具 ----

    private static int parseInt(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
