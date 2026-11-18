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

import com.gettyio.jwork.agent.core.chat.MultiAgentGateway;
import com.gettyio.jwork.agent.core.knowledge.EmbeddingIndexService;
import com.gettyio.jwork.agent.core.knowledge.KnowledgeService;
import com.gettyio.jwork.agent.domain.entity.AgentDefinition;
import com.gettyio.jwork.agent.domain.util.TimeFormats;
import com.gettyio.jwork.agent.ui.i18n.I18n;
import com.gettyio.jwork.agent.ui.util.UiFormat;
import com.gettyio.jwork.agent.ui.config.SecurityAdminService;
import com.gettyio.jwork.agent.ui.control.SecretField;
import com.gettyio.jwork.agent.ui.util.UiStyles;
import com.gettyio.jwork.agent.ui.window.dialog.JworkDialog;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
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

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 知识库整页（挂在「拓展」页「知识」Tab）：按智能体导入 / 列出 / 删除 {@code knowledge/} 下的资料，
 * 并预览自动维护的 {@code KNOWLEDGE.md} 目录索引。
 * <p>直连 {@link KnowledgeService}：导入即抽文本落盘到该智能体工作区，Harness 每轮注入的目录随之更新，
 * 智能体可按需 read/grep 命中。文案走 {@link I18n}，语言切换刷新。</p>
 *
 * @author gogym
 */
public class KnowledgeView {

    private final KnowledgeService knowledgeService;
    private final SecurityAdminService securityAdminService;

    private final VBox root = new VBox();
    private final ComboBox<AgentChoice> agentCombo = new ComboBox<>();
    private final Label agentCaption = new Label();
    private final VBox docBox = new VBox(6);
    private final VBox docEmpty = new VBox(6);
    private final Label docEmptyTitle = new Label();
    private final Label docEmptyHint = new Label();
    private final TextArea indexArea = new TextArea();
    private final Label indexHint = new Label();
    private Label titleLabel;
    private Label descLabel;
    private final Label docsHeader = new Label();
    private final Label indexHeader = new Label();
    private final Button importBtn = new Button();
    private final Button importUrlBtn = new Button();
    private Button refreshBtn;
    // 向量索引区：状态 + embedding 提供方配置 + 重建索引
    private final Label vectorHeader = new Label();
    private final Label vectorStatus = new Label();
    private final Label vectorHint = new Label();
    private final TextField baseUrlField = new TextField();
    private final TextField modelField = new TextField();
    /** embedding API Key 输入行：已存密钥不回显，留空即不修改，撤销走「清除」。 */
    private final SecretField apiKey;
    private Button reindexBtn;
    private HBox vectorHeadRow;
    /** 侧栏当前智能体供给（主控制器注入）：进入知识页时默认跟随它 */
    private Supplier<String> currentAgent = () -> null;

    private static final DateTimeFormatter DATE_FMT =
            TimeFormats.MINUTE_TIMESTAMP.withZone(ZoneId.systemDefault());

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

    public KnowledgeView(KnowledgeService knowledgeService, SecurityAdminService securityAdminService) {
        this.knowledgeService = knowledgeService;
        this.securityAdminService = securityAdminService;
        this.apiKey = new SecretField(securityAdminService::getKnowledgeEmbeddingKeyState,
                securityAdminService::setKnowledgeEmbeddingKey,
                securityAdminService::clearKnowledgeEmbeddingKey);
        buildUi();
        I18n.onChange(lang -> {
            applyI18n();
            reload();
        });
    }

    public VBox getNode() {
        return root;
    }

    /** 注入侧栏当前智能体编码供给；进入知识页（activate）时默认选中它。 */
    public void bindCurrentAgent(Supplier<String> supplier) {
        this.currentAgent = supplier == null ? () -> null : supplier;
    }

