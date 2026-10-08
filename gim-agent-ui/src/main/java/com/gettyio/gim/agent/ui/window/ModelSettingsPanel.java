package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.core.model.ModelProbe;
import com.gettyio.gim.agent.domain.entity.ModelConfig;
import com.gettyio.gim.agent.domain.entity.ModelProviderConfig;
import com.gettyio.gim.agent.domain.enums.ApiProtocol;
import com.gettyio.gim.agent.domain.enums.AuthMethod;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.config.ProviderAdminService;
import com.gettyio.gim.agent.ui.control.ToggleSwitch;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 模型配置子面板：提供商、模型、默认模型三页合抽一处（它们共享 {@link ProviderAdminService}、
 * 表单字段与连接探测态，彼此高度互联，故合并为单一自治协作类，而非三个互依赖的碎片）。
 * <p>抽自 {@link SettingsWindow}：卡片渲染复用 {@link SettingsLayout}，弹窗归属/即时提示/整表刷新
 * 回调宿主；提供商↔模型联动（保存提供商后刷新模型表单的下拉、设为默认后刷新默认标签）在本类内部闭环。
 * 三页共用同一实例，宿主语言切换重建时复用，容器内容随之重绘。</p>
 */
final class ModelSettingsPanel {

    private final SettingsWindow host;
    private final ProviderAdminService adminService;

    private final FlowPane providerCardsBox = SettingsLayout.cardsBox();
    private final FlowPane modelCardsBox = SettingsLayout.cardsBox();

    // ---- 提供商表单字段 ----
    private final TextField providerCodeField = new TextField();
    private final TextField providerNameField = new TextField();
    private final ComboBox<String> protocolCombo = new ComboBox<>();
    private final ComboBox<String> authCombo = new ComboBox<>();
    private final TextField baseUrlField = new TextField();
    private final PasswordField apiKeyField = new PasswordField();
    private final ToggleSwitch providerEnabledCheck = new ToggleSwitch();
    private final TextField remarkField = new TextField();

    // ---- 模型表单字段 ----
    private final ComboBox<String> modelProviderCombo = new ComboBox<>();
    private final TextField modelCodeField = new TextField();
    private final TextField modelNameField = new TextField();
    private final TextField priorityField = new TextField();
    private final ToggleSwitch modelEnabledCheck = new ToggleSwitch();

    private final Label defaultModelInfoLabel = new Label("-");

    /** 模型表单本次弹窗里的连接测试结果；为空表示没测过。 */
    private ModelProbe.Result modelProbeResult;
    /** 正在编辑的模型原记录：未重新测试时沿用它已有的可用状态，不被抹成 UNKNOWN。 */
    private ModelConfig modelBaseline;
    /** 本次弹窗里的多模态测试结果；为空表示没测过，不影响可用性状态。 */
    private ModelProbe.Result modelVisionResult;

    ModelSettingsPanel(SettingsWindow host, ProviderAdminService adminService) {
        this.host = host;
        this.adminService = adminService;
    }

    /**
     * 探测本机 Ollama / LM Studio 并一键导入：后台线程探测（本地回环 1s 超时），
     * 未发现时提示引导；发现后弹确认框列出模型清单，确认即落库（幂等，已存在跳过）。
     */
    private void detectLocalService(Button detectBtn) {
        detectBtn.setDisable(true);
        Thread worker = new Thread(() -> {
            var detected = new com.gettyio.gim.agent.core.model.LocalModelDetector().detect();
            Platform.runLater(() -> {
                detectBtn.setDisable(false);
                if (detectBtn.getScene() == null) {
                    return;
                }
                if (detected.isEmpty()) {
                    host.notify("settings.menu.providers", I18n.t("settings.provider.detectNone"));
                    return;
                }
                confirmLocalImport(detected.get());
            });
        }, "local-model-detect");
        worker.setDaemon(true);
        worker.start();
    }

