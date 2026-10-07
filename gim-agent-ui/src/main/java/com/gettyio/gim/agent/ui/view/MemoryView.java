package com.gettyio.gim.agent.ui.view;

import com.gettyio.gim.agent.core.memory.MemoryService;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.window.GimDialog;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 长期记忆整页（挂在「拓展」页「记忆」Tab）：按智能体查看 / 编辑 / 清空其 {@code MEMORY.md}，
 * 并列出 {@code memory/*.md} 每日台账供查看与删除。
 * <p>直连 {@link MemoryService}，读写的是 Harness 运行时实际维护的同一批文件——用户在此的编辑会即时
 * 落到 {@code MEMORY.md}，后续对话记忆照常往同一文件沉淀。文案全部走 {@link I18n}，语言切换刷新。</p>
 */
@Component
public class MemoryView {

    @Autowired
    private MemoryService memoryService;

    private final VBox root = new VBox();
    private final ComboBox<AgentChoice> agentCombo = new ComboBox<>();
    private final TextArea memoryArea = new TextArea();
    private final Label pathLabel = new Label();
    private final VBox dailyBox = new VBox(6);
    private final Label dailyTitle = new Label();
    private final Label dailyEmpty = new Label();
    private final Label memHeader = new Label();
    private final Button saveBtn = new Button();
    private final Button clearBtn = new Button();
    private Label titleLabel;
    private Label descLabel;

    /** ComboBox 展示项：以智能体名显示、以编码寻值。 */
    private static final class AgentChoice {
        private final String code;
        private final String display;

        AgentChoice(String code, String display) {
            this.code = code;
            this.display = display;
        }

        @Override
        public String toString() {
            return display;
        }
    }

    public MemoryView() {
        buildUi();
        I18n.onChange(lang -> {
            applyI18n();
            reload();
        });
    }

    public VBox getNode() {
        return root;
    }

    /** 控制一级标题显隐（Tab 内隐藏，避免与标签名重复）。 */
    public void showHeaderTitle(boolean visible) {
        if (titleLabel != null) {
            titleLabel.setVisible(visible);
            titleLabel.setManaged(visible);
        }
    }

    private void buildUi() {
        root.getStyleClass().add("content-page");

        titleLabel = new Label();
        titleLabel.getStyleClass().add("page-h1");
        descLabel = new Label();
        descLabel.getStyleClass().add("page-desc");
        descLabel.setMinWidth(0);
        descLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        Button refreshButton = iconButton("\uE72C", this::reload);
        refreshButton.setMinWidth(Region.USE_PREF_SIZE);

        agentCombo.setMinWidth(180);
        agentCombo.setMaxWidth(280);
        agentCombo.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(AgentChoice item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.display);
            }
        });
        agentCombo.valueProperty().addListener((obs, o, n) -> {
            if (n != null && !Objects.equals(o == null ? null : o.code, n.code)) {
                loadSelected();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox headRow = new HBox(10, descLabel, spacer, agentCombo, refreshButton);
        headRow.setAlignment(Pos.CENTER_LEFT);
        VBox headText = new VBox(4, titleLabel, headRow);
        headText.setMaxWidth(Double.MAX_VALUE);
        HBox header = new HBox(headText);
        HBox.setHgrow(headText, Priority.ALWAYS);
        header.getStyleClass().add("page-header");

        // 长期记忆编辑区
        memHeader.getStyleClass().add("section-title");
        saveBtn.getStyleClass().addAll("btn-primary", "memory-save-btn");
        saveBtn.setMinWidth(Region.USE_PREF_SIZE);
        saveBtn.setOnAction(e -> handleSave());
        clearBtn.getStyleClass().add("link-btn");
        clearBtn.setMinWidth(Region.USE_PREF_SIZE);
        clearBtn.setOnAction(e -> handleClear());
        HBox memToolbar = new HBox(8, memHeader, spacerNode(), saveBtn, clearBtn);
        memToolbar.setAlignment(Pos.CENTER_LEFT);

        memoryArea.getStyleClass().add("memory-editor");
        memoryArea.setWrapText(true);
        memoryArea.setStyle("-fx-font-family: \"Cascadia Mono\", \"Consolas\", monospace; -fx-font-size: 12.5px;");
        VBox.setVgrow(memoryArea, Priority.ALWAYS);
        pathLabel.getStyleClass().add("card-meta");
        pathLabel.setMinWidth(0);
        pathLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        // 每日台账
        dailyTitle.getStyleClass().add("section-title");
        dailyEmpty.getStyleClass().add("card-meta");
        dailyBox.getStyleClass().add("memory-daily-box");

        VBox content = new VBox(10, memToolbar, memoryArea, pathLabel,
                new Separator(), dailyTitle, dailyEmpty, dailyBox);
        content.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(content, Priority.ALWAYS);

        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox body = new VBox(scroller);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        root.getChildren().addAll(header, body);
        applyI18n();
    }

    private static Region spacerNode() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private void applyI18n() {
        if (titleLabel != null) {
            titleLabel.setText(I18n.t("memory.title"));
            descLabel.setText(I18n.t("memory.desc"));
            memHeader.setText(I18n.t("memory.longterm"));
            saveBtn.setText(I18n.t("memory.save"));
            clearBtn.setText(I18n.t("memory.clear"));
            memoryArea.setPromptText(I18n.t("memory.empty.placeholder"));
            dailyTitle.setText(I18n.t("memory.daily"));
            dailyEmpty.setText(I18n.t("memory.daily.empty"));
            // 重绘当前智能体下的台账（含按钮文案）
            loadSelected();
        }
    }

    /** 刷新智能体下拉（保留当前选中），再加载选中项内容。 */
    public void reload() {
        String previous = agentCombo.getValue() == null ? null : agentCombo.getValue().code;
        agentCombo.getItems().clear();
        List<AgentDefinition> agents = memoryService.listAgents();
        for (AgentDefinition a : agents) {
            String name = a.getAgentName() == null || a.getAgentName().isBlank()
                    ? a.getAgentCode() : a.getAgentName();
            agentCombo.getItems().add(new AgentChoice(a.getAgentCode(), name));
        }
        AgentChoice target = null;
        if (previous != null) {
            for (AgentChoice c : agentCombo.getItems()) {
                if (Objects.equals(c.code, previous)) {
                    target = c;
                    break;
                }
            }
        }
        if (target == null && !agentCombo.getItems().isEmpty()) {
            target = agentCombo.getItems().get(0);
        }
        agentCombo.setValue(target);
        loadSelected();
    }

    /** 加载选中智能体的 MEMORY.md 与每日台账到界面。 */
    private void loadSelected() {
        AgentChoice choice = agentCombo.getValue();
        if (choice == null) {
            memoryArea.clear();
            memoryArea.setDisable(true);
            dailyBox.getChildren().clear();
            pathLabel.setText("");
            return;
        }
        memoryArea.setDisable(false);
        String code = choice.code;
        memoryArea.setText(memoryService.readLongTerm(code));
        memoryArea.deselect();
        pathLabel.setText(I18n.t("memory.path", memoryService.workspaceOf(code).toString()));
        renderDaily(code);
    }

    private void renderDaily(String code) {
        dailyBox.getChildren().clear();
        List<String> files = memoryService.listDaily(code);
        dailyEmpty.setVisible(files.isEmpty());
        dailyEmpty.setManaged(files.isEmpty());
        for (String name : files) {
            dailyBox.getChildren().add(dailyRow(code, name));
        }
    }

    private Node dailyRow(String code, String name) {
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("memory-daily-name");
        nameLabel.setMinWidth(0);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);
        Button view = linkButton(I18n.t("memory.daily.view"), () -> openDaily(code, name));
        Button del = linkButton(I18n.t("memory.daily.delete"), () -> handleDeleteDaily(code, name));
        del.getStyleClass().add("link-btn-danger");
        HBox row = new HBox(8, nameLabel, view, del);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("memory-daily-row");
        return row;
    }

    // ---- 操作 ----

    private void handleSave() {
        AgentChoice choice = agentCombo.getValue();
        if (choice == null) {
            return;
        }
        try {
            memoryService.writeLongTerm(choice.code, memoryArea.getText());
            info(I18n.t("memory.save"), I18n.t("memory.save.ok"));
        } catch (RuntimeException e) {
            error(e.getMessage());
        }
    }

    private void handleClear() {
        AgentChoice choice = agentCombo.getValue();
        if (choice == null) {
            return;
        }
        Window owner = owner();
        if (!GimDialog.confirm(owner, I18n.t("memory.clear"), I18n.t("memory.clear.confirm"))) {
            return;
        }
        try {
            memoryService.clearLongTerm(choice.code);
            memoryArea.setText(memoryService.readLongTerm(choice.code));
        } catch (RuntimeException e) {
            error(e.getMessage());
        }
    }

    private void openDaily(String code, String name) {
        Window owner = owner();
        GimDialog.Shell shell = GimDialog.shell(owner, name);
        TextArea content = new TextArea(memoryService.readDaily(code, name));
        content.setEditable(false);
        content.setWrapText(true);
        content.setPrefWidth(600);
        content.setPrefHeight(420);
        content.setStyle("-fx-font-family: \"Cascadia Mono\", \"Consolas\", monospace; -fx-font-size: 12px;");
        Button close = new Button(I18n.t("common.close"));
        close.setOnAction(e -> shell.stage().close());
        HBox btns = new HBox(8, close);
        btns.setAlignment(Pos.CENTER_RIGHT);
        shell.body().getChildren().addAll(content, btns);
        shell.show();
    }

    private void handleDeleteDaily(String code, String name) {
        Window owner = owner();
        if (!GimDialog.confirm(owner, I18n.t("memory.daily.delete"), I18n.t("memory.daily.delete.confirm", name))) {
            return;
        }
        try {
            memoryService.deleteDaily(code, name);
            renderDaily(code);
        } catch (RuntimeException e) {
            error(e.getMessage());
        }
    }

    // ---- 小工具 ----

    private Window owner() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    private Button iconButton(String iconText, Runnable action) {
        Button b = new Button();
        b.getStyleClass().add("icon-btn");
        b.setTooltip(new Tooltip());
        Label icon = new Label(iconText);
        icon.getStyleClass().add("icon");
        b.setGraphic(icon);
        b.setOnAction(e -> action.run());
        return b;
    }

    private Button linkButton(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("link-btn");
        b.setOnAction(e -> action.run());
        return b;
    }

    private void info(String header, String msg) {
        GimDialog.message(owner(), header, null, msg);
    }

    private void error(String msg) {
        GimDialog.message(owner(), I18n.t("memory.title"), null,
                msg == null || msg.isBlank() ? I18n.t("common.error.unknown") : msg);
    }
}
