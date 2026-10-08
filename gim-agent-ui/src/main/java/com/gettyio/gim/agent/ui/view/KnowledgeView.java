package com.gettyio.gim.agent.ui.view;

import com.gettyio.gim.agent.core.knowledge.EmbeddingIndexService;
import com.gettyio.gim.agent.core.knowledge.KnowledgeService;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.config.SecurityAdminService;
import com.gettyio.gim.agent.ui.window.GimDialog;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 知识库整页（挂在「拓展」页「知识」Tab）：按智能体导入 / 列出 / 删除 {@code knowledge/} 下的资料，
 * 并预览自动维护的 {@code KNOWLEDGE.md} 目录索引。
 * <p>直连 {@link KnowledgeService}：导入即抽文本落盘到该智能体工作区，Harness 每轮注入的目录随之更新，
 * 智能体可按需 read/grep 命中。文案走 {@link I18n}，语言切换刷新。</p>
 */
@Component
public class KnowledgeView {

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private SecurityAdminService securityAdminService;

    private final VBox root = new VBox();
    private final ComboBox<AgentChoice> agentCombo = new ComboBox<>();
    private final VBox docBox = new VBox(6);
    private final Label docEmpty = new Label();
    private final TextArea indexArea = new TextArea();
    private Label titleLabel;
    private Label descLabel;
    private final Label docsHeader = new Label();
    private final Label indexHeader = new Label();
    private final Button importBtn = new Button();
    private Button refreshBtn;
    // 向量索引区：状态 + embedding 提供方配置 + 重建索引
    private final Label vectorHeader = new Label();
    private final Label vectorStatus = new Label();
    private final Label vectorHint = new Label();
    private final TextField baseUrlField = new TextField();
    private final TextField modelField = new TextField();
    private final PasswordField apiKeyField = new PasswordField();
    private Button reindexBtn;
    private HBox vectorHeadRow;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

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

    public KnowledgeView() {
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

        refreshBtn = iconButton("\uE72C", this::reload);
        refreshBtn.setMinWidth(Region.USE_PREF_SIZE);

        importBtn.getStyleClass().addAll("btn-primary", "knowledge-import-btn");
        importBtn.setMinWidth(Region.USE_PREF_SIZE);
        importBtn.setOnAction(e -> handleImport());

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
        HBox headRow = new HBox(10, descLabel, spacer, agentCombo, refreshBtn, importBtn);
        headRow.setAlignment(Pos.CENTER_LEFT);
        VBox headText = new VBox(4, titleLabel, headRow);
        headText.setMaxWidth(Double.MAX_VALUE);
        HBox header = new HBox(headText);
        HBox.setHgrow(headText, Priority.ALWAYS);
        header.getStyleClass().add("page-header");

        docsHeader.getStyleClass().add("section-title");
        docEmpty.getStyleClass().add("card-meta");
        docBox.getStyleClass().add("knowledge-doc-box");

        indexHeader.getStyleClass().add("section-title");
        indexArea.setEditable(false);
        indexArea.setWrapText(true);
        indexArea.setPrefHeight(220);
        indexArea.setStyle("-fx-font-family: \"Cascadia Mono\", \"Consolas\", monospace; -fx-font-size: 12px;");

        buildVectorSection();

        VBox content = new VBox(10, docsHeader, docEmpty, docBox,
                new Separator(), vectorHeadRow, vectorHint, configGrid(), vectorStatus,
                new Separator(), indexHeader, indexArea);
        content.setMaxWidth(Double.MAX_VALUE);

        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox body = new VBox(scroller);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        root.getChildren().addAll(header, body);
        applyI18n();
    }

    private void applyI18n() {
        if (titleLabel != null) {
            titleLabel.setText(I18n.t("knowledge.title"));
            descLabel.setText(I18n.t("knowledge.desc"));
            importBtn.setText(I18n.t("knowledge.import"));
            docsHeader.setText(I18n.t("knowledge.docs"));
            docEmpty.setText(I18n.t("knowledge.empty"));
            indexHeader.setText(I18n.t("knowledge.index"));
            refreshBtn.getTooltip().setText(I18n.t("common.refresh"));
            applyVectorI18n();
            loadSelected();
        }
    }

    // ===================== 向量索引区 =====================