    /** 弹确认框展示探测到的本地服务与模型清单，确认后导入。 */
    private void confirmLocalImport(com.gettyio.gim.agent.core.model.LocalModelDetector.LocalService svc) {
        String listing = String.join("\n", svc.models());
        if (!GimDialog.confirm(host.hostWindow(), I18n.t("settings.provider.detectLocal"),
                I18n.t("settings.provider.detectFound", svc.baseUrl()),
                listing)) {
            return;
        }
        var result = adminService.importLocalModels(svc.kind(), svc.baseUrl(), svc.models());
        host.reloadAll();
        host.notify("settings.menu.providers", I18n.t("settings.provider.detectImported",
                result.imported(), result.skipped()));
    }

    /** 初始化协议/鉴权下拉项：宿主整窗构建时调用一次。 */
    void initCombos() {
        protocolCombo.setItems(FXCollections.observableArrayList(
                List.of(ApiProtocol.DASHSCOPE.name(), ApiProtocol.OPENAI_COMPATIBLE.name(),
                        ApiProtocol.ANTHROPIC_COMPATIBLE.name(), ApiProtocol.OLLAMA.name())));
        authCombo.setItems(FXCollections.observableArrayList(
                List.of(AuthMethod.X_API_KEY.name(), AuthMethod.BEARER_TOKEN.name())));
    }

    /** 依据库中数据重绘提供商/模型卡片、刷新模型下拉与默认模型标签：宿主整表刷新时调用。 */
    void reload() {
        refreshProviderCombos();
        renderProviderCards();
        renderModelCards();
        providerCodeField.setDisable(false);
        modelCodeField.setDisable(false);
        refreshDefaultModelInfo();
    }

    // ---- 提供商页 ----

