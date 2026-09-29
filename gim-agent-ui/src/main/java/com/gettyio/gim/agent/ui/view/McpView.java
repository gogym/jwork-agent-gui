package com.gettyio.gim.agent.ui.view;

import com.gettyio.gim.agent.domain.entity.McpServerConfig;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.enums.McpTransportType;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.config.McpAdminService;
import com.gettyio.gim.agent.ui.control.ToggleSwitch;
import com.gettyio.gim.agent.ui.window.GimDialog;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Window;

import java.util.List;

/**
 * MCP 服务页：可复用的自包含组件（页头 + 服务信息卡片 + 新增/编辑模态表单 + 删除）。
 * <p>直连 {@link McpAdminService}，状态提示内聚于本页底部，便于在「设置」与「拓展」两处
 * 各自独立实例化复用，互不干扰场景图父子关系。语言切换时自动刷新文案并重绘卡片。</p>
 */
public class McpView {

    /** 信息卡片固定宽度：不铺满整行；有内容时左对齐，空状态居中。 */
    private static final double CARD_WIDTH = 400;

    private final McpAdminService mcpAdminService;

    private final VBox root = new VBox();
    private final VBox cardsBox = new VBox(12);
    private final Label titleLabel = new Label();
    private final Label subtitleLabel = new Label();
    private final Button addBtn = new Button();
    private final Label statusLabel = new Label("");

    private final TextField codeField = new TextField();
    private final TextField nameField = new TextField();
    private final ComboBox<String> transportCombo = new ComboBox<>();
    private final TextField commandField = new TextField();
    private final TextField argsField = new TextField();
    private final TextField baseUrlField = new TextField();
    private final TextField timeoutField = new TextField();
    private final ToggleSwitch enabledCheck = new ToggleSwitch();

    public McpView(McpAdminService mcpAdminService) {
        this.mcpAdminService = mcpAdminService;
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
        root.getStyleClass().add("settings-page");
        root.setMaxWidth(Double.MAX_VALUE);
        root.setMaxHeight(Double.MAX_VALUE);

        titleLabel.getStyleClass().add("page-title");
        subtitleLabel.getStyleClass().add("page-subtitle");
        subtitleLabel.setWrapText(true);
        VBox headText = new VBox(4, titleLabel, subtitleLabel);

        addBtn.getStyleClass().add("btn-primary");
        Label addIcon = new Label("\uE710");
        addIcon.getStyleClass().add("icon");
        addBtn.setGraphic(addIcon);
        addBtn.setOnAction(e -> openDialog(null));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(headText, spacer, addBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        transportCombo.getItems().setAll(
                McpTransportType.STDIO.name(), McpTransportType.HTTP.name(), McpTransportType.SSE.name());

        cardsBox.getStyleClass().add("provider-cards");
        cardsBox.setFillWidth(false);
        ScrollPane scroller = new ScrollPane(cardsBox);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setFitToHeight(true);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        statusLabel.getStyleClass().add("status-label");
        statusLabel.setPadding(new Insets(6, 0, 0, 0));

        root.getChildren().addAll(header, scroller, statusLabel);
        applyI18n();
        reload();
    }

    private void applyI18n() {
        titleLabel.setText(I18n.t("settings.menu.mcp"));
        subtitleLabel.setText(I18n.t("settings.mcp.subtitle"));
        addBtn.setText(I18n.t("settings.mcp.add"));
    }

    /** 依据库中数据重绘 MCP 服务信息卡片。 */
    public void reload() {
        cardsBox.getChildren().clear();
        List<McpServerConfig> servers = mcpAdminService.list();
        if (servers.isEmpty()) {
            cardsBox.setAlignment(Pos.CENTER);
            cardsBox.getChildren().add(emptyState());
            return;
        }
        cardsBox.setAlignment(Pos.TOP_LEFT);
        for (McpServerConfig m : servers) {
            cardsBox.getChildren().add(card(m));
        }
    }

    // ---- 卡片 ----

    private VBox emptyState() {
        VBox box = new VBox();
        box.getStyleClass().add("empty-state");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFillWidth(false);
        Label icon = new Label("\uE71B");
        icon.getStyleClass().add("empty-icon");
        Label t = new Label(I18n.t("settings.mcp.empty"));
        t.getStyleClass().add("empty-title");
        t.setTextAlignment(TextAlignment.CENTER);
        Label d = new Label(I18n.t("settings.mcp.emptyDesc"));
        d.getStyleClass().add("empty-desc");
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
        name.getStyleClass().add("provider-card-title");
        Label badge = new Label(I18n.t(enabled ? "settings.mcp.enabled" : "settings.mcp.disabled"));
        badge.getStyleClass().addAll("badge", enabled ? "badge-green" : "badge-gray");
        HBox titleRow = new HBox(10, name, badge);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Button edit = iconTextButton("\uE70F", I18n.t("common.edit"), () -> openDialog(m));
        edit.getStyleClass().add("link-btn");
        Button del = iconTextButton("\uE74D", I18n.t("common.delete"), () -> handleDelete(m));
        del.getStyleClass().addAll("link-btn", "link-btn-danger");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox head = new HBox(titleRow, sp, edit, del);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, head,
                kvLine(I18n.t("settings.mcp.code"), m.getServerCode()),
                kvLine(I18n.t("settings.mcp.transport"), m.getTransportType()),
                kvLine(I18n.t("settings.mcp.command"), m.getCommand()),
                kvLine(I18n.t("settings.mcp.baseUrl"), m.getBaseUrl()),
                kvLine(I18n.t("settings.mcp.timeout"), String.valueOf(m.getTimeout())),
                kvLine(I18n.t("common.status"), m.getStatus()));
        card.getStyleClass().add("provider-card");
        card.setPrefWidth(CARD_WIDTH);
        card.setMaxWidth(CARD_WIDTH);
        return card;
    }

