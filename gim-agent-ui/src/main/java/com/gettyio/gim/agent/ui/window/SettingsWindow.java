package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.entity.McpServerConfig;
import com.gettyio.gim.agent.domain.entity.ModelConfig;
import com.gettyio.gim.agent.domain.entity.ModelProviderConfig;
import com.gettyio.gim.agent.domain.entity.ToolPermissionRule;
import com.gettyio.gim.agent.domain.enums.ApiProtocol;
import com.gettyio.gim.agent.domain.enums.AuthMethod;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.enums.McpTransportType;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import com.gettyio.gim.agent.ui.control.ToggleSwitch;
import com.gettyio.gim.agent.ui.view.McpView;
import com.gettyio.gim.agent.ui.config.AgentAdminService;
import com.gettyio.gim.agent.ui.config.McpAdminService;
import com.gettyio.gim.agent.ui.config.ProviderAdminService;
import com.gettyio.gim.agent.ui.config.SecurityAdminService;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Window;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 设置窗口：左侧分类菜单（含搜索）+ 右侧内容页（对标 Qoder 设置风格）。
 * <p>分页：个人资料 / 外观（主题·字体·语言，接入 {@link UiTheme}/{@link I18n}）/
 * 记忆（只读查看 workspace/MEMORY.md）/ 通用（数据与路径）/ 智能体 /
 * 模型提供商 / 模型 / 默认模型 / MCP 服务 / 工具权限。仅呈现有真实后端支撑的页面，不伪造无功能菜单项。
 * 外观页改动即时生效；语言切换时整窗重建以刷新全部文案。</p>
 */
@Component
public class SettingsWindow {

    @Autowired
    private ProviderAdminService adminService;

    @Autowired
    private GimPaths gimPaths;

    @Autowired
    private McpAdminService mcpAdminService;

    @Autowired
    private SecurityAdminService securityAdminService;

    @Autowired
    private AgentAdminService agentAdminService;

    private final ListView<ModelProviderConfig> providerListView = new ListView<>();
    private final ListView<ModelConfig> modelListView = new ListView<>();
    /** 信息卡片固定宽度：不铺满整行；有内容时左对齐，空状态居中。 */
    private static final double CARD_WIDTH = 400;
    private final VBox providerCardsBox = new VBox(12);
    private final VBox modelCardsBox = new VBox(12);
    private final VBox agentCardsBox = new VBox(12);

    private final TextField providerCodeField = new TextField();
    private final TextField providerNameField = new TextField();
    private final ComboBox<String> protocolCombo = new ComboBox<>();
    private final ComboBox<String> authCombo = new ComboBox<>();
    private final TextField baseUrlField = new TextField();
    private final PasswordField apiKeyField = new PasswordField();
    private final ToggleSwitch providerEnabledCheck = new ToggleSwitch();
    private final TextField remarkField = new TextField();

    private final ComboBox<String> modelProviderCombo = new ComboBox<>();
    private final TextField modelCodeField = new TextField();
    private final TextField modelNameField = new TextField();
    private final TextField priorityField = new TextField();
    private final ToggleSwitch modelEnabledCheck = new ToggleSwitch();

    private final ComboBox<String> defaultProviderCombo = new ComboBox<>();
    private final ComboBox<String> defaultModelCombo = new ComboBox<>();
    private final Label statusLabel = new Label("");

    /** MCP 服务页复用组件（自包含）；与拓展页各自实例化，互不干扰场景图父子关系。 */
    private McpView mcpView;

    private final ListView<ToolPermissionRule> permissionListView = new ListView<>();

    private Scene hostScene;
    private AppShell shell;
    private Runnable onBack;
    private boolean showing;
    private StackPane pageContainer;
    private ToggleGroup menuGroup;
    private VBox menuBox;
    private final List<MenuEntry> menuEntries = new ArrayList<>();
    private boolean loading;
    private boolean langListenerRegistered;
    private boolean modelProviderListenerAdded;

    /** 菜单项与搜索元数据。 */
    private static final class MenuEntry {
        final ToggleButton button;
        final String haystack;
        final Label section;

        MenuEntry(ToggleButton button, String haystack, Label section) {
            this.button = button;
            this.haystack = haystack;
            this.section = section;
        }
    }

    /** 在主窗口外壳内显示设置：替换 shell 中部内容（标题栏常驻），点击“返回应用”回调切回主界面。 */
    public void showIn(AppShell shell, Scene scene, Runnable onBack) {
        this.shell = shell;
        this.hostScene = scene;
        this.onBack = onBack;
        this.showing = true;
        shell.setContent(buildRoot());
        UiTheme.apply(scene);
        reloadAll();
        selectFirstMenu();
        registerLangListener();
    }

    /** 语言切换时整窗重建，使全部文案即时刷新（仅当设置视图正显示时）。 */
    private void registerLangListener() {
        if (langListenerRegistered) {
            return;
        }
        langListenerRegistered = true;
        I18n.onChange(lang -> {
            if (showing) {
                Platform.runLater(this::rebuildInPlace);
            }
        });
    }