    VBox buildProviderPage() {
        VBox box = new VBox();
        box.getStyleClass().add("settings-page");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMaxHeight(Double.MAX_VALUE);

        VBox headText = new VBox(4);
        Label title = new Label(I18n.t("settings.menu.providers"));
        title.getStyleClass().add("page-title");
        Label subtitle = new Label(I18n.t("settings.providers.subtitle"));
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.setWrapText(true);
        headText.getChildren().addAll(title, subtitle);

        Button addBtn = new Button(I18n.t("settings.provider.add"));
        addBtn.getStyleClass().add("btn-primary");
        Label addIcon = new Label("\uE710");
        addIcon.getStyleClass().add("icon");
        addBtn.setGraphic(addIcon);
        addBtn.setOnAction(e -> openProviderDialog(null));

        Button detectBtn = new Button(I18n.t("settings.provider.detectLocal"));
        Label detectIcon = new Label("\uE9F5");
        detectIcon.getStyleClass().add("icon");
        detectBtn.setGraphic(detectIcon);
        detectBtn.setOnAction(e -> detectLocalService(detectBtn));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(headText, spacer, detectBtn, addBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        providerCardsBox.getStyleClass().add("provider-cards");
        VBox.setVgrow(providerCardsBox, Priority.ALWAYS);
        box.getChildren().addAll(header, providerCardsBox);
        return box;
    }

    /** 依据库中数据重绘提供商信息卡片。 */
    private void renderProviderCards() {
        providerCardsBox.getChildren().clear();
        List<ModelProviderConfig> providers = adminService.listProviders();
        if (providers.isEmpty()) {
            providerCardsBox.setAlignment(Pos.CENTER);
            providerCardsBox.getChildren().add(providerEmptyState());
            return;
        }
        providerCardsBox.setAlignment(Pos.TOP_LEFT);
        for (ModelProviderConfig p : providers) {
            providerCardsBox.getChildren().add(providerCard(p));
        }
    }

    /** 空状态：大图标 + 标题 + 描述（对齐技能模块 emptyState 样式）。 */
    private VBox providerEmptyState() {
        VBox box = new VBox();
        box.getStyleClass().add("empty-state");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFillWidth(false);
        Label icon = new Label("\uE753");
        icon.getStyleClass().add("empty-icon");
        Label t = new Label(I18n.t("settings.provider.empty"));
        t.getStyleClass().add("empty-title");
        t.setTextAlignment(TextAlignment.CENTER);
        Label d = new Label(I18n.t("settings.provider.emptyDesc"));
        d.getStyleClass().add("empty-desc");
        d.setWrapText(true);
        d.setTextAlignment(TextAlignment.CENTER);
        d.setMaxWidth(460);
        box.getChildren().addAll(icon, t, d);
        return box;
    }

    private VBox providerCard(ModelProviderConfig p) {
        boolean enabled = EnabledStatus.isEnabled(p.getEnabled());

        Label name = new Label(p.getProviderName() == null || p.getProviderName().isBlank()
                ? p.getProviderCode() : p.getProviderName());
        name.getStyleClass().add("provider-card-title");
        Label badge = new Label(I18n.t(enabled ? "settings.provider.enabled" : "settings.provider.disabled"));
        badge.getStyleClass().addAll("badge", enabled ? "badge-green" : "badge-gray");

        Button edit = SettingsLayout.iconTextButton("\uE70F", I18n.t("common.edit"), () -> openProviderDialog(p));
        edit.getStyleClass().add("link-btn");
        Button del = SettingsLayout.iconTextButton("\uE74D", I18n.t("common.delete"), () -> handleDeleteProvider(p));
        del.getStyleClass().addAll("link-btn", "link-btn-danger");
        HBox head = SettingsLayout.cardHeadRow(name, badge, edit, del);

        VBox card = new VBox(8, head,
                SettingsLayout.kvLine(I18n.t("settings.provider.code"), p.getProviderCode()),
                SettingsLayout.kvLine(I18n.t("settings.provider.protocol"), p.getApiProtocol()),
                SettingsLayout.kvLine(I18n.t("settings.provider.auth"), p.getAuthMethod()),
                SettingsLayout.kvLine(I18n.t("settings.provider.baseUrl"), p.getBaseUrl()),
                SettingsLayout.kvLine(I18n.t("settings.provider.remark"), p.getRemark()));
        card.getStyleClass().add("provider-card");
        card.setPrefWidth(SettingsLayout.CARD_WIDTH);
        card.setMaxWidth(SettingsLayout.CARD_WIDTH);
        return card;
    }

    /** 弹出无边框模态窗口新增/编辑提供商；{@code editing} 为 null 表示新增。 */
    private void openProviderDialog(ModelProviderConfig editing) {
        GimDialog.Shell shell = GimDialog.shell(host.hostWindow(), I18n.t(editing == null
                ? "settings.provider.addTitle" : "settings.provider.editTitle"));

        if (editing == null) {
            clearProviderForm();
        } else {
            loadProviderToForm(editing);
        }

        GridPane form = buildProviderForm();
        form.setPrefWidth(440);

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        Button save = new Button(I18n.t("common.save"));
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            if (commitProviderForm()) {
                shell.stage().close();
            }
        });
        HBox btns = new HBox(8, cancel, save);
        btns.setAlignment(Pos.CENTER_RIGHT);