    /** 进入知识页：跟随侧栏当前智能体（定义已删时回落默认智能体、再回落首项），再加载内容。 */
    public void activate() {
        reloadWith(currentAgent.get());
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

        importUrlBtn.getStyleClass().add(UiStyles.LINK_BTN);
        importUrlBtn.setMinWidth(Region.USE_PREF_SIZE);
        importUrlBtn.setOnAction(e -> handleImportUrl());

        importBtn.getStyleClass().addAll(UiStyles.BTN_PRIMARY, "knowledge-import-btn");
        importBtn.setMinWidth(Region.USE_PREF_SIZE);
        importBtn.setOnAction(e -> handleImport());

        agentCombo.setMinWidth(180);
        agentCombo.setMaxWidth(280);
        agentCaption.getStyleClass().add("knowledge-agent-caption");
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
        HBox headRow = new HBox(10, descLabel, spacer, agentCaption, agentCombo, refreshBtn, importUrlBtn, importBtn);
        headRow.setAlignment(Pos.CENTER_LEFT);
        VBox headText = new VBox(4, titleLabel, headRow);
        headText.setMaxWidth(Double.MAX_VALUE);
        HBox header = new HBox(headText);
        HBox.setHgrow(headText, Priority.ALWAYS);
        header.getStyleClass().add(UiStyles.PAGE_HEADER);

        docsHeader.getStyleClass().add("section-title");
        // 空态引导卡：说清支持格式与「导入即生效」，而不是一句暂无数据
        Label emptyIcon = new Label("\uE82D");
        emptyIcon.getStyleClass().add("knowledge-empty-icon");
        docEmptyTitle.getStyleClass().add("knowledge-empty-title");
        docEmptyHint.getStyleClass().add(UiStyles.CARD_META);
        docEmptyHint.setWrapText(true);
        HBox emptyHead = new HBox(8, emptyIcon, docEmptyTitle);
        emptyHead.setAlignment(Pos.CENTER_LEFT);
        docEmpty.getStyleClass().add("knowledge-empty-card");
        docEmpty.getChildren().addAll(emptyHead, docEmptyHint);
        docBox.getStyleClass().add("knowledge-doc-box");

        indexHeader.getStyleClass().add("section-title");
        indexHint.getStyleClass().add(UiStyles.CARD_META);
        indexHint.setWrapText(true);
        indexArea.setEditable(false);
        indexArea.setWrapText(true);
        indexArea.setStyle("-fx-font-family: \"Cascadia Mono\", \"Consolas\", monospace; -fx-font-size: 12px;");

        buildVectorSection();

        // 引导顺序：① 知识文件 → ② 自动目录索引 → ③ 可选语义检索；
        // 可选增强放最后，避免未配置时夹在中间像必配项
        VBox content = new VBox(10, docsHeader, docEmpty, docBox,
                new Separator(), indexHeader, indexHint, indexArea,
                new Separator(), vectorHeadRow, vectorHint, configGrid(), vectorStatus);
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
            agentCaption.setText(I18n.t("knowledge.agent"));
            importBtn.setText(I18n.t("knowledge.import"));
            importUrlBtn.setText(I18n.t("knowledge.importUrl"));
            docsHeader.setText(I18n.t("knowledge.docs"));
            docEmptyTitle.setText(I18n.t("knowledge.empty.title"));
            docEmptyHint.setText(I18n.t("knowledge.empty.hint"));
            indexHeader.setText(I18n.t("knowledge.index"));
            indexHint.setText(I18n.t("knowledge.index.hint"));
            refreshBtn.getTooltip().setText(I18n.t("common.refresh"));
            applyVectorI18n();
            loadSelected();
        }
    }

    // ===================== 向量索引区 =====================

    /** 向量索引区：标题行（重建按钮在右）+ embedding 提供方三字段（失焦/回车即存）+ 状态行。 */
    private void buildVectorSection() {
        vectorHeader.getStyleClass().add("section-title");
        vectorStatus.getStyleClass().add(UiStyles.CARD_META);
        vectorStatus.setWrapText(true);
        vectorHint.getStyleClass().add(UiStyles.CARD_META);
        vectorHint.setWrapText(true);

        reindexBtn = new Button();
        reindexBtn.getStyleClass().add(UiStyles.LINK_BTN);
        reindexBtn.setMinWidth(Region.USE_PREF_SIZE);
        reindexBtn.setOnAction(e -> handleReindex());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        vectorHeadRow = new HBox(10, vectorHeader, spacer, reindexBtn);
        vectorHeadRow.setAlignment(Pos.CENTER_LEFT);

        saveOnBlur(baseUrlField, () -> securityAdminService.setKnowledgeEmbeddingBaseUrl(baseUrlField.getText()));
        saveOnBlur(modelField, () -> securityAdminService.setKnowledgeEmbeddingModel(modelField.getText()));
        // Key 由 SecretField 自己接管失焦/回车提交（留空不写库），提交后补刷状态行
        apiKey.addAfterCommit(this::refreshVectorStatus);
    }

    /** 三字段横向配置栅格（Key 那格带状态与「清除」）。 */
    private HBox configGrid() {
        baseUrlField.setPromptText("https://dashscope.aliyuncs.com/compatible-mode/v1");
        modelField.setPromptText("text-embedding-v4");
        baseUrlField.getStyleClass().add("vector-field");
        modelField.getStyleClass().add("vector-field");
        apiKey.field().getStyleClass().add("vector-field");
        HBox.setHgrow(baseUrlField, Priority.ALWAYS);
        HBox.setHgrow(modelField, Priority.SOMETIMES);
        HBox.setHgrow(apiKey.control(), Priority.SOMETIMES);
        HBox row = new HBox(8, baseUrlField, modelField, apiKey.control());
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

    /** 重建索引（增量）：未变更文档按内容+嵌入配置指纹跳过，变更文档重嵌入整文档替换；未配置/失败给明确反馈。 */
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
        int skipped = 0;
        String lastError = null;
        for (KnowledgeService.DocInfo d : docs) {
            try {
                String text = knowledgeService.readDoc(choice.code, d.name());
                if (indexer.isUpToDate(choice.code, d.name(), text)) {
                    skipped++;
                    continue;
                }
                int n = indexer.indexDocument(choice.code, d.name(), text);
                if (n >= 0) {
                    ok++;
                    chunks += n;
                }
            } catch (RuntimeException e) {
                lastError = e.getMessage();
            }
        }
        refreshVectorStatus();
        if (ok == 0 && skipped == 0) {
            error(lastError);
        } else {
            info(I18n.t("knowledge.vector.section"),
                    I18n.t("knowledge.vector.reindex.ok",
                            String.valueOf(ok), String.valueOf(chunks), String.valueOf(skipped)));
        }
    }

    private void applyVectorI18n() {
        vectorHeader.setText(I18n.t("knowledge.vector.section"));
        vectorHint.setText(I18n.t("knowledge.vector.hint"));
        baseUrlField.setPromptText(I18n.t("knowledge.vector.baseUrl"));
        modelField.setPromptText(I18n.t("knowledge.vector.model"));
        apiKey.field().setPromptText(I18n.t("settings.secret.prompt"));
        apiKey.applyI18n();
        reindexBtn.setText(I18n.t("knowledge.vector.reindex"));
        refreshVectorStatus();
    }

    /** 刷新智能体下拉（保留当前选中），再加载选中项内容。 */
    public void reload() {
        reloadWith(null);
    }

    /** preferred 非空时优先选它（进入页跟随当前智能体）；否则保留旧选中；都落空才取首项。 */
    private void reloadWith(String preferred) {
        String previous = preferred != null ? preferred
                : (agentCombo.getValue() == null ? null : agentCombo.getValue().code);
        agentCombo.getItems().clear();
        for (AgentDefinition a : knowledgeService.listAgents()) {
            String name = a.getAgentName() == null || a.getAgentName().isBlank()
                    ? a.getAgentCode() : a.getAgentName();
            agentCombo.getItems().add(new AgentChoice(a.getAgentCode(), name));
        }
        AgentChoice target = find(previous);
        if (target == null && preferred != null) {
            target = find(MultiAgentGateway.DEFAULT_AGENT_CODE);
        }
        if (target == null && !agentCombo.getItems().isEmpty()) {
            target = agentCombo.getItems().get(0);
        }
        agentCombo.setValue(target);
        loadSelected();
    }

    private AgentChoice find(String code) {
        if (code == null) {
            return null;
        }
        for (AgentChoice c : agentCombo.getItems()) {
            if (Objects.equals(c.code, code)) {
                return c;
            }
        }
        return null;
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
        fitIndexHeight();
        refreshVectorStatus();
    }

    /** 索引预览高度随内容收放：空骨架只占小条，长索引封顶 12 行内滚，不再固定半屏空白。 */
    private void fitIndexHeight() {
        String text = indexArea.getText() == null ? "" : indexArea.getText();
        int lines = text.split("\n", -1).length;
        indexArea.setPrefRowCount(Math.max(4, Math.min(12, lines)));
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
        meta.getChildren().get(1).getStyleClass().add(UiStyles.CARD_META);
        HBox.setHgrow(meta, Priority.ALWAYS);

        Button view = linkButton(I18n.t("knowledge.view"), () -> openDoc(code, d.name()));
        Button del = linkButton(I18n.t("knowledge.delete"), () -> handleDelete(code, d.name()));
        del.getStyleClass().add(UiStyles.LINK_BTN_DANGER);
        HBox row = new HBox(8, meta, view, del);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("knowledge-doc-row");
        return row;
    }

    private String summaryLine(KnowledgeService.DocInfo d) {
        String summary = d.summary() == null || d.summary().isBlank() ? I18n.t("knowledge.noSummary") : d.summary();
        String when = d.lastModifiedMs() > 0 ? DATE_FMT.format(Instant.ofEpochMilli(d.lastModifiedMs())) : "";
        return summary + "  ·  " + UiFormat.humanSize(d.sizeBytes()) + (when.isBlank() ? "" : "  ·  " + when);
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
        fitIndexHeight();
        if (ok == 0) {
            error(lastError);
        } else {
            info(I18n.t("knowledge.import"), I18n.t("knowledge.import.ok", ok));
        }
    }

    /** 从 URL 导入网页：对话框收集地址与可选摘要 → 后台抓取入库 → 回 UI 线程刷新。 */
    private void handleImportUrl() {
        AgentChoice choice = agentCombo.getValue();
        if (choice == null) {
            return;
        }
        JworkDialog.Shell shell = JworkDialog.shell(owner(), I18n.t("knowledge.importUrl.title"));
        TextField urlField = new TextField();
        urlField.setPromptText(I18n.t("knowledge.importUrl.url"));
        urlField.setPrefWidth(460);
        TextField summaryField = new TextField();
        summaryField.setPromptText(I18n.t("knowledge.importUrl.summary"));
        summaryField.setPrefWidth(460);
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        Button confirm = new Button(I18n.t("knowledge.importUrl.confirm"));
        confirm.getStyleClass().add(UiStyles.BTN_PRIMARY);
        confirm.setOnAction(e -> {
            String url = urlField.getText() == null ? "" : urlField.getText().trim();
            if (url.isEmpty()) {
                return;
            }
            String summary = summaryField.getText() == null ? "" : summaryField.getText().trim();
            shell.stage().close();
            startUrlImport(choice.code, url, summary);
        });
        HBox btns = new HBox(8, cancel, confirm);
        btns.setAlignment(Pos.CENTER_RIGHT);
        shell.body().getChildren().addAll(urlField, summaryField, btns);
        shell.show();
        urlField.requestFocus();
    }

    /** 后台线程执行 URL 抓取与入库（网络耗时避免卡 UI）；完成后回 UI 线程提示并刷新。 */
    private void startUrlImport(String agentCode, String url, String summary) {
        importUrlBtn.setDisable(true);
        Thread worker = new Thread(() -> {
            String name = null;
            String failure = null;
            try {
                name = knowledgeService.importFromUrl(agentCode, url, summary);
            } catch (RuntimeException e) {
                failure = e.getMessage();
            }
            String doneName = name;
            String err = failure;
            Platform.runLater(() -> {
                importUrlBtn.setDisable(false);
                if (doneName == null) {
                    error(err);
                    return;
                }
                renderDocs(agentCode);
                indexArea.setText(knowledgeService.readIndex(agentCode));
                fitIndexHeight();
                refreshVectorStatus();
                info(I18n.t("knowledge.importUrl.title"), I18n.t("knowledge.importUrl.ok", doneName));
            });
        }, "knowledge-url-import");
        worker.setDaemon(true);
        worker.start();
    }

    private void openDoc(String code, String name) {
        Window owner = owner();
        JworkDialog.Shell shell = JworkDialog.shell(owner, name);
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
        if (!JworkDialog.confirm(owner(), I18n.t("knowledge.delete"), I18n.t("knowledge.delete.confirm", name))) {
            return;
        }
        try {
            knowledgeService.deleteDoc(code, name);
            renderDocs(code);
            indexArea.setText(knowledgeService.readIndex(code));
            fitIndexHeight();
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
        b.getStyleClass().add(UiStyles.ICON_BTN);
        b.setTooltip(new Tooltip());
        Label icon = new Label(iconText);
        icon.getStyleClass().add("icon");
        b.setGraphic(icon);
        b.setOnAction(e -> action.run());
        return b;
    }

    private Button linkButton(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add(UiStyles.LINK_BTN);
        b.setOnAction(e -> action.run());
        return b;
    }

    private void info(String header, String msg) {
        JworkDialog.message(owner(), header, null, msg);
    }

    private void error(String msg) {
        JworkDialog.message(owner(), I18n.t("knowledge.title"), null,
                msg == null || msg.isBlank() ? I18n.t("common.error.unknown") : msg);
    }
}