    private void rebuildInPlace() {
        String keep = statusLabel.getText();
        shell.setContent(buildRoot());
        UiTheme.apply(hostScene);
        reloadAll();
        statusLabel.setText(keep);
        selectFirstMenu();
    }

    // ---- 根布局 ----

    private BorderPane buildRoot() {
        menuEntries.clear();
        menuGroup = new ToggleGroup();
        pageContainer = new StackPane();
        pageContainer.getStyleClass().add("settings-pages");
        menuGroup.selectedToggleProperty().addListener((obs, o, t) -> {
            if (t != null) {
                pageContainer.getChildren().setAll((Node) t.getUserData());
            }
        });
        initComboItems();

        BorderPane root = new BorderPane();
        root.getStyleClass().add("window-root");
        root.setLeft(buildMenu());

        ScrollPane scroller = new ScrollPane(pageContainer);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.getStyleClass().add("settings-scroll");
        // 让内容至少占满视口高度，使空状态等能在页面内垂直居中（对齐技能模块）
        pageContainer.minHeightProperty().bind(Bindings.createDoubleBinding(
                () -> scroller.getViewportBounds().getHeight(),
                scroller.viewportBoundsProperty()));

        statusLabel.getStyleClass().add("status-label");
        statusLabel.setPadding(new Insets(6, 14, 8, 14));
        // 状态栏只置于右侧内容区下方，避免横贯左菜单底部导致菜单无法触底（左下露白）
        BorderPane centerPane = new BorderPane();
        centerPane.setCenter(scroller);
        centerPane.setBottom(statusLabel);
        root.setCenter(centerPane);
        return root;
    }

    // ---- 菜单构建（含搜索过滤） ----

    private VBox buildMenu() {
        menuBox = new VBox();
        menuBox.getStyleClass().add("settings-menu");

        Button back = new Button(I18n.t("settings.back"));
        Label backIcon = new Label("\uE76B");
        backIcon.getStyleClass().add("icon");
        back.setGraphic(backIcon);
        back.getStyleClass().add("settings-back");
        back.setOnAction(e -> {
            showing = false;
            if (onBack != null) {
                onBack.run();
            }
        });
        TextField search = new TextField();
        search.getStyleClass().add("settings-search");
        search.setPromptText(I18n.t("settings.searchPrompt"));
        search.textProperty().addListener((obs, o, n) -> filterMenu(n));
        menuBox.getChildren().addAll(back, search);

        Label personal = addSection(I18n.t("settings.section.personal"));
        addMenu(personal, "\uE77B", I18n.t("settings.menu.profile"), buildProfilePage());
        addMenu(personal, "\uE790", I18n.t("settings.menu.appearance"), buildAppearancePage());
        addMenu(personal, "\uE7C3", I18n.t("settings.menu.memory"), buildMemoryPage());
        addMenu(personal, "\uE713", I18n.t("settings.menu.general"), buildGeneralPage());

        Label model = addSection(I18n.t("settings.section.model"));
        addMenu(model, "\uE99A", I18n.t("settings.menu.agents"), buildAgentPage());
        addMenu(model, "\uE753", I18n.t("settings.menu.providers"), buildProviderPage());
        addMenu(model, "\uE7B8", I18n.t("settings.menu.models"), buildModelPage());
        addMenu(model, "\uE8CC", I18n.t("settings.menu.default"), buildDefaultPage());

        Label extension = addSection(I18n.t("settings.section.extension"));
        addMenu(extension, "\uE71B", I18n.t("settings.menu.mcp"), buildMcpPage());
        addMenu(extension, "\uE72E", I18n.t("settings.menu.security"), buildSecurityPage());

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        menuBox.getChildren().add(spacer);
        return menuBox;
    }

    private Label addSection(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-section");
        menuBox.getChildren().add(l);
        return l;
    }

    private void addMenu(Label section, String iconText, String label, Node page) {
        ToggleButton tb = new ToggleButton(label);
        tb.setToggleGroup(menuGroup);
        tb.getStyleClass().add("settings-toggle");
        tb.setMaxWidth(Double.MAX_VALUE);
        tb.setFocusTraversable(false);
        Label icon = new Label(iconText);
        icon.getStyleClass().add("icon");
        tb.setGraphic(icon);
        tb.setUserData(page);
        menuBox.getChildren().add(tb);
        String haystack = (label + " " + section.getText()).toLowerCase(Locale.ROOT);
        menuEntries.add(new MenuEntry(tb, haystack, section));
    }

    private void filterMenu(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Map<Label, Boolean> sectionVisible = new LinkedHashMap<>();
        for (MenuEntry e : menuEntries) {
            boolean match = q.isEmpty() || e.haystack.contains(q);
            e.button.setVisible(match);
            e.button.setManaged(match);
            sectionVisible.merge(e.section, match, (a, b) -> a || b);
        }
        for (Map.Entry<Label, Boolean> se : sectionVisible.entrySet()) {
            se.getKey().setVisible(se.getValue());
            se.getKey().setManaged(se.getValue());
        }
    }