        shell.body().getChildren().addAll(form, btns);
        shell.showAndWait();
    }

    // ---- 模型页 ----

    VBox buildModelPage() {
        VBox box = new VBox();
        box.getStyleClass().add("settings-page");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMaxHeight(Double.MAX_VALUE);

        VBox headText = new VBox(4);
        Label title = new Label(I18n.t("settings.menu.models"));
        title.getStyleClass().add("page-title");
        Label subtitle = new Label(I18n.t("settings.models.subtitle"));
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.setWrapText(true);
        headText.getChildren().addAll(title, subtitle);

        Button addBtn = new Button(I18n.t("settings.model.add"));
        addBtn.getStyleClass().add("btn-primary");
        Label addIcon = new Label("\uE710");
        addIcon.getStyleClass().add("icon");
        addBtn.setGraphic(addIcon);
        addBtn.setOnAction(e -> openModelDialog(null));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(headText, spacer, addBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        modelCardsBox.getStyleClass().add("provider-cards");
        VBox.setVgrow(modelCardsBox, Priority.ALWAYS);
        box.getChildren().addAll(header, modelCardsBox);
        return box;
    }

    /** 依据库中数据重绘模型信息卡片（列出全部模型）。 */
    private void renderModelCards() {
        modelCardsBox.getChildren().clear();
        List<ModelConfig> models = adminService.listModels();
        if (models.isEmpty()) {
            modelCardsBox.setAlignment(Pos.CENTER);
            modelCardsBox.getChildren().add(modelEmptyState());
            return;
        }
        modelCardsBox.setAlignment(Pos.TOP_LEFT);
        for (ModelConfig m : models) {
            modelCardsBox.getChildren().add(modelCard(m));
        }
    }

    private VBox modelEmptyState() {
        VBox box = new VBox();
        box.getStyleClass().add("empty-state");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFillWidth(false);
        Label icon = new Label("\uE7B8");
        icon.getStyleClass().add("empty-icon");
        Label t = new Label(I18n.t("settings.model.empty"));
        t.getStyleClass().add("empty-title");
        t.setTextAlignment(TextAlignment.CENTER);
        Label d = new Label(I18n.t("settings.model.emptyDesc"));
        d.getStyleClass().add("empty-desc");
        d.setWrapText(true);
        d.setTextAlignment(TextAlignment.CENTER);
        d.setMaxWidth(460);
        box.getChildren().addAll(icon, t, d);
        return box;
    }

    private VBox modelCard(ModelConfig m) {
        boolean enabled = EnabledStatus.isEnabled(m.getEnabled());
        Label name = new Label(m.getModelName() == null || m.getModelName().isBlank()
                ? m.getModelCode() : m.getModelName());
        name.getStyleClass().add("provider-card-title");
        Label badge = new Label(I18n.t(enabled ? "settings.model.enabled" : "settings.model.disabled"));
        badge.getStyleClass().addAll("badge", enabled ? "badge-green" : "badge-gray");

        Button edit = SettingsLayout.iconTextButton("\uE70F", I18n.t("common.edit"), () -> openModelDialog(m));
        edit.getStyleClass().add("link-btn");
        Button del = SettingsLayout.iconTextButton("\uE74D", I18n.t("common.delete"), () -> handleDeleteModel(m));
        del.getStyleClass().addAll("link-btn", "link-btn-danger");
        HBox head = SettingsLayout.cardHeadRow(name, badge, edit, del);

        VBox card = new VBox(8, head,
                SettingsLayout.kvLine(I18n.t("settings.model.code"), m.getModelCode()),
                SettingsLayout.kvLine(I18n.t("settings.model.provider"), m.getProviderCode()),
                SettingsLayout.kvLine(I18n.t("settings.model.priority"), String.valueOf(m.getPriority())),
                SettingsLayout.kvIconLine(I18n.t("common.status"), SettingsLayout.statusIconValue(m.getStatus())),
                SettingsLayout.kvIconLine(I18n.t("settings.model.multimodal"), SettingsLayout.visionIconValue(m.getSupportsVision())),
                SettingsLayout.kvLine(I18n.t("settings.model.lastTest"), lastTestText(m)));
        card.getStyleClass().add("provider-card");
        card.setPrefWidth(SettingsLayout.CARD_WIDTH);
        card.setMaxWidth(SettingsLayout.CARD_WIDTH);
        return card;
    }

    /** 弹出无边框模态窗口新增/编辑模型；{@code editing} 为 null 表示新增。 */
    private void openModelDialog(ModelConfig editing) {
        GimDialog.Shell shell = GimDialog.shell(host.hostWindow(), I18n.t(editing == null
                ? "settings.model.addTitle" : "settings.model.editTitle"));

        if (editing == null) {
            clearModelForm();
        } else {
            loadModelToForm(editing);
        }

        GridPane form = buildModelForm();
        form.setPrefWidth(440);

        Label probeHint = new Label("");
        probeHint.getStyleClass().add("probe-hint");
        probeHint.setWrapText(true);
        probeHint.setMaxWidth(440);

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        Button save = new Button(I18n.t("common.save"));
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            if (commitModelForm()) {
                shell.stage().close();
            }
        });
        Button test = new Button(I18n.t("settings.model.test"));
        test.setOnAction(e -> runModelTest(test, probeHint));
        Button testVision = new Button(I18n.t("settings.model.visionTest"));
        testVision.setOnAction(e -> runModelVisionTest(testVision, probeHint));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox btns = new HBox(8, test, testVision, gap, cancel, save);
        btns.setAlignment(Pos.CENTER_RIGHT);

        shell.body().getChildren().addAll(form, probeHint, btns);
        shell.showAndWait();
    }

    /** 发起连通性探测：后台线程跑，结果回主线程回显；弹窗若已关闭则丢弃。 */
    private void runModelTest(Button test, Label hint) {
        String provider = modelProviderCombo.getValue();
        String modelName = modelNameField.getText().trim();
        if (provider == null || provider.isBlank() || modelName.isEmpty()) {
            hint.getStyleClass().removeAll("probe-ok", "probe-fail");
            hint.getStyleClass().add("probe-fail");
            hint.setText(I18n.t("settings.model.testNeedFields"));
            return;
        }
        String modelCode = modelCodeField.getText().trim();
        test.setDisable(true);
        hint.getStyleClass().removeAll("probe-ok", "probe-fail");
        hint.setText(I18n.t("settings.model.testing"));
        Thread worker = new Thread(() -> {
            ModelProbe.Result result = adminService.testModel(provider, modelCode, modelName);
            Platform.runLater(() -> {
                if (test.getScene() == null) {
                    return;
                }
                test.setDisable(false);
                modelProbeResult = result;
                hint.getStyleClass().removeAll("probe-ok", "probe-fail");
                hint.getStyleClass().add(result.ok() ? "probe-ok" : "probe-fail");
                hint.setText(I18n.t(result.ok() ? "settings.model.testOk" : "settings.model.testFail",
                        result.detail()));
            });
        }, "model-connection-test");
        worker.setDaemon(true);
        worker.start();
    }

    /** 发起多模态探测：与连通性测试共用回显位，但不写入 modelProbeResult（不影响保存时的可用状态）。 */
    private void runModelVisionTest(Button btn, Label hint) {
        String provider = modelProviderCombo.getValue();
        String modelName = modelNameField.getText().trim();
        if (provider == null || provider.isBlank() || modelName.isEmpty()) {
            hint.getStyleClass().removeAll("probe-ok", "probe-fail");
            hint.getStyleClass().add("probe-fail");
            hint.setText(I18n.t("settings.model.testNeedFields"));
            return;
        }
        String modelCode = modelCodeField.getText().trim();
        btn.setDisable(true);
        hint.getStyleClass().removeAll("probe-ok", "probe-fail");
        hint.setText(I18n.t("settings.model.visionTesting"));
        Thread worker = new Thread(() -> {
            ModelProbe.Result result = adminService.testModelVision(provider, modelCode, modelName);
            Platform.runLater(() -> {
                if (btn.getScene() == null) {
                    return;
                }
                btn.setDisable(false);
                modelVisionResult = result;
                hint.getStyleClass().removeAll("probe-ok", "probe-fail");
                hint.getStyleClass().add(result.ok() ? "probe-ok" : "probe-fail");
                hint.setText(I18n.t(result.ok() ? "settings.model.visionOk" : "settings.model.visionFail",
                        result.detail()));
            });
        }, "model-vision-test");
        worker.setDaemon(true);
        worker.start();
    }

    /** 把本次探测结果落到待保存实体；没测过则沿用原记录，避免编辑把已验证状态抹成 UNKNOWN。 */
    private void applyModelStatus(ModelConfig m) {
        if (modelProbeResult != null) {
            ProviderAdminService.applyProbe(m, modelProbeResult);
        } else if (modelBaseline != null) {
            m.setStatus(modelBaseline.getStatus());
            m.setLastTestAt(modelBaseline.getLastTestAt());
            m.setLastTestResult(modelBaseline.getLastTestResult());
        } else {
            m.setStatus(ProviderAdminService.MODEL_STATUS_UNKNOWN);
        }
        // 多模态能力位独立于可用状态：测过则写入，没测则沿用原值
        if (modelVisionResult != null) {
            ProviderAdminService.applyVision(m, modelVisionResult);
        } else if (modelBaseline != null) {
            m.setSupportsVision(modelBaseline.getSupportsVision());
        } else {
            m.setSupportsVision(null);
        }
    }

    private String lastTestText(ModelConfig m) {
        if (m.getLastTestAt() == null) {
            return "-";
        }
        String time = m.getLastTestAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String detail = m.getLastTestResult();
        return detail == null || detail.isBlank() ? time : time + " · " + detail;
    }

    // ---- 默认模型页 ----

    VBox buildDefaultPage() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.default"), I18n.t("settings.default.subtitle"));

        Button changeBtn = new Button(I18n.t("settings.default.change"));
        changeBtn.getStyleClass().add("btn-primary");
        changeBtn.setOnAction(e -> openDefaultModelPicker());

        defaultModelInfoLabel.getStyleClass().add("row-value");

        List<Node> rows = List.of(
                SettingsLayout.settingRow(I18n.t("settings.default.current"), null, defaultModelInfoLabel));
        HBox actions = new HBox(changeBtn);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(6, 0, 0, 0));

        box.getChildren().addAll(SettingsLayout.groupCard(null, rows), actions);
        return box;
    }

    /** 弹窗选择默认模型：列出所有已启用模型，可勾选只看多模态，选中后确定即设为默认。 */
    private void openDefaultModelPicker() {
        GimDialog.Shell shell = GimDialog.shell(host.hostWindow(), I18n.t("settings.default.pickTitle"));

        List<ModelConfig> models = adminService.listModels().stream()
                .filter(m -> EnabledStatus.isEnabled(m.getEnabled()))
                .toList();
        if (models.isEmpty()) {
            shell.stage().close();
            host.notify("settings.menu.default", I18n.t("settings.status.noEnabledModel"));
            return;
        }

        ListView<ModelConfig> listView = new ListView<>();
        listView.setPrefSize(420, 300);
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ModelConfig item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String name = item.getModelName() == null || item.getModelName().isBlank()
                        ? item.getModelCode() : item.getModelName();
                String vision = Integer.valueOf(1).equals(item.getSupportsVision())
                        ? " · " + I18n.t("settings.model.multimodal") : "";
                setText(name + "  (" + item.getProviderCode() + ")" + vision);
            }
        });

        // 预选当前默认（若存在）
        String curModel = adminService.getDefaultModelCode().orElse(null);

        CheckBox filterCheck = new CheckBox(I18n.t("settings.model.vision.only"));
        filterCheck.getStyleClass().add("cap-check");
        // 默认不勾选：避免误杀尚未测过或纯文本的模型
        filterCheck.setSelected(false);

        Runnable refill = () -> {
            List<ModelConfig> shown = filterCheck.isSelected()
                    ? models.stream().filter(m -> Integer.valueOf(1).equals(m.getSupportsVision())).toList()
                    : models;
            listView.setItems(FXCollections.observableArrayList(shown));
            int idx = -1;
            if (curModel != null) {
                for (int i = 0; i < shown.size(); i++) {
                    if (shown.get(i).getModelCode().equals(curModel)) {
                        idx = i;
                        break;
                    }
                }
            }
            if (idx >= 0) {
                listView.getSelectionModel().select(idx);
            } else if (!shown.isEmpty()) {
                listView.getSelectionModel().selectFirst();
            }
        };
        filterCheck.setOnAction(e -> refill.run());
        refill.run();

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        Button ok = new Button(I18n.t("common.ok"));
        ok.getStyleClass().add("btn-primary");
        ok.setOnAction(e -> {
            ModelConfig picked = listView.getSelectionModel().getSelectedItem();
            if (picked == null) {
                return;
            }
            adminService.setDefault(picked.getProviderCode(), picked.getModelCode());
            refreshDefaultModelInfo();
            shell.stage().close();
        });
        // 双击即选定，省去再摸一次按钮
        listView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && listView.getSelectionModel().getSelectedItem() != null) {
                ok.fire();
            }
        });
        HBox filterRow = new HBox(filterCheck);
        filterRow.setPadding(new Insets(0, 0, 6, 0));
        HBox btns = new HBox(8, cancel, ok);
        btns.setAlignment(Pos.CENTER_RIGHT);

        shell.body().getChildren().addAll(filterRow, listView, btns);
        shell.showAndWait();
    }

    /** 刷新默认模型标签显示。 */
    private void refreshDefaultModelInfo() {
        String provider = adminService.getDefaultProviderCode().orElse(null);
        String model = adminService.getDefaultModelCode().orElse(null);
        if (provider == null || model == null) {
            defaultModelInfoLabel.setText(I18n.t("settings.default.notSet"));
        } else {
            // 显示模型名而非编码
            String displayName = adminService.listModels().stream()
                    .filter(m -> m.getModelCode().equals(model))
                    .map(m -> m.getModelName() == null || m.getModelName().isBlank() ? m.getModelCode() : m.getModelName())
                    .findFirst().orElse(model);
            defaultModelInfoLabel.setText(provider + " / " + displayName);
        }
    }

    // ---- 表单 ----

    private GridPane buildProviderForm() {
        GridPane g = SettingsLayout.grid();
        int r = 0;
        g.addRow(r++, new Label(I18n.t("settings.provider.code")), providerCodeField);
        g.addRow(r++, new Label(I18n.t("settings.provider.name")), providerNameField);
        g.addRow(r++, new Label(I18n.t("settings.provider.protocol")), protocolCombo);
        g.addRow(r++, new Label(I18n.t("settings.provider.auth")), authCombo);
        g.addRow(r++, new Label(I18n.t("settings.provider.baseUrl")), baseUrlField);
        g.addRow(r++, new Label(I18n.t("settings.provider.apiKey")), apiKeyField);
        g.addRow(r++, new Label(I18n.t("common.enable")), providerEnabledCheck);
        g.addRow(r, new Label(I18n.t("settings.provider.remark")), remarkField);
        return g;
    }

    private GridPane buildModelForm() {
        GridPane g = SettingsLayout.grid();
        int r = 0;
        g.addRow(r++, new Label(I18n.t("settings.model.provider")), modelProviderCombo);
        g.addRow(r++, new Label(I18n.t("settings.model.code")), modelCodeField);
        g.addRow(r++, new Label(I18n.t("settings.model.name")), modelNameField);
        g.addRow(r++, new Label(I18n.t("settings.model.priority")), priorityField);
        g.addRow(r, new Label(I18n.t("common.enable")), modelEnabledCheck);
        return g;
    }

    // ---- 提供商操作 ----

    private void clearProviderForm() {
        providerCodeField.clear();
        providerNameField.clear();
        baseUrlField.clear();
        apiKeyField.clear();
        remarkField.clear();
        protocolCombo.setValue(ApiProtocol.DASHSCOPE.name());
        authCombo.setValue(AuthMethod.X_API_KEY.name());
        apiKeyField.setPromptText("");
        providerEnabledCheck.setSelected(true);
        providerCodeField.setDisable(false);
    }

    private void loadProviderToForm(ModelProviderConfig p) {
        providerCodeField.setText(p.getProviderCode());
        providerCodeField.setDisable(true);
        providerNameField.setText(p.getProviderName());
        protocolCombo.setValue(p.getApiProtocol());
        authCombo.setValue(p.getAuthMethod());
        baseUrlField.setText(p.getBaseUrl());
        apiKeyField.setText("");
        apiKeyField.setPromptText(I18n.t("settings.provider.apiKeyKeep"));
        providerEnabledCheck.setSelected(EnabledStatus.isEnabled(p.getEnabled()));
        remarkField.setText(p.getRemark());
    }

    private boolean commitProviderForm() {
        if (providerCodeField.getText().isBlank()) {
            host.notify("settings.menu.providers", I18n.t("settings.status.providerCodeEmpty"));
            return false;
        }
        ModelProviderConfig p = new ModelProviderConfig();
        p.setProviderCode(providerCodeField.getText().trim());
        p.setProviderName(providerNameField.getText().trim());
        p.setApiProtocol(protocolCombo.getValue());
        p.setAuthMethod(authCombo.getValue());
        p.setBaseUrl(baseUrlField.getText().trim());
        p.setEnabled(providerEnabledCheck.isSelected() ? EnabledStatus.ENABLED : EnabledStatus.DISABLED);
        p.setRemark(remarkField.getText().trim());
        // 编辑时保留原密文：从库中读回
        adminService.listProviders().stream()
                .filter(ex -> ex.getProviderCode().equals(p.getProviderCode()))
                .findFirst().ifPresent(ex -> p.setApiKey(ex.getApiKey()));
        String newKey = apiKeyField.getText();
        adminService.saveProvider(p, (newKey == null || newKey.isBlank()) ? null : newKey);
        host.reloadAll();
        return true;
    }

    private void handleDeleteProvider(ModelProviderConfig sel) {
        if (!GimDialog.confirm(host.hostWindow(), I18n.t("settings.menu.providers"),
                I18n.t("settings.provider.deleteConfirm", sel.getProviderCode()))) {
            return;
        }
        adminService.deleteProvider(sel.getProviderCode());
        host.reloadAll();
    }

    // ---- 模型操作 ----

    private void clearModelForm() {
        modelCodeField.clear();
        modelCodeField.setDisable(false);
        modelNameField.clear();
        priorityField.setText("100");
        modelEnabledCheck.setSelected(true);
        modelBaseline = null;
        modelProbeResult = null;
        modelVisionResult = null;
        if (modelProviderCombo.getItems().isEmpty()) {
            refreshProviderCombos();
        }
    }

    private void loadModelToForm(ModelConfig m) {
        modelProviderCombo.setValue(m.getProviderCode());
        modelCodeField.setText(m.getModelCode());
        modelCodeField.setDisable(true);
        modelNameField.setText(m.getModelName());
        priorityField.setText(String.valueOf(m.getPriority()));
        modelEnabledCheck.setSelected(EnabledStatus.isEnabled(m.getEnabled()));
        modelBaseline = m;
        modelProbeResult = null;
        modelVisionResult = null;
    }

    private boolean commitModelForm() {
        if (modelCodeField.getText().isBlank() || modelNameField.getText().isBlank()) {
            host.notify("settings.menu.models", I18n.t("settings.status.modelFieldsEmpty"));
            return false;
        }
        ModelConfig m = new ModelConfig();
        m.setProviderCode(modelProviderCombo.getValue());
        m.setModelCode(modelCodeField.getText().trim());
        m.setModelName(modelNameField.getText().trim());
        m.setPriority(parseInt(priorityField.getText(), 100));
        m.setEnabled(modelEnabledCheck.isSelected() ? EnabledStatus.ENABLED : EnabledStatus.DISABLED);
        applyModelStatus(m);
        adminService.saveModel(m);
        host.reloadAll();
        return true;
    }

    private void handleDeleteModel(ModelConfig sel) {
        if (!GimDialog.confirm(host.hostWindow(), I18n.t("settings.menu.models"),
                I18n.t("settings.model.deleteConfirm", sel.getModelCode()))) {
            return;
        }
        adminService.deleteModel(sel.getModelCode());
        host.reloadAll();
    }

    private void refreshProviderCombos() {
        List<String> codes = adminService.listProviders().stream()
                .map(ModelProviderConfig::getProviderCode).toList();
        String selModelProvider = modelProviderCombo.getValue();
        modelProviderCombo.setItems(FXCollections.observableArrayList(codes));
        if (selModelProvider == null && !codes.isEmpty()) {
            selModelProvider = codes.get(0);
        }
        modelProviderCombo.setValue(selModelProvider);
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
