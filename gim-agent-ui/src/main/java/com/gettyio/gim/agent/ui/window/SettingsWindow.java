package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.backup.DbBackupService;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
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
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.Window;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 设置窗口：左侧分类菜单（含搜索）+ 右侧内容页（对标 Qoder 设置风格）。
 * <p>分页：个人资料 / 账号 / 外观（主题·字体·语言，接入 {@link UiTheme}/{@link I18n}）/
 * 通用（启动与关闭）/ 对话与用量（上下文窗口·月预算）/ 语音（输入转写·朗读）/ 图像生成 /
 * 网络（全局代理）/ 数据与备份（数据与路径·本地备份·导出·WebDAV 云同步与恢复）/
 * 更新（检查·下载·安装）/ 智能体 / 记忆（开关·沉淀档位·按作用域管理记忆文件）/
 * 模型提供商 / 模型 / 默认模型 / 技能 / 提示词模板 / MCP 服务 /
 * 安全（工具防护·文件防护·联网·授权管理）。仅呈现有真实后端支撑的页面，不伪造无功能菜单项。
 * 外观页改动即时生效；语言切换时整窗重建以刷新全部文案。</p>
 * <p>本类为薄编排层：仅负责整窗骨架（菜单/页面容器/语言重建）与各设置页子面板的装配及整表刷新驱动，
 * 具体页面内容由按设置页拆出的子面板（如 {@link SecuritySettingsPanel}/{@link ModelSettingsPanel}）承载。</p>
 */
@Component
public class SettingsWindow {

    @Autowired
    private ProviderAdminService adminService;

    @Autowired
    private GimPaths gimPaths;

    @Autowired
    private McpAdminService mcpAdminService;

    /** 在线市场服务：MCP 页内「市场」入口复用 */
    @Autowired
    private com.gettyio.gim.agent.ui.market.MarketService marketService;

    @Autowired
    private SecurityAdminService securityAdminService;

    @Autowired
    private AgentAdminService agentAdminService;

    @Autowired
    private DbBackupService dbBackupService;

    /** 读应用级设置（月度用量预算键）；仅取 settingDao 用途 */
    @Autowired
    private com.gettyio.gim.agent.domain.GimDatabase database;

    @Autowired
    private com.gettyio.gim.agent.ui.sync.WebDavSyncService webDavSyncService;

    @Autowired
    private com.gettyio.gim.agent.ui.voice.VoiceInputService voiceInputService;

    @Autowired
    private com.gettyio.gim.agent.ui.voice.TtsService ttsService;

    /** 多智能体网关：通用页上下文窗口上限变更后需失效全部运行体即时重装配。 */
    @Autowired
    private com.gettyio.gim.agent.core.chat.MultiAgentGateway agentGateway;

    /** 全局网络代理：通用页代理卡片直接读写（保存即应用到进程）。 */
    @Autowired
    private com.gettyio.gim.agent.ui.config.ProxyManager proxyManager;

    /** 模型实例缓存：代理出口变更后清空，使下一轮对话按新传输重建模型。 */
    @Autowired
    private com.gettyio.gim.agent.core.model.AgentScopeModelFactory modelFactory;

    /** 应用级记忆管理：记忆页按作用域浏览/编辑/删除各工作区的 MEMORY.md 与台账。 */
    @Autowired
    private com.gettyio.gim.agent.core.memory.MemoryService memoryService;

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

    /** Docker 握手探针（与执行路径同源的单例）：安全页沙箱卡复用其 TTL 缓存做初始状态检测。 */
    @Autowired
    private com.gettyio.gim.agent.tools.sandbox.DockerAvailabilityProbe dockerAvailabilityProbe;

    /** MCP 服务页复用组件（自包含）；与拓展页各自实例化，互不干扰场景图父子关系。 */
    private McpView mcpView;

    /** 技能页复用组件：与拓展页共用同一 Spring 单例，两处不同时展示，节点挂接互斥安全。 */
    @Autowired
    private SkillsView skillsView;

    /** 本地登录认证（软锁）：账号页直接读写凭证文件。 */
    @Autowired
    private com.gettyio.gim.agent.domain.security.LocalAuthService localAuthService;

    /** 安全设置子面板（抽自本类）：懒加载单例，语言切换重建时复用同一实例、容器内容随之重绘。 */
    private SecuritySettingsPanel securityPanel;

    private SecuritySettingsPanel securityPanel() {
        if (securityPanel == null) {
            securityPanel = new SecuritySettingsPanel(this, securityAdminService, gimPaths, dockerAvailabilityProbe);
        }
        return securityPanel;
    }

    /** 关于子面板（独立菜单页）：懒加载单例，语言切换重建时复用同一实例。 */
    private UpdateSettingsPanel updatePanel;