    /** 向量索引区：标题行（重建按钮在右）+ embedding 提供方三字段（失焦/回车即存）+ 状态行。 */
    private void buildVectorSection() {
        vectorHeader.getStyleClass().add("section-title");
        vectorStatus.getStyleClass().add("card-meta");
        vectorStatus.setWrapText(true);
        vectorHint.getStyleClass().add("card-meta");
        vectorHint.setWrapText(true);

        reindexBtn = new Button();
        reindexBtn.getStyleClass().add("link-btn");
        reindexBtn.setMinWidth(Region.USE_PREF_SIZE);
        reindexBtn.setOnAction(e -> handleReindex());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        vectorHeadRow = new HBox(10, vectorHeader, spacer, reindexBtn);
        vectorHeadRow.setAlignment(Pos.CENTER_LEFT);

        saveOnBlur(baseUrlField, () -> securityAdminService.setKnowledgeEmbeddingBaseUrl(baseUrlField.getText()));
        saveOnBlur(modelField, () -> securityAdminService.setKnowledgeEmbeddingModel(modelField.getText()));
        saveOnBlur(apiKeyField, () -> securityAdminService.setKnowledgeEmbeddingKey(apiKeyField.getText()));
    }

    /** 三字段横向配置栅格。 */
    private HBox configGrid() {
        baseUrlField.setPromptText("https://dashscope.aliyuncs.com/compatible-mode/v1");
        modelField.setPromptText("text-embedding-v4");
        baseUrlField.getStyleClass().add("vector-field");
        modelField.getStyleClass().add("vector-field");
        apiKeyField.getStyleClass().add("vector-field");
        HBox.setHgrow(baseUrlField, Priority.ALWAYS);
        HBox.setHgrow(modelField, Priority.SOMETIMES);
        HBox.setHgrow(apiKeyField, Priority.SOMETIMES);
        HBox row = new HBox(8, baseUrlField, modelField, apiKeyField);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** 字段失焦/回车保存 + 刷状态行（改配置后无需重启即生效）。 */
    private void saveOnBlur(TextField field, Runnable save) {
        field.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                save.run();
                refreshVectorStatus();
            }
        });
        field.setOnAction(e -> {
            save.run();
            refreshVectorStatus();
        });
    }

    /** 选中智能体的索引状态行：未配置给引导，已配置给文档/块计数。 */
    private void refreshVectorStatus() {
        AgentChoice choice = agentCombo.getValue();
        // 构造期（buildUi→applyI18n）字段注入尚未发生，此时代理回落为未开启文案
        EmbeddingIndexService indexer = knowledgeService == null ? null : knowledgeService.embeddingIndexer();
        if (choice == null || indexer == null || !indexer.isConfigured()) {
            vectorStatus.setText(I18n.t("knowledge.vector.status.off"));
            return;
        }
        vectorStatus.setText(I18n.t("knowledge.vector.status.on",
                String.valueOf(indexer.docCount(choice.code)), String.valueOf(indexer.chunkCount(choice.code))));
    }

    /** 重建索引：逐文档读原文 → 分块嵌入整文档替换；未配置/失败给明确反馈。 */
    private void handleReindex() {
        AgentChoice choice = agentCombo.getValue();
        if (choice == null) {
            return;
        }
        EmbeddingIndexService indexer = knowledgeService.embeddingIndexer();
        if (indexer == null || !indexer.isConfigured()) {
            error(I18n.t("knowledge.vector.reindex.needConfig"));
            return;
        }
        List<KnowledgeService.DocInfo> docs = knowledgeService.listDocs(choice.code);
        if (docs.isEmpty()) {
            info(I18n.t("knowledge.vector.section"), I18n.t("knowledge.empty"));
            return;
        }
        int ok = 0;
        int chunks = 0;
        String lastError = null;
        for (KnowledgeService.DocInfo d : docs) {
            try {
                int n = indexer.indexDocument(choice.code, d.name(), knowledgeService.readDoc(choice.code, d.name()));
                if (n >= 0) {
                    ok++;
                    chunks += n;
                }
            } catch (RuntimeException e) {
                lastError = e.getMessage();
            }
        }
        refreshVectorStatus();
        if (ok == 0) {
            error(lastError);
        } else {
            info(I18n.t("knowledge.vector.section"),
                    I18n.t("knowledge.vector.reindex.ok", String.valueOf(ok), String.valueOf(chunks)));
        }
    }

    private void applyVectorI18n() {
        vectorHeader.setText(I18n.t("knowledge.vector.section"));
        vectorHint.setText(I18n.t("knowledge.vector.hint"));
        baseUrlField.setPromptText(I18n.t("knowledge.vector.baseUrl"));
        modelField.setPromptText(I18n.t("knowledge.vector.model"));
        apiKeyField.setPromptText(I18n.t("knowledge.vector.apiKey"));
        reindexBtn.setText(I18n.t("knowledge.vector.reindex"));
        refreshVectorStatus();
    }

    /** 刷新智能体下拉（保留当前选中），再加载选中项内容。 */
    public void reload() {
        String previous = agentCombo.getValue() == null ? null : agentCombo.getValue().code;
        agentCombo.getItems().clear();
        for (AgentDefinition a : knowledgeService.listAgents()) {
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

    private void loadSelected() {
        AgentChoice choice = agentCombo.getValue();
        if (choice == null) {
            docBox.getChildren().clear();
            indexArea.clear();
            return;
        }
        renderDocs(choice.code);
        indexArea.setText(knowledgeService.readIndex(choice.code));
        indexArea.deselect();
        refreshVectorStatus();
    }

    private void renderDocs(String code) {
        docBox.getChildren().clear();
        List<KnowledgeService.DocInfo> docs = knowledgeService.listDocs(code);
        docEmpty.setVisible(docs.isEmpty());
        docEmpty.setManaged(docs.isEmpty());
        for (KnowledgeService.DocInfo d : docs) {
            docBox.getChildren().add(docRow(code, d));
        }
    }

    private Node docRow(String code, KnowledgeService.DocInfo d) {
        Label name = new Label(d.name());
        name.getStyleClass().add("knowledge-doc-name");
        name.setMinWidth(0);
        name.setMaxWidth(Double.MAX_VALUE);
        name.setEllipsisString("\u2026");
        VBox meta = new VBox(2, name, new Label(summaryLine(d)));
        meta.getChildren().get(1).getStyleClass().add("card-meta");
        HBox.setHgrow(meta, Priority.ALWAYS);

        Button view = linkButton(I18n.t("knowledge.view"), () -> openDoc(code, d.name()));
        Button del = linkButton(I18n.t("knowledge.delete"), () -> handleDelete(code, d.name()));
        del.getStyleClass().add("link-btn-danger");
        HBox row = new HBox(8, meta, view, del);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("knowledge-doc-row");
        return row;
    }

    private String summaryLine(KnowledgeService.DocInfo d) {
        String summary = d.summary() == null || d.summary().isBlank() ? I18n.t("knowledge.noSummary") : d.summary();
        String when = d.lastModifiedMs() > 0 ? DATE_FMT.format(Instant.ofEpochMilli(d.lastModifiedMs())) : "";
        return summary + "  ·  " + humanSize(d.sizeBytes()) + (when.isBlank() ? "" : "  ·  " + when);
    }

    // ---- 操作 ----

    private void handleImport() {
        AgentChoice choice = agentCombo.getValue();
        if (choice == null) {
            return;
        }
        Window owner = owner();
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("knowledge.import.title"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.t("knowledge.import.filter"), "*.pdf", "*.docx", "*.xlsx", "*.txt", "*.md", "*.markdown", "*.csv"));
        List<File> files = chooser.showOpenMultipleDialog(owner);
        if (files == null || files.isEmpty()) {
            return;
        }
        int ok = 0;
        String lastError = null;
        for (File f : files) {
            try {
                knowledgeService.importFromPath(choice.code, f.toPath(), null);
                ok++;
            } catch (RuntimeException e) {
                lastError = e.getMessage();
            }
        }
        renderDocs(choice.code);
        indexArea.setText(knowledgeService.readIndex(choice.code));
        if (ok == 0) {
            error(lastError);
        } else {
            info(I18n.t("knowledge.import"), I18n.t("knowledge.import.ok", ok));
        }
    }

    private void openDoc(String code, String name) {
        Window owner = owner();
        GimDialog.Shell shell = GimDialog.shell(owner, name);
        TextArea content = new TextArea(knowledgeService.readDoc(code, name));
        content.setEditable(false);
        content.setWrapText(true);
        content.setPrefWidth(640);
        content.setPrefHeight(460);
        content.setStyle("-fx-font-family: \"Cascadia Mono\", \"Consolas\", monospace; -fx-font-size: 12px;");
        Button close = new Button(I18n.t("common.close"));
        close.setOnAction(e -> shell.stage().close());
        HBox btns = new HBox(8, close);
        btns.setAlignment(Pos.CENTER_RIGHT);
        shell.body().getChildren().addAll(content, btns);
        shell.show();
    }

    private void handleDelete(String code, String name) {
        if (!GimDialog.confirm(owner(), I18n.t("knowledge.delete"), I18n.t("knowledge.delete.confirm", name))) {
            return;
        }
        try {
            knowledgeService.deleteDoc(code, name);
            renderDocs(code);
            indexArea.setText(knowledgeService.readIndex(code));
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

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        return String.format(Locale.ROOT, "%.1f MB", kb / 1024.0);
    }

    private void info(String header, String msg) {
        GimDialog.message(owner(), header, null, msg);
    }

    private void error(String msg) {
        GimDialog.message(owner(), I18n.t("knowledge.title"), null,
                msg == null || msg.isBlank() ? I18n.t("common.error.unknown") : msg);
    }
}
