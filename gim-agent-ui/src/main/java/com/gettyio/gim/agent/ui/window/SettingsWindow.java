package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.core.model.ModelProbe;
import com.gettyio.gim.agent.domain.backup.DbBackupService;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.entity.McpServerConfig;
import com.gettyio.gim.agent.domain.entity.ModelConfig;
import com.gettyio.gim.agent.domain.entity.ModelProviderConfig;
import com.gettyio.gim.agent.domain.entity.PromptTemplate;
import com.gettyio.gim.agent.domain.entity.ToolPermissionRule;
import com.gettyio.gim.agent.domain.enums.ApiProtocol;
import com.gettyio.gim.agent.domain.enums.AuthMethod;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.enums.McpTransportType;
import com.gettyio.gim.agent.tools.guard.DangerousRule;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import com.gettyio.gim.agent.ui.control.ToggleSwitch;
import com.gettyio.gim.agent.ui.view.McpView;
import com.gettyio.gim.agent.ui.view.SkillsView;
import com.gettyio.gim.agent.ui.config.AgentAdminService;
import com.gettyio.gim.agent.ui.config.McpAdminService;
import com.gettyio.gim.agent.ui.config.PromptTemplateAdminService;
import com.gettyio.gim.agent.ui.config.ProviderAdminService;
import com.gettyio.gim.agent.ui.config.SecurityAdminService;
import com.gettyio.gim.agent.ui.export.ExportService;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.TextAlignment;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.awt.Desktop;
import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 设置窗口：左侧分类菜单（含搜索）+ 右侧内容页（对标 Qoder 设置风格）。
 * <p>分页：个人资料 / 外观（主题·字体·语言，接入 {@link UiTheme}/{@link I18n}）/
 * 记忆（只读查看 workspace/MEMORY.md）/ 通用（数据与路径）/ 智能体 /
 * 模型提供商 / 模型 / 默认模型 / 技能 / MCP 服务 / 安全（工具防护·文件防护·授权管理）。仅呈现有真实后端支撑的页面，不伪造无功能菜单项。
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

    @Autowired
    private DbBackupService dbBackupService;

    @Autowired
    private ExportService exportService;

    @Autowired
    private PromptTemplateAdminService promptTemplateAdminService;

    @Autowired
    private AppLifecycle appLifecycle;

    @Autowired
    private com.gettyio.gim.agent.ui.update.UpdateChecker updateChecker;

    @Autowired
    private com.gettyio.gim.agent.ui.desktop.GlobalHotkeyService globalHotkeyService;

    private final ListView<ModelProviderConfig> providerListView = new ListView<>();
    private final ListView<ModelConfig> modelListView = new ListView<>();
    /** 信息卡片固定宽度：不铺满整行；有内容时左对齐，空状态居中。 */
    private static final double CARD_WIDTH = 400;
    private final FlowPane providerCardsBox = cardsBox();
    private final FlowPane modelCardsBox = cardsBox();
    private final FlowPane agentCardsBox = cardsBox();
    private final FlowPane templateCardsBox = cardsBox();

    /**
     * 卡片流式容器：宽度够时横向并排，不够则换行；卡片按 {@link #CARD_WIDTH} 取自身尺寸，
     * 不被拉伸，行内顶对齐以免高低不一的卡片彼此错位。
     */
    private static FlowPane cardsBox() {
        FlowPane box = new FlowPane(12, 12);
        box.setAlignment(Pos.TOP_LEFT);
        box.setRowValignment(VPos.TOP);
        return box;
    }

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

    private final Label defaultModelInfoLabel = new Label("-");
    /** 通用页「最近备份」值标签：备份完成后就地刷新。 */
    private final Label lastBackupValueLabel = new Label("-");

    /** MCP 服务页复用组件（自包含）；与拓展页各自实例化，互不干扰场景图父子关系。 */
    private McpView mcpView;

    /** 技能页复用组件：与拓展页共用同一 Spring 单例，两处不同时展示，节点挂接互斥安全。 */
    @Autowired
    private SkillsView skillsView;

    /** 安全页动态容器：危险命令规则 / Shell 白名单 / 文件授权目录 / 授权管理，reloadAll 时重绘。 */
    private final VBox dangerousRulesBox = new VBox(8);
    private final VBox shellAllowBox = new VBox(0);
    private final VBox pathRootsBox = new VBox(0);
    private final VBox grantBox = new VBox(0);

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
    /** 本次打开要直达的设置菜单文案（如「模型」）；为空则默认选中第一项 */
    private String pendingMenuLabel;
    /** 模型表单本次弹窗里的连接测试结果；为空表示没测过。 */
    private ModelProbe.Result modelProbeResult;
    /** 正在编辑的模型原记录：未重新测试时沿用它已有的可用状态，不被抹成 UNKNOWN。 */
    private ModelConfig modelBaseline;
    /** 本次弹窗里的多模态测试结果；为空表示没测过，不影响可用性状态。 */
    private ModelProbe.Result modelVisionResult;

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
        showIn(shell, scene, null, onBack);
    }

    /** 同上；{@code selectMenuLabel} 非空时直达对应菜单项（找不到则回退第一项）。 */
    public void showIn(AppShell shell, Scene scene, String selectMenuLabel, Runnable onBack) {
        this.shell = shell;
        this.hostScene = scene;
        this.onBack = onBack;
        this.pendingMenuLabel = selectMenuLabel;
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
        shell.setContent(buildRoot());
        UiTheme.apply(hostScene);
        reloadAll();
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

        BorderPane centerPane = new BorderPane();
        centerPane.setCenter(scroller);

        // 右侧内容区对齐聊天页：白底圆角面板（.content-panel）浮于画布（.content-area）之上，
        // 右/下留 12px 空隙（.content-margin），圆角靠矩形裁剪（半径同 CSS 14）。
        StackPane contentPanel = new StackPane(centerPane);
        contentPanel.getStyleClass().add("content-panel");
        applyRoundedClip(contentPanel);
        StackPane contentMargin = new StackPane(contentPanel);
        contentMargin.getStyleClass().addAll("content-area", "content-margin");
        root.setCenter(contentMargin);
        return root;
    }

    /** 内容面板圆角裁剪：半径与 CSS .content-panel 的 background-radius(14) 一致（arc=2×半径）。 */
    private static void applyRoundedClip(Region node) {
        Rectangle clip = new Rectangle();
        clip.setArcWidth(28);
        clip.setArcHeight(28);
        clip.widthProperty().bind(node.widthProperty());
        clip.heightProperty().bind(node.heightProperty());
        node.setClip(clip);
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
        addMenu(extension, "\uE945", I18n.t("skills.title"), buildSkillsPage());
        addMenu(extension, "\uE73A", I18n.t("settings.menu.templates"), buildTemplatesPage());
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
        if (menuGroup.getToggles().isEmpty()) {
            return;
        }
        // 有直达目标（如模型菜单的「模型管理」→「模型」页）则先选它，消费一次后恢复默认行为
        String want = pendingMenuLabel;
        pendingMenuLabel = null;
        if (want != null && !want.isBlank()) {
            for (Toggle t : menuGroup.getToggles()) {
                if (t instanceof ToggleButton tb && want.equals(tb.getText())) {
                    tb.setSelected(true);
                    return;
                }
            }
        }
        ((ToggleButton) menuGroup.getToggles().get(0)).setSelected(true);
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
                valueLabel(updateChecker.currentVersion())));
        rows.add(settingRow(I18n.t("settings.key.dataDir"), null,
                valueLabel(String.valueOf(gimPaths.root()))));
        rows.add(settingRow(I18n.t("settings.profile.language"), null,
                valueLabel(I18n.current().nativeName())));
        rows.add(settingRow(I18n.t("settings.profile.theme"), null,
                valueLabel(I18n.t(UiTheme.mode() == UiTheme.Mode.DARK ? "menu.mode.dark" : "menu.mode.light"))));

        box.getChildren().addAll(head, groupCard(I18n.t("settings.group.about"), rows), updateCard());
        return box;
    }

    /** 更新卡片：服务器地址、每日静默检查开关、立即检查按钮。 */
    private VBox updateCard() {
        Button check = iconTextButton("\uE777", I18n.t("settings.update.check"), this::handleCheckUpdate);

        ToggleSwitch auto = new ToggleSwitch();
        auto.setSelected(updateChecker.isAutoCheckEnabled());
        auto.selectedProperty().addListener((obs, o, sel) -> updateChecker.setAutoCheckEnabled(sel));

        TextField serverField = new TextField(updateChecker.getServerBaseUrl());
        serverField.setPromptText(I18n.t("settings.update.server.prompt"));
        serverField.setMaxWidth(360);
        Runnable saveServer = () -> updateChecker.setServerBaseUrl(serverField.getText());
        serverField.setOnAction(e -> saveServer.run());
        serverField.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                saveServer.run();
            }
        });

        List<Node> rows = new ArrayList<>();
        rows.add(settingRow(I18n.t("settings.update.server"), I18n.t("settings.update.server.desc"), serverField));
        rows.add(settingRow(I18n.t("settings.update.auto"), I18n.t("settings.update.auto.desc"), auto));
        rows.add(settingRow(I18n.t("settings.update.check"), I18n.t("settings.update.check.desc"), check));
        return groupCard(I18n.t("settings.group.update"), rows);
    }

    /** 手动检查更新：向服务器查询新版，命中后下载并校验，再提示拉起安装。 */
    private void handleCheckUpdate() {
        Window owner = hostWindow();
        Thread t = new Thread(() -> {
            try {
                Optional<com.gettyio.gim.agent.ui.update.UpdateChecker.UpdateInfo> info = updateChecker.checkLatest();
                Platform.runLater(() -> {
                    if (info.isPresent()) {
                        if (info.get().forceUpdate()) {
                            updateChecker.beginForced(owner, info.get(), null);
                        } else {
                            confirmDownload(owner, info.get());
                        }
                    } else {
                        GimDialog.message(owner, I18n.t("settings.update.latest.title"), null,
                                I18n.t("settings.update.latest.body", updateChecker.currentVersion()));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> notify("settings.update.check",
                        I18n.t("settings.update.fail", String.valueOf(e.getMessage()))));
            }
        }, "gim-update-check");
        t.setDaemon(true);
        t.start();
    }

    /** 发现新版：询问是否下载，确认后后台下载并校验，完成再询问是否安装。 */
    private void confirmDownload(Window owner, com.gettyio.gim.agent.ui.update.UpdateChecker.UpdateInfo info) {
        int go = GimDialog.choose(owner, I18n.t("settings.update.found.title"), null,
                I18n.t("settings.update.found.body", updateChecker.currentVersion(), info.version()),
                I18n.t("settings.update.download"), I18n.t("common.cancel"));
        if (go != 0) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                java.nio.file.Path file = updateChecker.download(info, ratio -> { });
                Platform.runLater(() -> confirmInstall(owner, file));
            } catch (Exception e) {
                Platform.runLater(() -> notify("settings.update.check",
                        I18n.t("settings.update.downloadFail", String.valueOf(e.getMessage()))));
            }
        }, "gim-update-download");
        t.setDaemon(true);
        t.start();
    }

    /** 下载完成：询问是否立即拉起安装。 */
    private void confirmInstall(Window owner, java.nio.file.Path file) {
        int go = GimDialog.choose(owner, I18n.t("settings.update.install.title"), null,
                I18n.t("settings.update.install.body", file.getFileName().toString()),
                I18n.t("settings.update.install"), I18n.t("common.cancel"));
        if (go == 0) {
            boolean ok = updateChecker.install(file);
            if (!ok) {
                notify("settings.update.check", I18n.t("settings.update.installFail"));
            }
        }
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
        rows.add(infoRow(I18n.t("settings.key.backupsDir"), gimPaths.backupsDir()));
        rows.add(infoRow(I18n.t("settings.key.db"), gimPaths.dbFile()));
        lastBackupValueLabel.getStyleClass().add("row-value");
        rows.add(settingRow(I18n.t("settings.backup.lastAt"), null, lastBackupValueLabel));
        box.getChildren().add(groupCard(I18n.t("settings.group.paths"), rows));
        box.getChildren().add(behaviorCard());
        box.getChildren().add(dataMaintenanceCard());
        refreshLastBackupRow();
        return box;
    }

    /** 启动与关闭卡片：关闭行为（退出/最小化到托盘）与开机自启（仅打包 exe 可用）。 */
    private VBox behaviorCard() {
        String[] opts = {I18n.t("settings.close.tray"), I18n.t("settings.close.exit")};
        ComboBox<String> closeCombo = pillCombo(opts);
        boolean exitAction = AppLifecycle.CLOSE_EXIT.equals(
                appLifecycle.readCloseAction().orElse(AppLifecycle.CLOSE_TRAY));
        closeCombo.setValue(opts[exitAction ? 1 : 0]);
        closeCombo.setOnAction(e -> {
            boolean exit = closeCombo.getSelectionModel().getSelectedIndex() == 1;
            appLifecycle.setCloseAction(exit ? AppLifecycle.CLOSE_EXIT : AppLifecycle.CLOSE_TRAY);
        });

        boolean manageable = appLifecycle.canManageAutostart();
        Node autoControl;
        if (manageable) {
            ToggleSwitch autostart = new ToggleSwitch();
            autostart.setSelected(appLifecycle.isAutostartEnabled());
            autostart.selectedProperty().addListener((obs, o, sel) -> {
                if (!appLifecycle.setAutostart(sel)) {
                    autostart.setSelected(!sel);
                    notify("settings.autostart", I18n.t("settings.status.autostartFail"));
                }
            });
            autoControl = autostart;
        } else {
            Label na = new Label(I18n.t("settings.autostart.unavailable"));
            na.getStyleClass().add("row-value");
            autoControl = na;
        }

        List<Node> rows = new ArrayList<>();
        rows.add(settingRow(I18n.t("settings.close.action"), I18n.t("settings.close.action.desc"), closeCombo));
        rows.add(settingRow(I18n.t("settings.autostart"), I18n.t("settings.autostart.desc"), autoControl));
        rows.add(settingRow(I18n.t("settings.hotkey"), I18n.t("settings.hotkey.desc"), hotkeyControl()));
        return groupCard(I18n.t("settings.group.startupClose"), rows);
    }

    /**
     * 全局热键开关控件：仅 Windows 可用（与 {@link com.gettyio.gim.agent.ui.desktop.GlobalHotkeyService#isSupported()} 同口径）；
     * 切换即写 {@code app.hotkey.enabled} 并即时注册/反注册。不支持平台显示不可用文案。
     */
    private Node hotkeyControl() {
        if (!globalHotkeyService.isSupported()) {
            Label na = new Label(I18n.t("settings.hotkey.unavailable"));
            na.getStyleClass().add("row-value");
            return na;
        }
        ToggleSwitch hotkey = new ToggleSwitch();
        hotkey.setSelected(globalHotkeyService.isEnabled());
        hotkey.selectedProperty().addListener((obs, o, sel) -> globalHotkeyService.setEnabled(sel));
        return hotkey;
    }

    /** 数据维护卡片：立即备份 / 导出会话 / 导出全库 / 打开备份目录 / 打开数据目录。 */
    private VBox dataMaintenanceCard() {
        Label gt = new Label(I18n.t("settings.group.maintenance"));
        gt.getStyleClass().add("settings-group-title");

        Button backup = iconTextButton("\uE7B5", I18n.t("settings.backup.now"), this::handleBackupNow);
        Button exportSession = iconTextButton("\uE8A5", I18n.t("settings.export.session"), this::handleExportSession);
        Button exportFull = iconTextButton("\uE8A5", I18n.t("settings.export.full"), this::handleExportFull);
        Button openBackup = iconTextButton("\uE838", I18n.t("settings.data.openBackup"),
                () -> revealDir(gimPaths.backupsDir()));
        Button openRoot = iconTextButton("\uE838", I18n.t("settings.data.openRoot"),
                () -> revealDir(gimPaths.root()));

        Label hint = new Label(I18n.t("settings.backup.hint"));
        hint.getStyleClass().add("row-desc");
        hint.setWrapText(true);

        FlowPane buttons = new FlowPane(8, 8, backup, exportSession, exportFull, openBackup, openRoot);
        VBox card = new VBox(8, hint, buttons);
        card.getStyleClass().add("settings-group");
        return new VBox(6, gt, card);
    }

    private String formatLastBackup() {
        return dbBackupService.lastBackupAt()
                .map(i -> i.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .orElse(I18n.t("settings.backup.none"));
    }

    /** 刷新「最近备份」行文本（通用页重建与备份完成后调用）。 */
    private void refreshLastBackupRow() {
        lastBackupValueLabel.setText(formatLastBackup());
    }

    /** 立即备份（后台线程执行 VACUUM，完成后刷新本页并弹框告知）。 */
    private void handleBackupNow() {
        runInBackground("settings.backup.now",
                () -> dbBackupService.backupNow().map(p -> I18n.t("settings.backup.done", p.getFileName())),
                this::refreshLastBackupRow);
    }

    /** 导出单个会话为 Markdown：先选会话，再选保存路径。 */
    private void handleExportSession() {
        List<ChatSession> sessions = exportServiceAllSessions();
        if (sessions.isEmpty()) {
            notify("settings.export.session", I18n.t("settings.export.noSession"));
            return;
        }
        String[] labels = sessions.stream()
                .map(s -> {
                    String t = s.getTitle() == null || s.getTitle().isBlank() ? s.getSessionId() : s.getTitle();
                    int n = s.getMessageCount() == null ? 0 : s.getMessageCount();
                    return t + "  （" + n + "）";
                })
                .toArray(String[]::new);
        int idx = GimDialog.choose(hostWindow(), I18n.t("settings.export.session"), null,
                I18n.t("settings.export.pickSession"), labels);
        if (idx < 0 || idx >= sessions.size()) {
            return;
        }
        ChatSession chosen = sessions.get(idx);
        String base = chosen.getTitle() == null || chosen.getTitle().isBlank() ? chosen.getSessionId() : chosen.getTitle();
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("settings.export.session"));
        chooser.setInitialFileName(exportService.suggestedFileName(base, ".md"));
        FileChooser.ExtensionFilter md = new FileChooser.ExtensionFilter("Markdown (*.md)", "*.md");
        FileChooser.ExtensionFilter pdf = new FileChooser.ExtensionFilter("PDF (*.pdf)", "*.pdf");
        FileChooser.ExtensionFilter docx = new FileChooser.ExtensionFilter("DOCX (*.docx)", "*.docx");
        chooser.getExtensionFilters().addAll(md, pdf, docx);
        File file = chooser.showSaveDialog(hostWindow());
        if (file == null) {
            return;
        }
        String sessionId = chosen.getSessionId();
        FileChooser.ExtensionFilter sel = chooser.getSelectedExtensionFilter();
        Path target = withExtension(file.toPath(), sel == pdf ? ".pdf" : sel == docx ? ".docx" : ".md");
        boolean asPdf = sel == pdf;
        boolean asDocx = sel == docx;
        runInBackground("settings.export.session",
                () -> Optional.of(I18n.t("settings.export.done",
                        (asPdf ? exportService.exportSessionPdf(sessionId, target)
                                : asDocx ? exportService.exportSessionDocx(sessionId, target)
                                        : exportService.exportSessionMarkdown(sessionId, target)).getFileName())),
                null);
    }

    /** 用户未按选中格式补后缀时自动补上，保证扩展名与导出内容一致。 */
    private static Path withExtension(Path p, String ext) {
        String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(ext) ? p : Path.of(p.toString() + ext);
    }

    /** 导出全库为 JSON。 */
    private void handleExportFull() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("settings.export.full"));
        chooser.setInitialFileName(exportService.suggestedFileName("gim-agent-export", ".json"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        File file = chooser.showSaveDialog(hostWindow());
        if (file == null) {
            return;
        }
        Path target = file.toPath();
        runInBackground("settings.export.full",
                () -> Optional.of(I18n.t("settings.export.done",
                        exportService.exportFullJson(target).getFileName())),
                null);
    }

    /** 从库中取全部会话（导出选择用）。 */
    private List<ChatSession> exportServiceAllSessions() {
        return exportService.findSessions();
    }

    /** 用系统文件管理器打开目录；不支持时回落状态栏提示。 */
    private void revealDir(Path dir) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir.toFile());
            } else {
                notify("settings.menu.general", I18n.t("settings.data.openFail", "Desktop unsupported"));
            }
        } catch (Exception e) {
            notify("settings.menu.general", I18n.t("settings.data.openFail", e.getMessage()));
        }
    }

    /**
     * 后台线程执行耗时操作：完成或失败后以 {@code titleKey} 为标题弹框告知结果；
     * {@code onSuccess} 可空，非空时在 FX 线程回调（如刷新页面）。
     */
    private void runInBackground(String titleKey, java.util.function.Supplier<Optional<String>> work, Runnable onSuccess) {
        Thread t = new Thread(() -> {
            String result;
            try {
                result = work.get().orElse(null);
            } catch (RuntimeException e) {
                result = I18n.t("settings.data.fail", String.valueOf(e.getMessage()));
            }
            final String msg = result;
            Platform.runLater(() -> {
                if (msg != null && !msg.isBlank()) {
                    notify(titleKey, msg);
                }
                if (onSuccess != null) {
                    onSuccess.run();
                }
            });
        }, "gim-data-task");
        t.setDaemon(true);
        t.start();
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
                notify("settings.menu.memory", I18n.t("settings.memory.revealFail", "Desktop unsupported"));
            }
        } catch (Exception e) {
            notify("settings.menu.memory", I18n.t("settings.memory.revealFail", e.getMessage()));
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

        Button edit = iconTextButton("\uE70F", I18n.t("common.edit"), () -> openAgentDialog(a));
        edit.getStyleClass().add("link-btn");
        HBox head;
        if (builtIn) {
            // 内置默认智能体不可删：它是所有未指定智能体的会话/任务的回退目标
            head = cardHeadRow(name, badge, edit);
        } else {
            Button del = iconTextButton("\uE74D", I18n.t("common.delete"), () -> handleDeleteAgent(a));
            del.getStyleClass().addAll("link-btn", "link-btn-danger");
            head = cardHeadRow(name, badge, edit, del);
        }

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
                kvLine(I18n.t("settings.agent.project"), a.getProjectPath()),
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
        AgentDialog.edit(hostWindow(), agentAdminService, editing).ifPresent(saved -> reloadAll());
    }

    private void handleDeleteAgent(AgentDefinition sel) {
        String code = sel.getAgentCode();
        if (agentAdminService.isDefault(code)) {
            notify("settings.menu.agents", I18n.t("settings.status.defaultAgentUndeletable"));
            return;
        }
        int sessions = agentAdminService.sessionUsage(code);
        int tasks = agentAdminService.taskUsage(code);
        if (sessions > 0 || tasks > 0) {
            notify("settings.menu.agents", I18n.t("settings.status.agentInUse", code, str(sessions), str(tasks)));
            return;
        }
        if (!GimDialog.confirm(hostWindow(), I18n.t("settings.menu.agents"),
                I18n.t("settings.agent.deleteConfirm", sel.getAgentName()))) {
            return;
        }
        agentAdminService.delete(code);
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

        Button edit = iconTextButton("\uE70F", I18n.t("common.edit"), () -> openProviderDialog(p));
        edit.getStyleClass().add("link-btn");
        Button del = iconTextButton("\uE74D", I18n.t("common.delete"), () -> handleDeleteProvider(p));
        del.getStyleClass().addAll("link-btn", "link-btn-danger");
        HBox head = cardHeadRow(name, badge, edit, del);

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

    /**
     * 卡片首行：左侧名称 + 状态徽标，右侧编辑/删除按钮。
     * <p>宽度不够时 HBox 会把所有可压缩的子节点一起往最小宽度压，所以默认情况下名称过长会
     * 连带把徽标与按钮的文字挤成省略号。这里只让名称可压缩（过长则省略号截断，并挂 tooltip
     * 便于看全名），其余节点的最小宽度钉在首选宽度上，保证操作入口始终完整可见。</p>
     */
    private static HBox cardHeadRow(Label name, Label badge, Button... actions) {
        name.setMinWidth(0);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setTooltip(new Tooltip(name.getText()));
        badge.setMinWidth(Region.USE_PREF_SIZE);

        HBox titleRow = new HBox(10, name, badge);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.setMaxWidth(Double.MAX_VALUE);

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        HBox head = new HBox();
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(titleRow, sp);
        for (Button action : actions) {
            action.setMinWidth(Region.USE_PREF_SIZE);
            head.getChildren().add(action);
        }
        return head;
    }

    /** 宿主窗口：作为无边框对话框的 owner（窗口模态并居中）。 */
    private Window hostWindow() {
        return hostScene == null ? null : hostScene.getWindow();
    }

    /** 即时反馈：以统一对话框呈现需要用户知晓的结果/错误（不驻留，避免跨页面残留）。 */
    private void notify(String titleKey, String message) {
        GimDialog.message(hostWindow(), I18n.t(titleKey), null, message);
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

        Button edit = iconTextButton("\uE70F", I18n.t("common.edit"), () -> openModelDialog(m));
        edit.getStyleClass().add("link-btn");
        Button del = iconTextButton("\uE74D", I18n.t("common.delete"), () -> handleDeleteModel(m));
        del.getStyleClass().addAll("link-btn", "link-btn-danger");
        HBox head = cardHeadRow(name, badge, edit, del);

        VBox card = new VBox(8, head,
                kvLine(I18n.t("settings.model.code"), m.getModelCode()),
                kvLine(I18n.t("settings.model.provider"), m.getProviderCode()),
                kvLine(I18n.t("settings.model.priority"), String.valueOf(m.getPriority())),
                kvIconLine(I18n.t("common.status"), statusIconValue(m.getStatus())),
                kvIconLine(I18n.t("settings.model.multimodal"), visionIconValue(m.getSupportsVision())),
                kvLine(I18n.t("settings.model.lastTest"), lastTestText(m)));
        card.getStyleClass().add("provider-card");
        card.setPrefWidth(CARD_WIDTH);
        card.setMaxWidth(CARD_WIDTH);
        return card;
    }

    /** 图标行：与 kvLine 同构，但值是一个富节点（图标 + 文字）。 */
    private HBox kvIconLine(String key, Node value) {
        Label k = new Label(key);
        k.getStyleClass().add("provider-card-key");
        HBox h = new HBox(12, k, value);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** 构造“Segoe 图标 + 主题色 + 说明文字”的小行值。 */
    private HBox iconTextValue(String glyph, String colorClass, String text) {
        Label ic = new Label(glyph);
        ic.getStyleClass().addAll("icon", "card-status-icon", colorClass);
        Label tx = new Label(text);
        tx.getStyleClass().add("provider-card-val");
        HBox h = new HBox(6, ic, tx);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** 连通状态行：可用=绿勾、不可用=红叉、未测试=灰问号。 */
    private HBox statusIconValue(String status) {
        if (ProviderAdminService.MODEL_STATUS_VALID.equalsIgnoreCase(status)) {
            return iconTextValue("\uE73E", "status-ok", I18n.t("settings.model.status.valid"));
        }
        if (ProviderAdminService.MODEL_STATUS_INVALID.equalsIgnoreCase(status)) {
            return iconTextValue("\uE711", "status-bad", I18n.t("settings.model.status.invalid"));
        }
        return iconTextValue("\uE897", "status-unknown", I18n.t("settings.model.status.unknown"));
    }

    /** 多模态行：支持=蓝图片、不支持=红图片、未测试=灰图片。 */
    private HBox visionIconValue(Integer supportsVision) {
        if (supportsVision == null) {
            return iconTextValue("\uE8B9", "status-unknown", I18n.t("settings.model.vision.unknown"));
        }
        if (supportsVision == 1) {
            return iconTextValue("\uE8B9", "status-vision", I18n.t("settings.model.vision.supported"));
        }
        return iconTextValue("\uE8B9", "status-bad", I18n.t("settings.model.vision.unsupported"));
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

    private VBox buildDefaultPage() {
        VBox box = page(I18n.t("settings.menu.default"), I18n.t("settings.default.subtitle"));

        Button changeBtn = new Button(I18n.t("settings.default.change"));
        changeBtn.getStyleClass().add("btn-primary");
        changeBtn.setOnAction(e -> openDefaultModelPicker());

        defaultModelInfoLabel.getStyleClass().add("row-value");

        List<Node> rows = List.of(
                settingRow(I18n.t("settings.default.current"), null, defaultModelInfoLabel));
        HBox actions = new HBox(changeBtn);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(6, 0, 0, 0));

        box.getChildren().addAll(groupCard(null, rows), actions);
        return box;
    }

    /** 弹窗选择默认模型：列出所有已启用模型，可勾选只看多模态，选中后确定即设为默认。 */
    private void openDefaultModelPicker() {
        GimDialog.Shell shell = GimDialog.shell(hostWindow(), I18n.t("settings.default.pickTitle"));

        List<ModelConfig> models = adminService.listModels().stream()
                .filter(m -> EnabledStatus.isEnabled(m.getEnabled()))
                .toList();
        if (models.isEmpty()) {
            shell.stage().close();
            notify("settings.menu.default", I18n.t("settings.status.noEnabledModel"));
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

    // ---- 技能页 ----

    /** 复用拓展页的技能整页；设置内显示一级标题（拓展 Tab 内被隐去）。 */
    private VBox buildSkillsPage() {
        skillsView.showHeaderTitle(true);
        return skillsView.getNode();
    }

    // ---- MCP 服务页 ----

    private VBox buildMcpPage() {
        mcpView = new McpView(mcpAdminService);
        return mcpView.getNode();
    }

    // ---- 提示词模板页 ----

    private VBox buildTemplatesPage() {
        VBox box = new VBox();
        box.getStyleClass().add("settings-page");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMaxHeight(Double.MAX_VALUE);

        VBox headText = new VBox(4);
        Label title = new Label(I18n.t("settings.menu.templates"));
        title.getStyleClass().add("page-title");
        Label subtitle = new Label(I18n.t("settings.templates.subtitle"));
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.setWrapText(true);
        headText.getChildren().addAll(title, subtitle);

        Button addBtn = new Button(I18n.t("settings.template.add"));
        addBtn.getStyleClass().add("btn-primary");
        Label addIcon = new Label("\uE710");
        addIcon.getStyleClass().add("icon");
        addBtn.setGraphic(addIcon);
        addBtn.setOnAction(e -> openTemplateDialog(null));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(headText, spacer, addBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        templateCardsBox.getStyleClass().add("provider-cards");
        VBox.setVgrow(templateCardsBox, Priority.ALWAYS);
        box.getChildren().addAll(header, templateCardsBox);
        return box;
    }

    /** 依据库中数据重绘模板信息卡片。 */
    private void renderTemplateCards() {
        templateCardsBox.getChildren().clear();
        List<PromptTemplate> templates = promptTemplateAdminService.list();
        if (templates.isEmpty()) {
            templateCardsBox.setAlignment(Pos.CENTER);
            templateCardsBox.getChildren().add(templateEmptyState());
            return;
        }
        templateCardsBox.setAlignment(Pos.TOP_LEFT);
        for (PromptTemplate t : templates) {
            templateCardsBox.getChildren().add(templateCard(t));
        }
    }

    private VBox templateEmptyState() {
        VBox box = new VBox();
        box.getStyleClass().add("empty-state");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFillWidth(false);
        Label icon = new Label("\uE73A");
        icon.getStyleClass().add("empty-icon");
        Label t = new Label(I18n.t("settings.template.empty"));
        t.getStyleClass().add("empty-title");
        t.setTextAlignment(TextAlignment.CENTER);
        Label d = new Label(I18n.t("settings.template.emptyDesc"));
        d.getStyleClass().add("empty-desc");
        d.setWrapText(true);
        d.setTextAlignment(TextAlignment.CENTER);
        d.setMaxWidth(460);
        box.getChildren().addAll(icon, t, d);
        return box;
    }

    private VBox templateCard(PromptTemplate t) {
        boolean enabled = EnabledStatus.isEnabled(t.getEnabled());
        Label name = new Label(t.getTemplateName() == null || t.getTemplateName().isBlank()
                ? t.getTemplateCode() : t.getTemplateName());
        name.getStyleClass().add("provider-card-title");
        Label badge = new Label(I18n.t(enabled ? "settings.template.enabled" : "settings.template.disabled"));
        badge.getStyleClass().addAll("badge", enabled ? "badge-green" : "badge-gray");

        ToggleSwitch toggle = new ToggleSwitch();
        toggle.setSelected(enabled);
        toggle.setMinWidth(Region.USE_PREF_SIZE);
        toggle.selectedProperty().addListener((obs, o, sel) -> {
            promptTemplateAdminService.setEnabled(t.getTemplateCode(), sel);
            renderTemplateCards();
        });

        Button edit = iconTextButton("\uE70F", I18n.t("common.edit"), () -> openTemplateDialog(t));
        edit.getStyleClass().add("link-btn");
        Button del = iconTextButton("\uE74D", I18n.t("common.delete"), () -> handleDeleteTemplate(t));
        del.getStyleClass().addAll("link-btn", "link-btn-danger");
        // 卡片首行：名称可压缩 + 徽标 + 操作区（启停开关 + 编辑 + 删除）钉住首选宽度
        name.setMinWidth(0);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setTooltip(new Tooltip(name.getText()));
        badge.setMinWidth(Region.USE_PREF_SIZE);
        HBox titleRow = new HBox(10, name, badge);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.setMaxWidth(Double.MAX_VALUE);
        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        HBox head = new HBox();
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(titleRow, headSpacer, toggle, edit, del);
        for (Node action : List.of(toggle, edit, del)) {
            if (action instanceof Region r) {
                r.setMinWidth(Region.USE_PREF_SIZE);
            }
        }

        VBox card = new VBox(8, head,
                kvLine(I18n.t("settings.template.desc"), t.getDescription()),
                kvLine(I18n.t("settings.template.content"), preview(t.getContent())));
        card.getStyleClass().add("provider-card");
        card.setPrefWidth(CARD_WIDTH);
        card.setMaxWidth(CARD_WIDTH);
        return card;
    }

    /** 弹模板表单：名称 / 描述 / 内容；保存写库后刷列表。{@code editing} 为 null 表示新增。 */
    private void openTemplateDialog(PromptTemplate editing) {
        GimDialog.Shell shell = GimDialog.shell(hostWindow(),
                I18n.t(editing == null ? "settings.template.addTitle" : "settings.template.editTitle"));

        TextField nameField = new TextField(editing == null ? "" : editing.getTemplateName());
        nameField.setPromptText(I18n.t("settings.template.namePrompt"));
        nameField.setMaxWidth(Double.MAX_VALUE);
        TextField descField = new TextField(editing == null ? "" : editing.getDescription());
        descField.setPromptText(I18n.t("settings.template.descPrompt"));
        descField.setMaxWidth(Double.MAX_VALUE);
        TextArea contentArea = new TextArea(editing == null ? "" : editing.getContent());
        contentArea.setPromptText(I18n.t("settings.template.contentPrompt"));
        contentArea.setPrefRowCount(8);
        contentArea.setWrapText(true);
        contentArea.setMaxWidth(Double.MAX_VALUE);

        VBox form = new VBox(10);
        form.setPrefWidth(460);
        form.getChildren().addAll(
                labeledField(I18n.t("settings.template.name"), nameField),
                labeledField(I18n.t("settings.template.desc"), descField),
                labeledField(I18n.t("settings.template.content"), contentArea));

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        Button save = new Button(I18n.t("common.save"));
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            String content = contentArea.getText() == null ? "" : contentArea.getText().trim();
            if (name.isEmpty() || content.isEmpty()) {
                notify("settings.menu.templates", I18n.t("settings.status.templateRequired"));
                return;
            }
            PromptTemplate t = editing == null ? new PromptTemplate() : editing;
            t.setTemplateName(name);
            t.setDescription(descField.getText() == null ? null : descField.getText().trim());
            t.setContent(content);
            if (editing == null) {
                t.setEnabled(1);
            }
            promptTemplateAdminService.save(t);
            renderTemplateCards();
            shell.stage().close();
        });
        HBox btns = new HBox(8, cancel, save);
        btns.getStyleClass().add("dialog-actions");
        btns.setAlignment(Pos.CENTER_RIGHT);

        shell.body().getChildren().addAll(form, btns);
        shell.showAndWait();
    }

    private void handleDeleteTemplate(PromptTemplate t) {
        if (!GimDialog.confirm(hostWindow(), I18n.t("settings.menu.templates"),
                I18n.t("settings.template.deleteConfirm", t.getTemplateName()))) {
            return;
        }
        promptTemplateAdminService.delete(t.getTemplateCode());
        renderTemplateCards();
    }

    // ---- 安全页（工具防护 / 文件防护 / 授权管理 三 Tab） ----

    private VBox buildSecurityPage() {
        VBox box = page(I18n.t("settings.menu.security"), I18n.t("settings.security.subtitle"));

        ToggleGroup subGroup = new ToggleGroup();
        HBox tabBar = new HBox(4);
        tabBar.getStyleClass().add("settings-subtabs");
        StackPane subContent = new StackPane();
        subContent.getStyleClass().add("settings-subtab-content");

        addSubTab(subGroup, tabBar, subContent, I18n.t("settings.security.tab.tools"), buildToolGuardTab());
        addSubTab(subGroup, tabBar, subContent, I18n.t("settings.security.tab.file"), buildFileGuardTab());
        addSubTab(subGroup, tabBar, subContent, I18n.t("settings.security.tab.web"), buildWebTab());
        addSubTab(subGroup, tabBar, subContent, I18n.t("settings.security.tab.grant"), buildGrantTab());
        if (!subGroup.getToggles().isEmpty()) {
            ((ToggleButton) subGroup.getToggles().get(0)).setSelected(true);
        }

        box.getChildren().addAll(tabBar, subContent);
        return box;
    }

    /** 页内子 Tab：选中即把对应面板换进内容区（自绘头部，避免 TabPane 在 ScrollPane 内的高度塌陷）。 */
    private void addSubTab(ToggleGroup group, HBox bar, StackPane content, String label, Node pane) {
        ToggleButton tab = new ToggleButton(label);
        tab.setToggleGroup(group);
        tab.getStyleClass().add("settings-subtab");
        tab.setFocusTraversable(false);
        tab.selectedProperty().addListener((obs, o, sel) -> {
            if (sel) {
                content.getChildren().setAll(pane);
            }
        });
        bar.getChildren().add(tab);
    }

    /** 工具防护 Tab：审批档位默认 + 危险命令黑名单 + Shell 程序白名单。 */
    private VBox buildToolGuardTab() {
        VBox tab = new VBox(16);
        tab.getStyleClass().add("settings-subtab-page");
        tab.setMaxWidth(Double.MAX_VALUE);

        String[] opts = {I18n.t("approval.ask"), I18n.t("approval.auto"), I18n.t("approval.readonly")};
        ComboBox<String> approvalCombo = pillCombo(opts);
        approvalCombo.setValue(opts[approvalDefaultIndex(securityAdminService.getApprovalDefault())]);
        approvalCombo.setOnAction(e -> {
            int i = Math.max(0, approvalCombo.getSelectionModel().getSelectedIndex());
            securityAdminService.setApprovalDefault(i == 1 ? SecurityAdminService.APPROVAL_AUTO
                    : i == 2 ? SecurityAdminService.APPROVAL_READONLY : SecurityAdminService.APPROVAL_ASK);
        });
        tab.getChildren().add(groupCard(I18n.t("settings.security.approval.group"),
                List.of(settingRow(I18n.t("settings.security.approval.default"),
                        I18n.t("settings.security.approval.default.desc"), approvalCombo))));

        Button addRule = newButton(I18n.t("settings.security.rule.add"), this::handleAddDangerousRule);
        addRule.getStyleClass().add("btn-primary");
        Button resetRules = newButton(I18n.t("settings.security.rule.reset"), this::handleResetDangerousRules);
        resetRules.getStyleClass().add("link-btn");
        renderDangerousRules();
        tab.getChildren().add(sectionBlock(I18n.t("settings.security.dangerous.group"),
                I18n.t("settings.security.dangerous.desc"), List.of(resetRules, addRule), dangerousRulesBox));

        Button addProg = newButton(I18n.t("settings.security.shell.add"), this::handleAddShellProgram);
        addProg.getStyleClass().add("btn-primary");
        renderShellAllowlist();
        tab.getChildren().add(sectionBlockCentered(I18n.t("settings.security.shell.group"),
                I18n.t("settings.security.shell.desc"), List.of(addProg), shellAllowBox));
        return tab;
    }

    /** 文件防护 Tab：文件授权目录（越界仅提示，写操作仍逐次确认）。 */
    private VBox buildFileGuardTab() {
        VBox tab = new VBox(16);
        tab.getStyleClass().add("settings-subtab-page");
        tab.setMaxWidth(Double.MAX_VALUE);

        Button addDir = newButton(I18n.t("settings.security.path.add"), this::handleAddPathRoot);
        addDir.getStyleClass().add("btn-primary");
        renderPathRoots();
        tab.getChildren().add(sectionBlockCentered(I18n.t("settings.security.path.group"),
                I18n.t("settings.security.path.desc"), List.of(addDir), pathRootsBox));

        Label builtin = new Label(I18n.t("settings.security.path.builtin",
                String.valueOf(gimPaths.workspaceDir())));
        builtin.getStyleClass().add("row-desc");
        builtin.setWrapText(true);
        tab.getChildren().add(builtin);
        return tab;
    }

    /** 联网 Tab：联网搜索提供方与 API Key（加密存储），供 web_search / web_fetch 工具实时取用。 */
    private VBox buildWebTab() {
        VBox tab = new VBox(16);
        tab.getStyleClass().add("settings-subtab-page");
        tab.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> providerCombo = pillCombo("Tavily");
        providerCombo.setValue(I18n.t("settings.security.web.provider.tavily"));
        providerCombo.setOnAction(e -> securityAdminService.setWebSearchProvider(SecurityAdminService.WEB_PROVIDER_TAVILY));

        PasswordField keyField = new PasswordField();
        keyField.setText(securityAdminService.getWebSearchApiKeyPlain());
        keyField.setPromptText(I18n.t("settings.security.web.key.prompt"));
        keyField.setMaxWidth(360);
        Runnable saveKey = () -> securityAdminService.setWebSearchApiKey(keyField.getText());
        keyField.setOnAction(e -> saveKey.run());
        keyField.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                saveKey.run();
            }
        });

        tab.getChildren().add(groupCard(I18n.t("settings.security.web.group"), List.of(
                settingRow(I18n.t("settings.security.web.provider"),
                        I18n.t("settings.security.web.provider.desc"), providerCombo),
                settingRow(I18n.t("settings.security.web.key"),
                        I18n.t("settings.security.web.key.desc"), keyField))));

        Label hint = new Label(I18n.t("settings.security.web.hint"));
        hint.getStyleClass().add("row-desc");
        hint.setWrapText(true);
        tab.getChildren().add(hint);
        return tab;
    }

    /** 授权管理 Tab：逐条内联撤销 HITL「总是允许」授权，另有整体「全部撤销」。 */
    private VBox buildGrantTab() {
        VBox tab = new VBox(16);
        tab.getStyleClass().add("settings-subtab-page");
        tab.setMaxWidth(Double.MAX_VALUE);

        Button clearAll = newButton(I18n.t("settings.security.clear"), this::handleClearPermissions);
        clearAll.getStyleClass().add("link-btn");
        renderGrants();
        tab.getChildren().add(sectionBlockCentered(I18n.t("settings.security.grant.group"),
                I18n.t("settings.security.hint"), List.of(clearAll), grantBox));
        return tab;
    }

    // ---- 安全页渲染 ----

    /** 危险命令黑名单：按 category 分组，逐条带严重程度徽标、描述/正则、启停开关与删除。 */
    private void renderDangerousRules() {
        dangerousRulesBox.getChildren().clear();
        List<DangerousRule> rules = securityAdminService.listDangerousRules();
        if (rules.isEmpty()) {
            dangerousRulesBox.getChildren().add(emptyHint(I18n.t("settings.security.rule.empty")));
            return;
        }
        Map<String, List<DangerousRule>> groups = new LinkedHashMap<>();
        for (DangerousRule r : rules) {
            String cat = r.category() == null || r.category().isBlank() ? "-" : r.category();
            groups.computeIfAbsent(cat, k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<DangerousRule>> g : groups.entrySet()) {
            Label gt = new Label(g.getKey() + "  " + g.getValue().size() + " 条");
            gt.getStyleClass().add("dangerous-category");
            VBox card = new VBox();
            card.getStyleClass().add("settings-group");
            List<DangerousRule> rs = g.getValue();
            for (int i = 0; i < rs.size(); i++) {
                HBox row = dangerousRuleRow(rs.get(i));
                if (i > 0) {
                    row.getStyleClass().add("settings-row-divider");
                }
                card.getChildren().add(row);
            }
            dangerousRulesBox.getChildren().addAll(gt, card);
        }
    }

    private HBox dangerousRuleRow(DangerousRule rule) {
        boolean critical = DangerousRule.SEVERITY_CRITICAL.equalsIgnoreCase(rule.severity());
        Label sev = new Label(I18n.t(critical ? "settings.security.severity.critical" : "settings.security.severity.high"));
        sev.getStyleClass().addAll("badge", critical ? "badge-red" : "badge-blue");
        sev.setMinWidth(Region.USE_PREF_SIZE);

        String desc = rule.description() == null || rule.description().isBlank() ? rule.pattern() : rule.description();
        Label title = new Label(desc);
        title.getStyleClass().add("row-title");
        Label pattern = new Label(rule.pattern());
        pattern.getStyleClass().add("row-desc");
        VBox text = new VBox(2, title, pattern);
        text.getStyleClass().add("row-text");

        ToggleSwitch toggle = new ToggleSwitch();
        toggle.setSelected(rule.enabled());
        toggle.selectedProperty().addListener((obs, o, sel) -> {
            securityAdminService.setDangerousRuleEnabled(rule.id(), sel);
        });

        Button del = iconTextButton("\uE74D", I18n.t("common.delete"), () -> {
            securityAdminService.deleteDangerousRule(rule.id());
            renderDangerousRules();
        });
        del.getStyleClass().addAll("link-btn", "link-btn-danger");
        del.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(12, sev, text, toggle, del);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        HBox.setHgrow(text, Priority.ALWAYS);
        return row;
    }

    private void renderShellAllowlist() {
        shellAllowBox.getChildren().clear();
        List<String> list = securityAdminService.listShellAllowlist();
        if (list.isEmpty()) {
            shellAllowBox.getChildren().add(emptyHint(I18n.t("settings.security.shell.empty")));
            return;
        }
        for (String prog : list) {
            shellAllowBox.getChildren().add(listRow(prog, null, () -> {
                securityAdminService.removeShellProgram(prog);
                renderShellAllowlist();
            }));
        }
    }

    private void renderPathRoots() {
        pathRootsBox.getChildren().clear();
        List<String> list = securityAdminService.listPathRoots();
        if (list.isEmpty()) {
            pathRootsBox.getChildren().add(emptyHint(I18n.t("settings.security.path.empty")));
            return;
        }
        for (String root : list) {
            pathRootsBox.getChildren().add(listRow(root, null, () -> {
                securityAdminService.removePathRoot(root);
                renderPathRoots();
            }));
        }
    }

    /** 授权管理：逐条列出 HITL「总是允许」规则，每行内联撤销。 */
    private void renderGrants() {
        grantBox.getChildren().clear();
        List<ToolPermissionRule> list = securityAdminService.list();
        if (list.isEmpty()) {
            grantBox.getChildren().add(emptyHint(I18n.t("settings.security.grant.empty")));
            return;
        }
        for (ToolPermissionRule r : list) {
            String scope = r.getRuleContent() == null || r.getRuleContent().isBlank()
                    ? I18n.t("settings.security.wholeTool") : r.getRuleContent();
            String label = r.getToolName() + " · " + scope + " · " + r.getDecision();
            grantBox.getChildren().add(listRow(label, null, () -> {
                securityAdminService.delete(r.getId());
                renderGrants();
            }));
        }
    }

    // ---- 安全页辅助 ----

    private static int approvalDefaultIndex(String mode) {
        if (SecurityAdminService.APPROVAL_AUTO.equals(mode)) {
            return 1;
        }
        if (SecurityAdminService.APPROVAL_READONLY.equals(mode)) {
            return 2;
        }
        return 0;
    }

    /** 分组块：标题 + 卡片（首行为描述 + 右侧操作按钮，其下为动态内容容器）。 */
    private VBox sectionBlock(String title, String desc, List<Node> actions, Region content) {
        Label gt = new Label(title);
        gt.getStyleClass().add("settings-group-title");

        Label d = new Label(desc);
        d.getStyleClass().add("row-desc");
        d.setWrapText(true);
        d.setMaxWidth(560);
        // 占位把操作按钮顶到卡片右缘；header 用 CENTER_LEFT 使按钮相对换行后的描述垂直居中
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox header = new HBox(8, d, sp);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("settings-row");
        for (Node a : actions) {
            if (a instanceof Region r) {
                r.setMinWidth(Region.USE_PREF_SIZE);
            }
            header.getChildren().add(a);
        }

        content.setMaxWidth(Double.MAX_VALUE);
        VBox card = new VBox(6, header, content);
        card.getStyleClass().add("settings-group");
        return new VBox(6, gt, card);
    }

    /**
     * 分组块（按钮整体垂直居中）：适用于描述 + 短列表的分区（Shell 白名单、文件授权目录、授权管理）。
     * <p>卡片左侧为「描述 + 内容」列，右侧操作按钮相对整列垂直居中，避免按钮只贴首行而右下方留白。
     * 危险命令区因下方是长列表不适用此布局（仍用 {@link #sectionBlock}）。</p>
     */
    private VBox sectionBlockCentered(String title, String desc, List<Node> actions, Region content) {
        Label gt = new Label(title);
        gt.getStyleClass().add("settings-group-title");

        Label d = new Label(desc);
        d.getStyleClass().add("row-desc");
        d.setWrapText(true);
        d.setMaxWidth(560);

        content.setMaxWidth(Double.MAX_VALUE);
        VBox body = new VBox(6, d, content);
        body.setPadding(new Insets(14, 0, 14, 0));
        body.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(body, Priority.ALWAYS);

        HBox actionsBox = new HBox(8);
        actionsBox.setAlignment(Pos.CENTER);
        for (Node a : actions) {
            if (a instanceof Region r) {
                r.setMinWidth(Region.USE_PREF_SIZE);
            }
            actionsBox.getChildren().add(a);
        }

        HBox card = new HBox(12, body, actionsBox);
        card.getStyleClass().add("settings-group");
        card.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, gt, card);
    }

    /** 单行：左侧标题（可带副标题）+ 右侧删除按钮。 */
    private HBox listRow(String title, String sub, Runnable onRemove) {
        Label t = new Label(title);
        t.getStyleClass().add("row-title");
        t.setWrapText(true);
        VBox text = new VBox(2, t);
        if (sub != null && !sub.isBlank()) {
            Label s = new Label(sub);
            s.getStyleClass().add("row-desc");
            text.getChildren().add(s);
        }
        text.getStyleClass().add("row-text");

        Button remove = iconTextButton("\uE74D", I18n.t("common.delete"), onRemove);
        remove.getStyleClass().addAll("link-btn", "link-btn-danger");
        remove.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(12, text, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        HBox.setHgrow(text, Priority.ALWAYS);
        return row;
    }

    private Label emptyHint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("row-desc");
        l.setWrapText(true);
        return l;
    }

    // ---- 安全页操作 ----

    private void handleAddDangerousRule() {
        GimDialog.Shell shell = GimDialog.shell(hostWindow(), I18n.t("settings.security.rule.addTitle"));
        TextField patternField = new TextField();
        patternField.setPromptText(I18n.t("settings.security.rule.pattern.prompt"));
        patternField.setMaxWidth(Double.MAX_VALUE);
        TextField descField = new TextField();
        descField.setPromptText(I18n.t("settings.security.rule.desc.prompt"));
        descField.setMaxWidth(Double.MAX_VALUE);
        VBox form = new VBox(10,
                labeledField(I18n.t("settings.security.rule.pattern"), patternField),
                labeledField(I18n.t("settings.security.rule.desc"), descField));
        form.setPrefWidth(440);

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        Button save = new Button(I18n.t("common.save"));
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            String p = patternField.getText().trim();
            if (p.isEmpty()) {
                notify("settings.security.rule.addTitle", I18n.t("settings.status.rulePatternEmpty"));
                return;
            }
            try {
                java.util.regex.Pattern.compile(p);
            } catch (java.util.regex.PatternSyntaxException ex) {
                notify("settings.security.rule.addTitle", I18n.t("settings.status.rulePatternBad", ex.getMessage()));
                return;
            }
            securityAdminService.addDangerousRule(p, descField.getText().trim());
            shell.stage().close();
            renderDangerousRules();
        });
        HBox btns = new HBox(8, cancel, save);
        btns.setAlignment(Pos.CENTER_RIGHT);
        shell.body().getChildren().addAll(form, btns);
        shell.showAndWait();
    }

    private void handleResetDangerousRules() {
        if (!GimDialog.confirm(hostWindow(), I18n.t("settings.security.dangerous.group"),
                I18n.t("settings.security.rule.resetConfirm"))) {
            return;
        }
        securityAdminService.resetDangerousRules();
        renderDangerousRules();
    }

    private void handleAddShellProgram() {
        Optional<String> name = GimDialog.promptText(hostWindow(),
                I18n.t("settings.security.shell.addTitle"),
                I18n.t("settings.security.shell.program"), "", 64);
        name.ifPresent(n -> {
            securityAdminService.addShellProgram(n);
            renderShellAllowlist();
        });
    }

    private void handleAddPathRoot() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.t("settings.security.path.choose"));
        File dir = chooser.showDialog(hostWindow());
        if (dir != null) {
            securityAdminService.addPathRoot(dir.getAbsolutePath());
            renderPathRoots();
        }
    }

    private static VBox labeledField(String label, Node field) {
        Label l = new Label(label);
        l.getStyleClass().add("form-label");
        return new VBox(4, l, field);
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
            notify("settings.menu.providers", I18n.t("settings.status.providerCodeEmpty"));
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
        reloadAll();
        return true;
    }

    private void handleDeleteProvider(ModelProviderConfig sel) {
        if (!GimDialog.confirm(hostWindow(), I18n.t("settings.menu.providers"),
                I18n.t("settings.provider.deleteConfirm", sel.getProviderCode()))) {
            return;
        }
        adminService.deleteProvider(sel.getProviderCode());
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
        modelBaseline = null;
        modelProbeResult = null;
        modelVisionResult = null;
        if (modelProviderCombo.getItems().isEmpty()) {
            refreshProviderCombos();
        }
        loading = false;
    }

    private void loadModelToForm(ModelConfig m) {
        loading = true;
        modelProviderCombo.setValue(m.getProviderCode());
        modelCodeField.setText(m.getModelCode());
        modelCodeField.setDisable(true);
        modelNameField.setText(m.getModelName());
        priorityField.setText(String.valueOf(m.getPriority()));
        modelEnabledCheck.setSelected(EnabledStatus.isEnabled(m.getEnabled()));
        modelBaseline = m;
        modelProbeResult = null;
        modelVisionResult = null;
        loading = false;
    }

    private boolean commitModelForm() {
        if (modelCodeField.getText().isBlank() || modelNameField.getText().isBlank()) {
            notify("settings.menu.models", I18n.t("settings.status.modelFieldsEmpty"));
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
        reloadAll();
        return true;
    }

    private void handleDeleteModel(ModelConfig sel) {
        if (!GimDialog.confirm(hostWindow(), I18n.t("settings.menu.models"),
                I18n.t("settings.model.deleteConfirm", sel.getModelCode()))) {
            return;
        }
        adminService.deleteModel(sel.getModelCode());
        reloadAll();
    }

    // ---- 工具权限操作 ----

    private void handleClearPermissions() {
        securityAdminService.clear();
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
        renderTemplateCards();
        providerCodeField.setDisable(false);
        modelCodeField.setDisable(false);
        refreshDefaultModelInfo();
        // MCP 由复用组件自管理刷新；仅刷新权限列表
        if (mcpView != null) {
            mcpView.reload();
        }
        // 安全页动态列表（危险命令/Shell 白名单/授权目录/授权管理）随数据刷新
        renderDangerousRules();
        renderShellAllowlist();
        renderPathRoots();
        renderGrants();
        loading = false;
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