    // ---- 新增 / 编辑 ----

    /** 弹出无边框模态窗口新增/编辑 MCP 服务；{@code editing} 为 null 表示新增。 */
    private void openDialog(McpServerConfig editing) {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        GimDialog.Shell shell = GimDialog.shell(owner, I18n.t(editing == null
                ? "settings.mcp.addTitle" : "settings.mcp.editTitle"));

        if (editing == null) {
            clearForm();
        } else {
            loadToForm(editing);
        }

        GridPane form = buildForm();
        form.setPrefWidth(440);

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        Button save = new Button(I18n.t("common.save"));
        save.getStyleClass().add("btn-primary");
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
        int r = 0;
        g.addRow(r++, new Label(I18n.t("settings.mcp.code")), codeField);
        g.addRow(r++, new Label(I18n.t("settings.mcp.name")), nameField);
        g.addRow(r++, new Label(I18n.t("settings.mcp.transport")), transportCombo);
        g.addRow(r++, new Label(I18n.t("settings.mcp.command")), commandField);
        g.addRow(r++, new Label(I18n.t("settings.mcp.args")), argsField);
        g.addRow(r++, new Label(I18n.t("settings.mcp.baseUrl")), baseUrlField);
        g.addRow(r++, new Label(I18n.t("settings.mcp.timeout")), timeoutField);
        g.addRow(r, new Label(I18n.t("common.enable")), enabledCheck);
        return g;
    }

    private void clearForm() {
        codeField.clear();
        nameField.clear();
        commandField.clear();
        argsField.clear();
        baseUrlField.clear();
        timeoutField.setText("30");
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
        timeoutField.setText(String.valueOf(m.getTimeout()));
        enabledCheck.setSelected(EnabledStatus.isEnabled(m.getEnabled()));
    }

    private boolean commitForm() {
        if (codeField.getText().isBlank()) {
            statusLabel.setText(I18n.t("settings.status.mcpCodeEmpty"));
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
        // 保留库中未暴露于表单的字段（环境变量 / 请求头 JSON / 连接状态）
        mcpAdminService.list().stream()
                .filter(ex -> ex.getServerCode().equals(m.getServerCode()))
                .findFirst().ifPresent(ex -> {
                    m.setEnvJson(ex.getEnvJson());
                    m.setHeadersJson(ex.getHeadersJson());
                    m.setStatus(ex.getStatus());
                });
        mcpAdminService.save(m);
        statusLabel.setText(I18n.t("settings.status.savedMcp", m.getServerCode()));
        reload();
        return true;
    }

    private void handleDelete(McpServerConfig sel) {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        if (!GimDialog.confirm(owner, I18n.t("settings.menu.mcp"),
                I18n.t("settings.mcp.deleteConfirm", sel.getServerCode()))) {
            return;
        }
        mcpAdminService.delete(sel.getServerCode());
        statusLabel.setText(I18n.t("settings.status.deletedMcp", sel.getServerCode()));
        reload();
    }

    // ---- 小工具 ----

    private HBox kvLine(String key, String value) {
        Label k = new Label(key);
        k.getStyleClass().add("provider-card-key");
        Label v = new Label(value == null || value.isBlank() ? "-" : value);
        v.getStyleClass().add("provider-card-val");
        v.setWrapText(true);
        HBox.setHgrow(v, Priority.ALWAYS);
        HBox h = new HBox(12, k, v);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private Button iconTextButton(String iconText, String text, Runnable action) {
        Button b = new Button(text);
        Label ic = new Label(iconText);
        ic.getStyleClass().add("icon");
        b.setGraphic(ic);
        b.setOnAction(e -> action.run());
        return b;
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