    private void selectFirstMenu() {
        if (!menuGroup.getToggles().isEmpty()) {
            ((ToggleButton) menuGroup.getToggles().get(0)).setSelected(true);
        }
    }

    // ---- 页面 / 行 / 分组 辅助 ----

    private VBox page(String title, String subtitle) {
        VBox box = new VBox();
        box.getStyleClass().add("settings-page");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMaxHeight(Double.MAX_VALUE);
        Label t = new Label(title);
        t.getStyleClass().add("page-title");
        box.getChildren().add(t);
        if (subtitle != null && !subtitle.isBlank()) {
            Label s = new Label(subtitle);
            s.getStyleClass().add("page-subtitle");
            s.setWrapText(true);
            box.getChildren().add(s);
        }
        return box;
    }

    private VBox groupCard(String groupTitle, List<Node> rows) {
        VBox wrap = new VBox(6);
        if (groupTitle != null && !groupTitle.isBlank()) {
            Label gt = new Label(groupTitle);
            gt.getStyleClass().add("settings-group-title");
            wrap.getChildren().add(gt);
        }
        VBox card = new VBox();
        card.getStyleClass().add("settings-group");
        for (int i = 0; i < rows.size(); i++) {
            Node r = rows.get(i);
            if (i > 0 && r instanceof HBox hb) {
                hb.getStyleClass().add("settings-row-divider");
            }
            card.getChildren().add(r);
        }
        wrap.getChildren().add(card);
        return wrap;
    }

    /** 一行：左侧标题 + 描述，右侧控件；{@code desc}/{@code control} 可为 null。 */
    private HBox settingRow(String title, String desc, Node control) {
        VBox text = new VBox(3);
        text.getStyleClass().add("row-text");
        Label t = new Label(title);
        t.getStyleClass().add("row-title");
        text.getChildren().add(t);
        if (desc != null && !desc.isBlank()) {
            Label d = new Label(desc);
            d.getStyleClass().add("row-desc");
            d.setWrapText(true);
            d.setMaxWidth(520);
            text.getChildren().add(d);
        }
        HBox row = new HBox(text);
        row.getStyleClass().add("settings-row");
        HBox.setHgrow(text, Priority.ALWAYS);
        if (control != null) {
            HBox ctrl = new HBox(control);
            ctrl.getStyleClass().add("row-control");
            row.getChildren().add(ctrl);
        }
        return row;
    }

    private ComboBox<String> pillCombo(String... options) {
        ComboBox<String> c = new ComboBox<>(FXCollections.observableArrayList(options));
        c.getStyleClass().add("pill-combo");
        return c;
    }

    // ---- 个人资料页 ----

    private VBox buildProfilePage() {
        VBox box = page(I18n.t("settings.menu.profile"), I18n.t("settings.profile.subtitle"));

        HBox head = new HBox(16);
        head.getStyleClass().add("profile-head");
        Label avatar = new Label("A");
        avatar.getStyleClass().add("profile-avatar");
        avatar.setAlignment(Pos.CENTER);
        Label name = new Label(I18n.t("user.local"));
        name.getStyleClass().add("profile-name");
        Label badge = new Label(I18n.t("settings.profile.badge"));
        badge.getStyleClass().addAll("badge", "badge-green");
        VBox info = new VBox(6, name, badge);
        info.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(avatar, info);

        List<Node> rows = new ArrayList<>();
        rows.add(settingRow(I18n.t("settings.profile.appVersion"), null,
                valueLabel(I18n.t("settings.profile.appVersionValue"))));
        rows.add(settingRow(I18n.t("settings.key.dataDir"), null,
                valueLabel(String.valueOf(gimPaths.root()))));
        rows.add(settingRow(I18n.t("settings.profile.language"), null,
                valueLabel(I18n.current().nativeName())));
        rows.add(settingRow(I18n.t("settings.profile.theme"), null,
                valueLabel(I18n.t(UiTheme.mode() == UiTheme.Mode.DARK ? "menu.mode.dark" : "menu.mode.light"))));

        box.getChildren().addAll(head, groupCard(I18n.t("settings.group.about"), rows));
        return box;
    }

    private Label valueLabel(String value) {
        Label l = new Label(value);
        l.getStyleClass().add("row-value");
        return l;
    }

    // ---- 外观页（真实接入 UiTheme / I18n） ----