    private UpdateSettingsPanel updatePanel() {
        if (updatePanel == null) {
            updatePanel = new UpdateSettingsPanel(this, gimPaths, updateChecker);
        }
        return updatePanel;
    }

    /** 外观子面板（抽自本类）：懒加载单例，无内部状态。 */
    private AppearanceSettingsPanel appearancePanel;

    private AppearanceSettingsPanel appearancePanel() {
        if (appearancePanel == null) {
            appearancePanel = new AppearanceSettingsPanel();
        }
        return appearancePanel;
    }

    /** 通用子面板（抽自本类）：懒加载单例，仅承载启动与关闭行为（含全局热键）。 */
    private GeneralSettingsPanel generalPanel;

    private GeneralSettingsPanel generalPanel() {
        if (generalPanel == null) {
            generalPanel = new GeneralSettingsPanel(this, appLifecycle, globalHotkeyService);
        }
        return generalPanel;
    }

    /** 账号子面板（独立菜单页）：懒加载单例，本地登录认证（软锁）凭证的直接读写。 */
    private AccountSettingsPanel accountPanel;

    private AccountSettingsPanel accountPanel() {
        if (accountPanel == null) {
            accountPanel = new AccountSettingsPanel(this, localAuthService);
        }
        return accountPanel;
    }

    /** 对话与用量子面板（独立菜单页）：懒加载单例，承载上下文窗口上限与月度用量预算。 */
    private ChatUsageSettingsPanel chatUsagePanel;

    private ChatUsageSettingsPanel chatUsagePanel() {
        if (chatUsagePanel == null) {
            chatUsagePanel = new ChatUsageSettingsPanel(this, database, agentGateway);
        }
        return chatUsagePanel;
    }

    /** 语音子面板（独立菜单页）：懒加载单例，承载语音输入转写与语音朗读配置。 */
    private VoiceSettingsPanel voicePanel;

    private VoiceSettingsPanel voicePanel() {
        if (voicePanel == null) {
            voicePanel = new VoiceSettingsPanel(this, voiceInputService, ttsService);
        }
        return voicePanel;
    }

    /** 图像生成子面板（独立菜单页）：懒加载单例，配置读写复用安全服务后端。 */
    private ImageSettingsPanel imagePanel;

    private ImageSettingsPanel imagePanel() {
        if (imagePanel == null) {
            imagePanel = new ImageSettingsPanel(this, securityAdminService);
        }
        return imagePanel;
    }

    /** 网络子面板（独立菜单页）：懒加载单例，全局代理保存即应用到进程；并承载联网搜索服务配置（自安全页并入）。 */
    private NetworkSettingsPanel networkPanel;

    private NetworkSettingsPanel networkPanel() {
        if (networkPanel == null) {
            networkPanel = new NetworkSettingsPanel(this, proxyManager, modelFactory, agentGateway,
                    securityAdminService);
        }
        return networkPanel;
    }

    /** 备份与恢复子面板（抽自本类）：懒加载单例，承载本地备份/导出与 WebDAV 云同步/恢复。 */
    private BackupRestoreSettingsPanel backupRestorePanel;

    private BackupRestoreSettingsPanel backupRestorePanel() {
        if (backupRestorePanel == null) {
            backupRestorePanel = new BackupRestoreSettingsPanel(this, gimPaths, dbBackupService,
                    exportService, webDavSyncService);
        }
        return backupRestorePanel;
    }

    /** 记忆子面板（抽自本类）：懒加载单例；开关/沉淀档位写 AppSetting 后即时失效运行体。 */
    private MemorySettingsPanel memoryPanel;

    private MemorySettingsPanel memoryPanel() {
        if (memoryPanel == null) {
            memoryPanel = new MemorySettingsPanel(this, memoryService, database, agentGateway);
        }
        return memoryPanel;
    }

    /** 智能体子面板（抽自本类）：懒加载单例，持有本页专属卡片容器；渲染由 reloadAll 驱动。 */
    private AgentSettingsPanel agentPanel;

    private AgentSettingsPanel agentPanel() {
        if (agentPanel == null) {
            agentPanel = new AgentSettingsPanel(this, agentAdminService, database);
        }
        return agentPanel;
    }

    /** 提示词模板子面板（抽自本类）：懒加载单例，持有本页专属卡片容器；渲染由 reloadAll 驱动。 */
    private TemplateSettingsPanel templatePanel;

    private TemplateSettingsPanel templatePanel() {
        if (templatePanel == null) {
            templatePanel = new TemplateSettingsPanel(this, promptTemplateAdminService);
        }
        return templatePanel;
    }

    /** 模型配置子面板（提供商/模型/默认三页合抽一处）：懒加载单例，三页共享表单与探测态；渲染由 reloadAll 驱动。 */
    private ModelSettingsPanel modelPanel;