    private VBox buildAppearancePage() {
        VBox box = page(I18n.t("settings.menu.appearance"), I18n.t("settings.appearance.subtitle"));

        String[] themeOpts = {I18n.t("menu.mode.light"), I18n.t("menu.mode.dark")};
        ComboBox<String> themeCombo = pillCombo(themeOpts);
        themeCombo.setValue(UiTheme.mode() == UiTheme.Mode.DARK ? themeOpts[1] : themeOpts[0]);
        themeCombo.setOnAction(e -> UiTheme.setMode(
                themeCombo.getSelectionModel().getSelectedIndex() == 1
                        ? UiTheme.Mode.DARK : UiTheme.Mode.LIGHT));

        String[] fontOpts = {I18n.t("menu.font.small"), I18n.t("menu.font.medium"), I18n.t("menu.font.large")};
        ComboBox<String> fontCombo = pillCombo(fontOpts);
        fontCombo.setValue(fontOpts[UiTheme.fontSize().ordinal()]);
        fontCombo.setOnAction(e -> {
            int idx = fontCombo.getSelectionModel().getSelectedIndex();
            if (idx >= 0) {
                UiTheme.setFontSize(UiTheme.FontSize.values()[idx]);
            }
        });

        I18n.Language[] langs = I18n.Language.values();
        String[] langOpts = new String[langs.length];
        for (int i = 0; i < langs.length; i++) {
            langOpts[i] = langs[i].nativeName();
        }
        ComboBox<String> langCombo = pillCombo(langOpts);
        langCombo.setValue(I18n.current().nativeName());
        langCombo.setOnAction(e -> {
            int idx = langCombo.getSelectionModel().getSelectedIndex();
            if (idx >= 0) {
                I18n.setLanguage(langs[idx]);
            }
        });

        List<Node> rows = List.of(
                settingRow(I18n.t("settings.appearance.theme"), I18n.t("settings.appearance.theme.desc"), themeCombo),
                settingRow(I18n.t("settings.appearance.font"), I18n.t("settings.appearance.font.desc"), fontCombo),
                settingRow(I18n.t("settings.appearance.lang"), I18n.t("settings.appearance.lang.desc"), langCombo));
        box.getChildren().add(groupCard(I18n.t("settings.group.interface"), rows));
        return box;
    }

    // ---- 通用页（数据与路径） ----

    private VBox buildGeneralPage() {
        VBox box = page(I18n.t("settings.menu.general"), I18n.t("settings.general.subtitle"));
        List<Node> rows = new ArrayList<>();
        rows.add(infoRow(I18n.t("settings.key.dataDir"), gimPaths.root()));
        rows.add(infoRow(I18n.t("settings.key.workspace"), gimPaths.workspaceDir()));
        rows.add(infoRow(I18n.t("settings.key.skillsDir"), gimPaths.skillsDir()));
        rows.add(infoRow(I18n.t("settings.key.knowledgeDir"), gimPaths.knowledgeDir()));
        rows.add(infoRow(I18n.t("settings.key.logsDir"), gimPaths.logsDir()));
        rows.add(infoRow(I18n.t("settings.key.db"), gimPaths.dbFile()));
        box.getChildren().add(groupCard(I18n.t("settings.group.paths"), rows));
        return box;
    }

    private HBox infoRow(String key, Object value) {
        return settingRow(key, String.valueOf(value), null);
    }

    // ---- 记忆页（只读：查看 workspace/MEMORY.md，由 Agent 自动维护） ----

    private VBox buildMemoryPage() {
        VBox box = page(I18n.t("settings.menu.memory"), I18n.t("settings.memory.subtitle"));
        Path memoryFile = gimPaths.memoryMd();

        TextArea viewer = new TextArea(readMemory(memoryFile));
        viewer.getStyleClass().add("memory-editor");
        viewer.setWrapText(true);
        viewer.setEditable(false);
        viewer.setMaxWidth(Double.MAX_VALUE);

        Button reveal = new Button(I18n.t("settings.memory.reveal"));
        reveal.setOnAction(e -> revealMemory(memoryFile));
        HBox actions = new HBox(8, reveal);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(8, 0, 0, 0));

        Label hint = new Label(I18n.t("settings.memory.readonlyHint"));
        hint.getStyleClass().add("row-desc");
        hint.setWrapText(true);

        VBox card = new VBox(
                settingRow(I18n.t("settings.memory.file"), String.valueOf(memoryFile), null),
                hint, viewer, actions);
        card.getStyleClass().add("settings-group");
        Label gt = new Label(I18n.t("settings.group.memory"));
        gt.getStyleClass().add("settings-group-title");
        box.getChildren().addAll(gt, card);
        return box;
    }

    private String readMemory(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void revealMemory(Path file) {
        try {
            Path dir = file.getParent();
            if (Desktop.isDesktopSupported() && dir != null) {
                Desktop.getDesktop().open(dir.toFile());
            } else {
                statusLabel.setText(I18n.t("settings.memory.revealFail", "Desktop unsupported"));
            }
        } catch (Exception e) {
            statusLabel.setText(I18n.t("settings.memory.revealFail", e.getMessage()));
        }
    }

    // ---- 智能体页 ----

    private VBox buildAgentPage() {
        VBox box = new VBox();
        box.getStyleClass().add("settings-page");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMaxHeight(Double.MAX_VALUE);

        VBox headText = new VBox(4);
        Label title = new Label(I18n.t("settings.menu.agents"));
        title.getStyleClass().add("page-title");
        Label subtitle = new Label(I18n.t("settings.agents.subtitle"));
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.setWrapText(true);
        headText.getChildren().addAll(title, subtitle);

        Button addBtn = new Button(I18n.t("settings.agent.add"));
        addBtn.getStyleClass().add("btn-primary");
        Label addIcon = new Label("\uE710");
        addIcon.getStyleClass().add("icon");
        addBtn.setGraphic(addIcon);
        addBtn.setOnAction(e -> openAgentDialog(null));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(headText, spacer, addBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        agentCardsBox.getStyleClass().add("provider-cards");
        agentCardsBox.setFillWidth(false);
        VBox.setVgrow(agentCardsBox, Priority.ALWAYS);
        box.getChildren().addAll(header, agentCardsBox);
        return box;
    }

    /** 依据库中数据重绘智能体信息卡片。 */
    private void renderAgentCards() {
        agentCardsBox.getChildren().clear();
        List<AgentDefinition> agents = agentAdminService.list();
        if (agents.isEmpty()) {
            agentCardsBox.setAlignment(Pos.CENTER);
            agentCardsBox.getChildren().add(agentEmptyState());
            return;
        }
        agentCardsBox.setAlignment(Pos.TOP_LEFT);
        for (AgentDefinition a : agents) {
            agentCardsBox.getChildren().add(agentCard(a));
        }
    }

    private VBox agentEmptyState() {
        VBox box = new VBox();
        box.getStyleClass().add("empty-state");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFillWidth(false);
        Label icon = new Label("\uE99A");
        icon.getStyleClass().add("empty-icon");
        Label t = new Label(I18n.t("settings.agent.empty"));
        t.getStyleClass().add("empty-title");
        t.setTextAlignment(TextAlignment.CENTER);
        Label d = new Label(I18n.t("settings.agent.emptyDesc"));
        d.getStyleClass().add("empty-desc");
        d.setWrapText(true);
        d.setTextAlignment(TextAlignment.CENTER);
        d.setMaxWidth(460);
        box.getChildren().addAll(icon, t, d);
        return box;
    }

    private VBox agentCard(AgentDefinition a) {
        boolean builtIn = agentAdminService.isDefault(a.getAgentCode());

        Label name = new Label(a.getAgentName() == null || a.getAgentName().isBlank()
                ? a.getAgentCode() : a.getAgentName());
        name.getStyleClass().add("provider-card-title");
        Label badge = new Label(I18n.t(builtIn ? "settings.agent.builtIn" : "settings.agent.custom"));
        badge.getStyleClass().addAll("badge", builtIn ? "badge-green" : "badge-gray");
        HBox titleRow = new HBox(10, name, badge);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Button edit = iconTextButton("\uE70F", I18n.t("common.edit"), () -> openAgentDialog(a));
        edit.getStyleClass().add("link-btn");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox head;
        if (builtIn) {
            // 内置默认智能体不可删：它是所有未指定智能体的会话/任务的回退目标
            head = new HBox(titleRow, sp, edit);
        } else {
            Button del = iconTextButton("\uE74D", I18n.t("common.delete"), () -> handleDeleteAgent(a));
            del.getStyleClass().addAll("link-btn", "link-btn-danger");
            head = new HBox(titleRow, sp, edit, del);
        }
        head.setAlignment(Pos.CENTER_LEFT);

        String model = a.getProviderCode() == null || a.getProviderCode().isBlank()
                ? I18n.t("settings.agent.followDefault")
                : a.getProviderCode() + " / " + a.getModelCode();
        String params = a.getTemperature() == null && a.getMaxTokens() == null
                ? I18n.t("settings.agent.followDefault")
                : I18n.t("settings.agent.paramsValue", str(a.getTemperature()), str(a.getMaxTokens()));
        VBox card = new VBox(8, head,
                kvLine(I18n.t("settings.agent.desc"), a.getDescription()),
                kvLine(I18n.t("settings.agent.prompt"), preview(a.getSystemPrompt())),
                kvLine(I18n.t("settings.agent.model"), model),
                kvLine(I18n.t("settings.agent.params"), params),
                kvLine(I18n.t("settings.agent.workspace"), a.getWorkspaceDir()),
                kvLine(I18n.t("settings.agent.skills"), capability(agentAdminService.declaredSkillNames(a.getSkillsJson()))),
                kvLine(I18n.t("settings.agent.mcp"), capability(agentAdminService.declaredMcpNames(a.getMcpServersJson()))),
                kvLine(I18n.t("settings.agent.usage"), I18n.t("settings.agent.usageValue",
                        str(agentAdminService.sessionUsage(a.getAgentCode())),
                        str(agentAdminService.taskUsage(a.getAgentCode())))));
        card.getStyleClass().add("provider-card");
        card.setPrefWidth(CARD_WIDTH);
        card.setMaxWidth(CARD_WIDTH);
        return card;
    }

    /** 人格可能很长，卡片内只预览折叠后的一小段。 */
    private static String preview(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= 72 ? flat : flat.substring(0, 72) + "\u2026";
    }

    /** MessageFormat 会给数字加上分组分隔符（2,000），计数与参数统一以文本传入。 */
    private static String str(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    /**
     * 能力行文本：白名单语义下「没有」必须写明，留白会被误读成「默认全部可用」。
     */
    private String capability(List<String> names) {
        if (names.isEmpty()) {
            return I18n.t("settings.agent.none");
        }
        I18n.Language lang = I18n.current();
        boolean cjk = lang == I18n.Language.ZH_CN || lang == I18n.Language.ZH_TW;
        return String.join(cjk ? "、" : ", ", names);
    }

    /** 弹智能体表单（与侧栏共用 {@link AgentDialog}），保存后刷列表。 */
    private void openAgentDialog(AgentDefinition editing) {
        AgentDialog.edit(hostWindow(), agentAdminService, editing).ifPresent(saved -> {
            statusLabel.setText(I18n.t("settings.status.savedAgent", saved.getAgentName()));
            reloadAll();
        });
    }

    private void handleDeleteAgent(AgentDefinition sel) {
        String code = sel.getAgentCode();
        if (agentAdminService.isDefault(code)) {
            statusLabel.setText(I18n.t("settings.status.defaultAgentUndeletable"));
            return;
        }
        int sessions = agentAdminService.sessionUsage(code);
        int tasks = agentAdminService.taskUsage(code);
        if (sessions > 0 || tasks > 0) {
            statusLabel.setText(I18n.t("settings.status.agentInUse", code, str(sessions), str(tasks)));
            return;
        }
        if (!GimDialog.confirm(hostWindow(), I18n.t("settings.menu.agents"),
                I18n.t("settings.agent.deleteConfirm", sel.getAgentName()))) {
            return;
        }
        agentAdminService.delete(code);
        statusLabel.setText(I18n.t("settings.status.deletedAgent", code));
        reloadAll();
    }

    // ---- 提供商页 ----

    private VBox buildProviderPage() {
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

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(headText, spacer, addBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        providerCardsBox.getStyleClass().add("provider-cards");
        providerCardsBox.setFillWidth(false);
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
        HBox titleRow = new HBox(10, name, badge);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Button edit = iconTextButton("\uE70F", I18n.t("common.edit"), () -> openProviderDialog(p));
        edit.getStyleClass().add("link-btn");
        Button del = iconTextButton("\uE74D", I18n.t("common.delete"), () -> handleDeleteProvider(p));
        del.getStyleClass().addAll("link-btn", "link-btn-danger");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox head = new HBox(titleRow, sp, edit, del);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, head,
                kvLine(I18n.t("settings.provider.code"), p.getProviderCode()),
                kvLine(I18n.t("settings.provider.protocol"), p.getApiProtocol()),
                kvLine(I18n.t("settings.provider.auth"), p.getAuthMethod()),
                kvLine(I18n.t("settings.provider.baseUrl"), p.getBaseUrl()),
                kvLine(I18n.t("settings.provider.remark"), p.getRemark()));
        card.getStyleClass().add("provider-card");
        card.setPrefWidth(CARD_WIDTH);
        card.setMaxWidth(CARD_WIDTH);
        return card;
    }

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

    /** 宿主窗口：作为无边框对话框的 owner（窗口模态并居中）。 */
    private Window hostWindow() {
        return hostScene == null ? null : hostScene.getWindow();
    }

    /** 弹出无边框模态窗口新增/编辑提供商；{@code editing} 为 null 表示新增。 */
    private void openProviderDialog(ModelProviderConfig editing) {
        GimDialog.Shell shell = GimDialog.shell(hostWindow(), I18n.t(editing == null
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

    private VBox buildModelPage() {
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
        modelCardsBox.setFillWidth(false);
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
        HBox titleRow = new HBox(10, name, badge);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Button edit = iconTextButton("\uE70F", I18n.t("common.edit"), () -> openModelDialog(m));
        edit.getStyleClass().add("link-btn");
        Button del = iconTextButton("\uE74D", I18n.t("common.delete"), () -> handleDeleteModel(m));
        del.getStyleClass().addAll("link-btn", "link-btn-danger");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox head = new HBox(titleRow, sp, edit, del);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, head,
                kvLine(I18n.t("settings.model.code"), m.getModelCode()),
                kvLine(I18n.t("settings.model.provider"), m.getProviderCode()),
                kvLine(I18n.t("settings.model.priority"), String.valueOf(m.getPriority())),
                kvLine(I18n.t("common.status"), m.getStatus()));
        card.getStyleClass().add("provider-card");
        card.setPrefWidth(CARD_WIDTH);
        card.setMaxWidth(CARD_WIDTH);
        return card;
    }

    /** 弹出无边框模态窗口新增/编辑模型；{@code editing} 为 null 表示新增。 */
    private void openModelDialog(ModelConfig editing) {
        GimDialog.Shell shell = GimDialog.shell(hostWindow(), I18n.t(editing == null
                ? "settings.model.addTitle" : "settings.model.editTitle"));

        if (editing == null) {
            clearModelForm();
        } else {
            loadModelToForm(editing);
        }

        GridPane form = buildModelForm();
        form.setPrefWidth(440);

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        Button save = new Button(I18n.t("common.save"));
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            if (commitModelForm()) {
                shell.stage().close();
            }
        });
        HBox btns = new HBox(8, cancel, save);
        btns.setAlignment(Pos.CENTER_RIGHT);

        shell.body().getChildren().addAll(form, btns);
        shell.showAndWait();
    }

    // ---- 默认模型页 ----

    private VBox buildDefaultPage() {
        VBox box = page(I18n.t("settings.menu.default"), I18n.t("settings.default.subtitle"));

        Button setDefault = new Button(I18n.t("settings.default.set"));
        setDefault.getStyleClass().add("btn-primary");
        setDefault.setOnAction(e -> handleSetDefault());

        List<Node> rows = List.of(
                settingRow(I18n.t("settings.default.provider"), null, defaultProviderCombo),
                settingRow(I18n.t("settings.default.model"), null, defaultModelCombo));
        HBox actions = new HBox(setDefault);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(6, 0, 0, 0));

        box.getChildren().addAll(groupCard(null, rows), actions);
        return box;
    }

    // ---- MCP 服务页 ----

    private VBox buildMcpPage() {
        mcpView = new McpView(mcpAdminService);
        return mcpView.getNode();
    }

    // ---- 工具权限页（撤销 HITL"总是允许"授权） ----

    private VBox buildSecurityPage() {
        VBox box = page(I18n.t("settings.menu.security"), I18n.t("settings.security.subtitle"));

        Label hint = new Label(I18n.t("settings.security.hint"));
        hint.getStyleClass().add("row-desc");
        hint.setWrapText(true);

        permissionListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ToolPermissionRule item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String scope = item.getRuleContent() == null || item.getRuleContent().isBlank()
                        ? I18n.t("settings.security.wholeTool") : item.getRuleContent();
                setText(item.getToolName() + " · " + scope + " · " + item.getDecision());
            }
        });

        ToolBar bar = new ToolBar(
                newButton(I18n.t("settings.security.revoke"), this::handleRevokePermission),
                newButton(I18n.t("settings.security.clear"), this::handleClearPermissions));

        box.getChildren().addAll(hint, bar, permissionListView);
        VBox.setVgrow(permissionListView, Priority.ALWAYS);
        return box;
    }

    // ---- 表单 ----

    private void initComboItems() {
        protocolCombo.setItems(FXCollections.observableArrayList(
                List.of(ApiProtocol.DASHSCOPE.name(), ApiProtocol.OPENAI_COMPATIBLE.name(),
                        ApiProtocol.ANTHROPIC_COMPATIBLE.name(), ApiProtocol.OLLAMA.name())));
        authCombo.setItems(FXCollections.observableArrayList(
                List.of(AuthMethod.X_API_KEY.name(), AuthMethod.BEARER_TOKEN.name())));
    }

    private GridPane buildProviderForm() {
        GridPane g = grid();
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
        GridPane g = grid();
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
        loading = true;
        providerListView.getSelectionModel().clearSelection();
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
        loading = false;
        statusLabel.setText(I18n.t("settings.status.newProvider"));
    }

    private void loadProviderToForm(ModelProviderConfig p) {
        loading = true;
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
        loading = false;
    }

    private boolean commitProviderForm() {
        if (providerCodeField.getText().isBlank()) {
            statusLabel.setText(I18n.t("settings.status.providerCodeEmpty"));
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
        statusLabel.setText(I18n.t("settings.status.savedProvider", p.getProviderCode()));
        reloadAll();
        return true;
    }

    private void handleDeleteProvider(ModelProviderConfig sel) {
        if (!GimDialog.confirm(hostWindow(), I18n.t("settings.menu.providers"),
                I18n.t("settings.provider.deleteConfirm", sel.getProviderCode()))) {
            return;
        }
        adminService.deleteProvider(sel.getProviderCode());
        statusLabel.setText(I18n.t("settings.status.deletedProvider", sel.getProviderCode()));
        reloadAll();
    }

    // ---- 模型操作 ----

    private void clearModelForm() {
        loading = true;
        modelListView.getSelectionModel().clearSelection();
        modelCodeField.clear();
        modelCodeField.setDisable(false);
        modelNameField.clear();
        priorityField.setText("100");
        modelEnabledCheck.setSelected(true);
        if (modelProviderCombo.getItems().isEmpty()) {
            refreshProviderCombos();
        }
        loading = false;
        statusLabel.setText(I18n.t("settings.status.newModel"));
    }

    private void loadModelToForm(ModelConfig m) {
        loading = true;
        modelProviderCombo.setValue(m.getProviderCode());
        modelCodeField.setText(m.getModelCode());
        modelCodeField.setDisable(true);
        modelNameField.setText(m.getModelName());
        priorityField.setText(String.valueOf(m.getPriority()));
        modelEnabledCheck.setSelected(EnabledStatus.isEnabled(m.getEnabled()));
        loading = false;
    }

    private boolean commitModelForm() {
        if (modelCodeField.getText().isBlank() || modelNameField.getText().isBlank()) {
            statusLabel.setText(I18n.t("settings.status.modelFieldsEmpty"));
            return false;
        }
        ModelConfig m = new ModelConfig();
        m.setProviderCode(modelProviderCombo.getValue());
        m.setModelCode(modelCodeField.getText().trim());
        m.setModelName(modelNameField.getText().trim());
        m.setPriority(parseInt(priorityField.getText(), 100));
        m.setEnabled(modelEnabledCheck.isSelected() ? EnabledStatus.ENABLED : EnabledStatus.DISABLED);
        m.setStatus("UNKNOWN");
        adminService.saveModel(m);
        statusLabel.setText(I18n.t("settings.status.savedModel", m.getModelCode()));
        reloadAll();
        return true;
    }

    private void handleDeleteModel(ModelConfig sel) {
        if (!GimDialog.confirm(hostWindow(), I18n.t("settings.menu.models"),
                I18n.t("settings.model.deleteConfirm", sel.getModelCode()))) {
            return;
        }
        adminService.deleteModel(sel.getModelCode());
        statusLabel.setText(I18n.t("settings.status.deletedModel", sel.getModelCode()));
        reloadAll();
    }

    // ---- 默认模型 ----

    private void handleSetDefault() {
        String provider = defaultProviderCombo.getValue();
        String model = defaultModelCombo.getValue();
        if (provider == null || model == null || model.isBlank()) {
            statusLabel.setText(I18n.t("settings.status.pickDefault"));
            return;
        }
        adminService.setDefault(provider, model);
        statusLabel.setText(I18n.t("settings.status.setDefault", provider, model));
    }

    // ---- 工具权限操作 ----

    private void handleRevokePermission() {
        ToolPermissionRule sel = permissionListView.getSelectionModel().getSelectedItem();
        if (sel == null) {
            statusLabel.setText(I18n.t("settings.status.pickRevoke"));
            return;
        }
        securityAdminService.delete(sel.getId());
        statusLabel.setText(I18n.t("settings.status.revoked", sel.getToolName()));
        reloadAll();
    }

    private void handleClearPermissions() {
        securityAdminService.clear();
        statusLabel.setText(I18n.t("settings.status.clearedPermission"));
        reloadAll();
    }

    // ---- 数据刷新 ----

    private void reloadAll() {
        loading = true;
        List<ModelProviderConfig> providers = adminService.listProviders();
        providerListView.setItems(FXCollections.observableArrayList(providers));
        refreshProviderCombos();
        renderProviderCards();
        renderModelCards();
        renderAgentCards();
        providerCodeField.setDisable(false);
        modelCodeField.setDisable(false);
        // 回填当前默认
        adminService.getDefaultProviderCode().ifPresent(p -> {
            defaultProviderCombo.setValue(p);
            loadDefaultModels(p);
        });
        adminService.getDefaultModelCode().ifPresent(defaultModelCombo::setValue);
        // MCP 由复用组件自管理刷新；仅刷新权限列表
        if (mcpView != null) {
            mcpView.reload();
        }
        permissionListView.setItems(FXCollections.observableArrayList(securityAdminService.list()));
        loading = false;
    }

    private void refreshProviderCombos() {
        List<String> codes = adminService.listProviders().stream()
                .map(ModelProviderConfig::getProviderCode).toList();
        String selModelProvider = modelProviderCombo.getValue();
        String selDefaultProvider = defaultProviderCombo.getValue();
        modelProviderCombo.setItems(FXCollections.observableArrayList(codes));
        defaultProviderCombo.setItems(FXCollections.observableArrayList(codes));
        if (selModelProvider == null && !codes.isEmpty()) {
            selModelProvider = codes.get(0);
        }
        modelProviderCombo.setValue(selModelProvider);
        defaultProviderCombo.setValue(selDefaultProvider);
        defaultProviderCombo.valueProperty().removeListener(defaultProviderListener);
        defaultProviderCombo.valueProperty().addListener(defaultProviderListener);
    }

    private final javafx.beans.value.ChangeListener<String> defaultProviderListener =
            (obs, o, n) -> {
                if (n != null) {
                    loadDefaultModels(n);
                }
            };

    private void loadDefaultModels(String providerCode) {
        List<String> codes = adminService.listModels(providerCode).stream()
                .map(ModelConfig::getModelCode).toList();
        defaultModelCombo.setItems(FXCollections.observableArrayList(codes));
    }

    private void reloadModelsForProvider(String providerCode) {
        modelListView.setItems(FXCollections.observableArrayList(adminService.listModels(providerCode)));
    }

    private void selectProvider(String providerCode) {
        for (int i = 0; i < providerListView.getItems().size(); i++) {
            if (providerListView.getItems().get(i).getProviderCode().equals(providerCode)) {
                providerListView.getSelectionModel().select(i);
                return;
            }
        }
    }

    private static Button newButton(String text, Runnable action) {
        Button b = new Button(text);
        b.setOnAction(e -> action.run());
        return b;
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.getStyleClass().add("form-card");
        g.setHgap(14);
        g.setVgap(10);
        return g;
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