    private ModelSettingsPanel modelPanel() {
        if (modelPanel == null) {
            modelPanel = new ModelSettingsPanel(this, adminService);
        }
        return modelPanel;
    }

    private Scene hostScene;
    private AppShell shell;
    private Runnable onBack;
    private boolean showing;
    private StackPane pageContainer;
    private ToggleGroup menuGroup;
    private VBox menuBox;
    private final List<MenuEntry> menuEntries = new ArrayList<>();
    private boolean langListenerRegistered;
    /** 本次打开要直达的设置菜单文案（如「模型」）；为空则默认选中第一项 */
    private String pendingMenuLabel;

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
        modelPanel().initCombos();

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
        addMenu(personal, "\uE716", I18n.t("settings.menu.account"), buildAccountPage());
        addMenu(personal, "\uE790", I18n.t("settings.menu.appearance"), buildAppearancePage());
        addMenu(personal, "\uE713", I18n.t("settings.menu.general"), buildGeneralPage());
        addMenu(personal, "\uE8BD", I18n.t("settings.menu.chat"), buildChatUsagePage());
        addMenu(personal, "\uE720", I18n.t("settings.menu.voice"), buildVoicePage());
        addMenu(personal, "\uE91B", I18n.t("settings.menu.image"), buildImagePage());
        addMenu(personal, "\uE909", I18n.t("settings.menu.network"), buildNetworkPage());
        addMenu(personal, "\uE7B5", I18n.t("settings.menu.backupRestore"), buildBackupRestorePage());
        addMenu(personal, "\uE946", I18n.t("settings.menu.about"), buildUpdatePage());

        Label model = addSection(I18n.t("settings.section.model"));
        addMenu(model, "\uE99A", I18n.t("settings.menu.agents"), buildAgentPage());
        addMenu(model, "\uE7C3", I18n.t("settings.menu.memory"), buildMemoryPage());
        addMenu(model, "\uE753", I18n.t("settings.menu.providers"), buildProviderPage());
        addMenu(model, "\uE7B8", I18n.t("settings.menu.models"), buildModelPage());

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

    // ---- 页面装配：各页委派给对应子面板构建（提供商/模型两页共享 modelPanel） ----

    private VBox buildAccountPage() {
        return accountPanel().build();
    }

    private VBox buildAppearancePage() {
        return appearancePanel().build();
    }

    private VBox buildGeneralPage() {
        return generalPanel().build();
    }

    private VBox buildChatUsagePage() {
        return chatUsagePanel().build();
    }

    private VBox buildVoicePage() {
        return voicePanel().build();
    }

    private VBox buildImagePage() {
        return imagePanel().build();
    }

    private VBox buildNetworkPage() {
        return networkPanel().build();
    }

    private VBox buildBackupRestorePage() {
        return backupRestorePanel().build();
    }

    private VBox buildUpdatePage() {
        return updatePanel().build();
    }

    private VBox buildMemoryPage() {
        return memoryPanel().build();
    }

    private VBox buildAgentPage() {
        return agentPanel().build();
    }

    private VBox buildProviderPage() {
        return modelPanel().buildProviderPage();
    }

    private VBox buildModelPage() {
        return modelPanel().buildModelPage();
    }

    /** 复用拓展页的技能整页；设置内显示一级标题（拓展 Tab 内被隐去）。 */
    private VBox buildSkillsPage() {
        skillsView.showHeaderTitle(true);
        return skillsView.getNode();
    }

    private VBox buildMcpPage() {
        mcpView = new McpView(mcpAdminService, marketService);
        return mcpView.getNode();
    }

    private VBox buildTemplatesPage() {
        return templatePanel().build();
    }

    private VBox buildSecurityPage() {
        return securityPanel().build();
    }

    /** 宿主窗口：作为无边框对话框的 owner（窗口模态并居中）。 */
    Window hostWindow() {
        return hostScene == null ? null : hostScene.getWindow();
    }

    /** 退出登录（账号页入口）：先退回主界面，再清除会话并在窗口内呈现登录页。 */
    void signOutApp() {
        if (onBack != null) {
            onBack.run();
        }
        appLifecycle.lockToLogin();
    }

    /** 即时反馈：以统一对话框呈现需要用户知晓的结果/错误（不驻留，避免跨页面残留）。 */
    void notify(String titleKey, String message) {
        GimDialog.message(hostWindow(), I18n.t(titleKey), null, message);
    }

    /** 整表刷新：驱动各持有动态内容的子面板重绘，供增删改后统一回显。 */
    void reloadAll() {
        modelPanel().reload();
        agentPanel().reload();
        templatePanel().reload();
        // MCP 由复用组件自管理刷新
        if (mcpView != null) {
            mcpView.reload();
        }
        // 安全页动态列表（危险命令/Shell 白名单/授权目录/授权管理）随数据刷新
        securityPanel().reloadSecurity();
    }
}
