package com.gettyio.gim.agent.ui.controller;

import com.gettyio.gim.agent.core.chat.ChatRequest;
import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.FileAttachment;
import com.gettyio.gim.agent.core.chat.ImageAttachment;
import com.gettyio.gim.agent.core.chat.MessageMetadataCodec;
import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.core.chat.StoredAttachment;
import com.gettyio.gim.agent.core.chat.StreamEvent;
import com.gettyio.gim.agent.core.chat.TokenUsage;
import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.entity.ModelConfig;
import com.gettyio.gim.agent.domain.entity.ModelProviderConfig;
import com.gettyio.gim.agent.domain.entity.PromptTemplate;
import com.gettyio.gim.agent.domain.enums.ConversationRole;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.enums.StreamEventType;
import com.gettyio.gim.agent.ui.UiTheme;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UserPrefs;
import com.gettyio.gim.agent.ui.config.AgentAdminService;
import com.gettyio.gim.agent.ui.config.PromptTemplateAdminService;
import com.gettyio.gim.agent.ui.config.ProviderAdminService;
import com.gettyio.gim.agent.ui.config.SecurityAdminService;
import com.gettyio.gim.agent.ui.view.ExtensionsView;
import com.gettyio.gim.agent.ui.view.TerminalView;
import com.gettyio.gim.agent.ui.view.TasksView;
import com.gettyio.gim.agent.ui.view.UsageView;
import com.gettyio.gim.agent.ui.window.AgentDialog;
import com.gettyio.gim.agent.ui.window.AppShell;
import com.gettyio.gim.agent.ui.window.GimDialog;
import com.gettyio.gim.agent.ui.window.SessionSearchDialog;
import com.gettyio.gim.agent.ui.window.SettingsWindow;
import com.gettyio.gim.agent.ui.window.TitleBar;
import io.agentscope.core.permission.PermissionMode;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.Parent;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.input.KeyCode;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主窗口控制器：侧边栏导航驱动页面切换（对话/定时任务/技能），
 * 对话页为会话列表切换 + 流式渲染（帧合并 ≥33ms）。
 * <p>侧栏顶部是智能体切换卡：选中的智能体既决定新建会话的归属，也决定
 * 下方会话列表展示哪一份数据（列表只列它的会话，行内不再重复标注）。</p>
 */
@Component
public class MainController implements com.gettyio.gim.agent.ui.chat.SessionSidebarHost,
        com.gettyio.gim.agent.ui.chat.MessageActionListener {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    /** 未选择智能体时使用的内置默认智能体编码 */
    private static final String DEFAULT_AGENT = MultiAgentGateway.DEFAULT_AGENT_CODE;
    /** 帧合并窗口：至少每 33ms 刷新一次 */
    private static final Duration FRAME_WINDOW = Duration.ofMillis(33);
    /** 侧栏偏好键：记住上次选择的智能体，新建会话默认归它 */
    private static final String PREF_SIDEBAR_AGENT = "sidebar.agent";
    /** 侧栏偏好键：记住侧栏是否收起 */
    private static final String PREF_SIDEBAR_COLLAPSED = "sidebar.collapsed";
    /** 侧栏展开时整体宽度：VBox 固定 250 + sidebar-margin 左边距 12（伸缩动画的满量程） */
    private static final double SIDEBAR_FULL_WIDTH = 262;
    /** 侧栏伸缩动画时长（毫秒） */
    private static final double SIDEBAR_ANIM_MS = 220;
    

    @FXML
    private ScrollPane scrollPane;
    @FXML
    private StackPane mainPages;
    @FXML
    private BorderPane chatPage;
    @FXML
    private ToggleGroup pageGroup;
    @FXML
    private ToggleButton navChat;
    @FXML
    private ToggleButton navTasks;
    @FXML
    private ToggleButton navSkills;
    @FXML
    private ToggleButton navUsage;
    @FXML
    private VBox messagesContainer;
    @FXML
    private VBox welcomePane;
    @FXML
    private TextArea inputField;
    @FXML
    private Button sendButton;
    @FXML
    private Button themeMenuButton;
    @FXML
    private VBox sessionsBox;
    @FXML
    private HBox sessionsHeader;
    @FXML
    private HBox recentHeader;
    @FXML
    private VBox recentBox;
    @FXML
    private Label recentSectionCaret;
    @FXML
    private Label sectionRecent;
    @FXML
    private Label sessionsSectionCaret;
    @FXML
    private Label sessionsCount;
    @FXML
    private HBox agentSwitcher;
    @FXML
    private Label agentAvatar;
    @FXML
    private Label agentNameLabel;
    @FXML
    private Label agentDescLabel;
    @FXML
    private Button searchButton;
    @FXML
    private Label navChatLabel;
    @FXML
    private Label navTasksLabel;
    @FXML
    private Label navSkillsLabel;
    @FXML
    private Label navUsageLabel;
    @FXML
    private Label sectionSessions;
    @FXML
    private Label userNameLabel;
    @FXML
    private Label welcomeTitle;
    @FXML
    private Label welcomeSubtitle;
    @FXML
    private Label privacyHint;
    /** 输入区底栏的本轮用量条：流式 USAGE 事件与切换会话时刷新 */
    @FXML
    private Label usageBar;
    @FXML
    private HBox chatHeader;
    @FXML
    private Label sessionTitleLabel;
    /** 尚未配置模型时的常驻提示条（显隐由 {@link #refreshModelBanner()} 按有无启用模型驱动） */
    @FXML
    private HBox modelBanner;
    @FXML
    private Label modelBannerText;
    @FXML
    private Button modelBannerAction;
    @FXML
    private SplitPane chatSplit;
    @FXML
    private Button terminalButton;
    @FXML
    private FlowPane attachChipsBox;
    @FXML
    private Button attachButton;
    @FXML
    private Button templateButton;
    @FXML
    private Button modelButton;
    @FXML
    private Button approvalButton;
    @FXML
    private VBox sidebarVBox;
    /** 侧栏最外层容器（sidebar-margin，左/下留白）：伸缩动画通过它的 maxWidth 实现 */
    @FXML
    private StackPane sidebarMargin;
    /** 内容区最外层容器（content-margin）：侧栏收起时补回左侧留白，保持与右侧边距对称 */
    @FXML
    private StackPane contentMargin;
    /** 标题栏上侧栏开关按钮：在 initialize() 中构造，通过 AppShell.onTitleBarReady 挂入标题栏 */
    private Button sidebarToggle;

    /** 协作类：消息列表渲染（气泡追加/帧合并刷新/滚动跟随），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.MessageListRenderer messageRenderer;

    /** 协作类：输入框附件条（待发图片/文件引用、回形针菜单、缩略图），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.AttachmentBar attachmentBar;

    /** 协作类：工具条三个下拉（齿轮/模型/审批），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.ToolbarPickers toolbar;

    /** 协作类：侧栏（智能体切换卡 + 会话列表渲染/交互），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.SessionSidebar sidebar;

    @Autowired
    private ChatService chatService;

    @Autowired
    private SettingsWindow settingsWindow;

    @Autowired
    private AppShell appShell;

    @Autowired
    private TasksView tasksView;

    @Autowired
    private ExtensionsView extensionsView;

    @Autowired
    private UsageView usageView;

    @Autowired
    private TerminalView terminalView;

    @Autowired
    private AgentAdminService agentAdminService;

    @Autowired
    private GimPaths gimPaths;

    @Autowired
    private ProviderAdminService providerAdminService;

    @Autowired
    private SecurityAdminService securityAdminService;

    @Autowired
    private PromptTemplateAdminService promptTemplateAdminService;

    private final ObservableList<ChatSession> sessions = FXCollections.observableArrayList();
    private String currentSessionId;
    private boolean settingsOpen;
    /** 左侧菜单栏是否收起 */
    private boolean sidebarCollapsed;
    /** 侧栏伸缩动画（复用：切换时若上一次还在跑先停下，避免叠加） */
    private Timeline sidebarAnim;
    /** 顶部卡片选中的智能体：新建会话归属它，它的会话构成侧栏列表，选中即写回偏好 */
    private String selectedAgentCode = DEFAULT_AGENT;
    /** 在途流式订阅：保留句柄以便用户点「停止」时取消；仅在 FX 线程读写 */
    private Disposable activeStream;
    /** 在途流所属会话：停止时需告知网关中断哪个会话的 Agent 循环 */
    private String streamingSessionId;
    /** 模板下拉菜单：复用单一实例，避免反复点击累积弹出 */
    private ContextMenu templateMenu;

    @FXML
    private void initialize() {
        scrollPane.setFitToWidth(true);
        applyRoundedClip();
        applySidebarClip();
        applySidebarMarginClip();
        messageRenderer = new com.gettyio.gim.agent.ui.chat.MessageListRenderer(scrollPane, messagesContainer, this);
        attachmentBar = new com.gettyio.gim.agent.ui.chat.AttachmentBar(
                attachChipsBox, inputField, attachButton, gimPaths, this);
        toolbar = new com.gettyio.gim.agent.ui.chat.ToolbarPickers(
                themeMenuButton, modelButton, approvalButton, providerAdminService, securityAdminService, this);
        sidebar = new com.gettyio.gim.agent.ui.chat.SessionSidebar(
                sessionsBox, recentHeader, recentBox, recentSectionCaret,
                sessionsCount, agentSwitcher, agentAvatar, agentNameLabel, agentDescLabel,
                sessionsSectionCaret, agentAdminService, chatService, this);
        // 无消息时展示居中欢迎页，有消息后自动隐藏
        welcomePane.visibleProperty().bind(Bindings.isEmpty(messageRenderer.messages()));
        welcomePane.managedProperty().bind(welcomePane.visibleProperty());
        // 多行输入框：Enter 发送、Shift+Enter 换行（事件过滤器抢在 TextArea 默认插换行之前拦截）
        inputField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                handleSend();
            }
        });

        // 侧栏导航切换主区页面（对话 / 定时任务 / 技能）
        mainPages.getChildren().setAll(chatPage);
        pageGroup.selectedToggleProperty().addListener((obs, o, t) -> {
            if (t == navTasks) {
                mainPages.getChildren().setAll(tasksView.getNode());
                tasksView.reload();
            } else if (t == navSkills) {
                mainPages.getChildren().setAll(extensionsView.getNode());
                extensionsView.reload();
            } else if (t == navUsage) {
                mainPages.getChildren().setAll(usageView.getNode());
                usageView.reload();
            } else {
                mainPages.getChildren().setAll(chatPage);
            }
        });

        // 侧栏会话列表手动渲染（VBox），点击行即切换会话
        selectedAgentCode = restoreSelectedAgent();
        sidebar.renderAgentSwitcher();
        agentSwitcher.setOnMouseClicked(e -> sidebar.showAgentMenu());
        refreshSessions();
        List<ChatSession> scoped = sidebar.sessionsOfAgent();
        if (scoped.isEmpty()) {
            // 该智能体还没有会话：留在欢迎页空态，发送首条消息时才建会话（不在库里堆空行）
            showBlankConversation();
        } else {
            switchToSession(scoped.get(0).getSessionId());
        }
        // 「会话」标题行点击：整块区域展开/收起
        sessionsHeader.setOnMouseClicked(e -> sidebar.toggleSessionsSection());
        // 「最近会话」标题行点击：平级独立区展开/收起
        recentHeader.setOnMouseClicked(e -> sidebar.toggleRecentSection());

        // 恢复侧栏收起/展开状态
        sidebarCollapsed = "true".equals(UserPrefs.get(PREF_SIDEBAR_COLLAPSED, "false"));
        applySidebarState();
        installSidebarToggle();

        // 底部终端抽屉：shell 进程退出时由视图回调宿主收起（进程与历史保留）
        terminalView.setOnCloseRequest(this::hideTerminal);

        applyI18n();
        toolbar.buildThemeMenu();
        // 审批初始档：读设置「安全」页持久化的全局默认（未设置回退询问）
        toolbar.initApprovalFromDefault();
        // 输入框工具条：附件芯片行仅在有待发图时占位；模型与审批下拉填充可选项
        attachChipsBox.visibleProperty().bind(Bindings.isNotEmpty(attachChipsBox.getChildren()));
        attachChipsBox.managedProperty().bind(attachChipsBox.visibleProperty());
        toolbar.renderModelButton();
        toolbar.renderApprovalButton();
        // 语言切换：静态文案、智能体卡片与列表都需重刷（展示名与占位文案都是本地化的）
        I18n.onChange(lang -> {
            applyI18n();
            toolbar.buildThemeMenu();
            sidebar.renderAgentSwitcher();
            sidebar.renderSessions();
            toolbar.renderModelButton();
            toolbar.renderApprovalButton();
        });
    }

    /** 内容面板圆角：裁剪贴边子页（对话/任务/技能），半径与 CSS .content-panel 的 background-radius 一致。 */
    private void applyRoundedClip() {
        Rectangle clip = new Rectangle();
        clip.setArcWidth(28);
        clip.setArcHeight(28);
        clip.widthProperty().bind(mainPages.widthProperty());
        clip.heightProperty().bind(mainPages.heightProperty());
        mainPages.setClip(clip);
    }

    /** 侧栏圆角裁剪：与 CSS .sidebar background-radius 14 匹配，避免子节点溢出圆角。 */
    private void applySidebarClip() {
        if (sidebarVBox == null) {
            return;
        }
        Rectangle clip = new Rectangle();
        clip.setArcWidth(28);
        clip.setArcHeight(28);
        clip.widthProperty().bind(sidebarVBox.widthProperty());
        clip.heightProperty().bind(sidebarVBox.heightProperty());
        sidebarVBox.setClip(clip);
    }

    /**
     * 侧栏外层容器裁剪：为伸缩动画铺路。
     * <p>把 minWidth 压到 0，使容器 maxWidth 能缩到 0（否则会被内部 VBox 的 minWidth 250 顶住）；
     * 再绑一个矩形 clip 到容器尺寸，收缩时把溢出的侧栏内容从右侧裁掉，形成擦除式动画。</p>
     */
    private void applySidebarMarginClip() {
        if (sidebarMargin == null) {
            return;
        }
        sidebarMargin.setMinWidth(0);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(sidebarMargin.widthProperty());
        clip.heightProperty().bind(sidebarMargin.heightProperty());
        sidebarMargin.setClip(clip);
    }

    /** 依据当前语言刷新主窗口静态文案（FXML 中的文本节点）。 */
    private void applyI18n() {
        navChatLabel.setText(I18n.t("nav.newTask"));
        navTasksLabel.setText(I18n.t("nav.tasks"));
        navSkillsLabel.setText(I18n.t("nav.skills"));
        navUsageLabel.setText(I18n.t("nav.usage"));
        sectionSessions.setText(I18n.t("section.sessions"));
        sectionRecent.setText(I18n.t("section.recent"));
        searchButton.setText(I18n.t("sidebar.search.prompt"));
        userNameLabel.setText(I18n.t("user.local"));
        welcomeTitle.setText(I18n.t("welcome.title"));
        welcomeSubtitle.setText(I18n.t("welcome.subtitle"));
        privacyHint.setText(I18n.t("input.privacy"));
        inputField.setPromptText(I18n.t("input.prompt"));
        if (templateButton != null) {
            if (templateButton.getTooltip() == null) {
                templateButton.setTooltip(new Tooltip());
            }
            templateButton.getTooltip().setText(I18n.t("chat.template.tooltip"));
        }
        if (terminalButton != null) {
            if (terminalButton.getTooltip() == null) {
                terminalButton.setTooltip(new Tooltip());
            }
            terminalButton.getTooltip().setText(I18n.t("chat.terminal.toggle"));
        }
        if (sidebarToggle != null) {
            if (sidebarToggle.getTooltip() == null) {
                sidebarToggle.setTooltip(new Tooltip());
            }
            sidebarToggle.getTooltip().setText(I18n.t("chat.sidebar.toggle"));
        }
        if (modelBannerText != null) {
            modelBannerText.setText(I18n.t("chat.banner.noModel"));
        }
        if (modelBannerAction != null) {
            modelBannerAction.setText(I18n.t("chat.banner.configure"));
        }
        refreshModelBanner();
    }

    /**
     * 刷新「尚未配置模型」常驻提示：无任何启用模型时常驻显示，配置并启用任意模型后自动隐藏。
     * <p>提示是全局状态驱动而非逐轮对话记录，故在启动、语言切换、设置页关闭三处重算。</p>
     */
    private void refreshModelBanner() {
        if (modelBanner == null) {
            return;
        }
        boolean missing = !providerAdminService.hasEnabledModel();
        modelBanner.setVisible(missing);
        modelBanner.setManaged(missing);
    }

    /**
     * 获取当前场景：齿轮按钮常驻侧栏，即使切到技能/定时任务页（chatPage 已脱离场景树）仍可取到 Scene。
     */
    @Override
    public Scene currentScene() {
        if (themeMenuButton != null && themeMenuButton.getScene() != null) {
            return themeMenuButton.getScene();
        }
        return chatPage == null ? null : chatPage.getScene();
    }

    /** 当前主窗口：作为对话框 owner（窗口模态 + 居中定位）。 */
    @Override
    public Window currentWindow() {
        Scene scene = currentScene();
        return scene == null ? null : scene.getWindow();
    }

    @Override
    public void showAbout() {
        GimDialog.message(currentWindow(), I18n.t("about.title"), I18n.t("about.header"),
                I18n.t("about.body", gimPaths.root()));
    }

    @Override
    public void openSettings(String menuLabel) {
        handleOpenSettings(menuLabel);
    }

    /**
     * 新任务：回到空态并把光标交给输入框。
     * <p>不落库建会话——首条消息发出时才创建（见 {@link #handleSend()}），
     * 否则点几下按钮就会在列表里留下几行没有内容的「新会话」。</p>
     */
    @FXML
    public void handleNewSession() {
        if (navChat != null) {
            navChat.setSelected(true);
        }
        showBlankConversation();
        sidebar.renderSessions();
        Platform.runLater(inputField::requestFocus);
    }

    @FXML
    public void handleOpenSettings() {
        handleOpenSettings(null);
    }

    /** 打开设置页；{@code selectMenuLabel} 非空时直达对应菜单（如模型菜单底部的「模型管理」→「模型」页）。 */
    public void handleOpenSettings(String selectMenuLabel) {
        if (settingsOpen) {
            return;
        }
        Scene scene = currentScene();
        if (scene == null) {
            return;
        }
        settingsOpen = true;
        settingsWindow.showIn(appShell, scene, selectMenuLabel, () -> {
            appShell.showApp();
            UiTheme.apply(scene);
            settingsOpen = false;
            // 设置页可能改过名称/描述或删掉了当前智能体，回到应用时同步一次卡片与会话列表
            sidebar.renderAgentSwitcher();
            refreshSessions();
            dropForeignSession();
            // 模型列表可能在设置页被增删改，重刷工具条模型按钮
            toolbar.renderModelButton();
            // 若用户在设置页新启用了一个模型，常驻提示应随之消失
            refreshModelBanner();
        });
    }

    /** 常驻提示上的「去配置」：直达设置「模型」页，引导用户添加并启用模型。 */
    @FXML
    public void handleBannerConfigureModel() {
        handleOpenSettings(I18n.t("settings.menu.models"));
    }

    @FXML
    public void handleSend() {
        // 在途生成期间不并发升一轮（Enter 与重复点击同一口径）；停止请点发送按钮的停止态
        if (activeStream != null) {
            return;
        }
        String text = inputField.getText();
        boolean hasText = text != null && !text.isBlank();
        boolean hasImages = attachmentBar.hasImages();
        boolean hasRefs = attachmentBar.hasRefs();
        boolean hasDocs = attachmentBar.hasDocs();
        if (!hasText && !hasImages && !hasRefs && !hasDocs) {
            return;
        }
        if (currentSessionId == null) {
            // 首条消息才建会话，归属当前选中的智能体；先让侧栏看到这一行，不等回复跑完
            currentSessionId = chatService.createSession(currentAgentCode());
            refreshSessions();
        }
        List<ImageAttachment> images = attachmentBar.snapshotImages();
        List<FileAttachment> docs = attachmentBar.snapshotDocs();
        String refBlock = attachmentBar.renderRefBlock();
        String body = hasText ? text : "";
        if (!refBlock.isEmpty()) {
            body = body.isEmpty() ? refBlock : body + "\n\n" + refBlock;
        }

        inputField.clear();
        List<Node> fileRows = attachmentBar.buildFileRows(attachmentBar.previewDocRefs());
        attachmentBar.clearPending();
        // 先以无主键(0)上屏，收尾后统一从库重放补齐主键与操作能力
        messageRenderer.appendUserBubble(body, attachmentBar.thumbsFromBase64(images), fileRows, 0);
        String sessionId = currentSessionId;
        String agentCode = agentOfSession(sessionId);

        ChatRequest req = ChatRequest.builder()
                .agentCode(agentCode)
                .sessionId(sessionId)
                .text(body)
                .providerCode(toolbar.selectedProviderCode())
                .modelCode(toolbar.selectedModelCode())
                .images(images)
                .files(docs)
                .permissionMode(toolbar.approvalMode())
                .autoApprove(toolbar.approvalAutoApprove())
                .build();
        runStream(chatService.chat(req), sessionId);
    }

    /**
     * 统一的流式收发：建一条空气泡，按帧增量渲染，收尾后从库重放（补齐主键与操作按钮）。
     * <p>{@code chat}/{@code regenerate}/{@code editAndResend} 三种入口都汇到这里；请求侧的落盘
     * （用户消息、删除旧助手）已在 {@code flux} 组装时于服务层同步完成，此处只订阅流。</p>
     */
    private void runStream(Flux<StreamEvent> flux, String sessionId) {
        com.gettyio.gim.agent.ui.chat.AssistantBubble bubble = messageRenderer.appendAssistantBubble();
        StringBuilder acc = new StringBuilder();
        enterStreamingUi(sessionId);
        activeStream = flux
                .bufferTimeout(256, FRAME_WINDOW)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        batch -> Platform.runLater(() -> {
                            messageRenderer.applyBatch(batch, bubble, acc);
                            updateUsageFromBatch(batch);
                        }),
                        err -> {
                            log.warn("流式对话失败", err);
                            Platform.runLater(() -> {
                                // 半截应答已由 ChatService 的 doOnError 落盘，这里只补失败提示并整体渲染
                                String note = I18n.t("chat.failed", err.getMessage());
                                if (acc.length() > 0) {
                                    acc.append("\n");
                                }
                                acc.append(note);
                                bubble.renderMarkdown(acc.toString());
                                exitStreamingUi();
                                refreshSessions();
                            });
                        },
                        () -> Platform.runLater(() -> {
                            exitStreamingUi();
                            refreshSessions();
                            // 完成：从库重放，令刚落的助手/用户气泡拿到真实主键与操作能力
                            renderSessionMessages(sessionId);
                        })
                );
    }

    /** 发送按钮的停止态入口：在途流存在则停止，否则正常发送（FXML 绑此方法）。 */
    @FXML
    public void handleSendOrStop() {
        if (activeStream != null) {
            handleStop();
            return;
        }
        handleSend();
    }

    /** 停止生成：先打断网关侧的模型/工具循环，再取消订阅（doOnCancel 落盘半截应答）。 */
    private void handleStop() {
        String session = streamingSessionId;
        if (session != null) {
            chatService.stop(agentOfSession(session), session);
        }
        Disposable stream = activeStream;
        exitStreamingUi();
        if (stream != null) {
            stream.dispose();
        }
    }

    /** 进入流式态：发送按钮原地变「停止」（方形图标、保持可点），记录在途会话。 */
    private void enterStreamingUi(String sessionId) {
        streamingSessionId = sessionId;
        Node graphic = sendButton.getGraphic();
        if (graphic instanceof Label icon) {
            icon.setText("\u25A0");
            icon.getStyleClass().add("send-stop");
        }
        sendButton.setTooltip(new Tooltip(I18n.t("chat.stop")));
    }

    /** 退出流式态：恢复发送图标并释放在途句柄。 */
    private void exitStreamingUi() {
        activeStream = null;
        streamingSessionId = null;
        Node graphic = sendButton.getGraphic();
        if (graphic instanceof Label icon) {
            icon.setText("\uE724");
            icon.getStyleClass().remove("send-stop");
        }
        sendButton.setTooltip(null);
        sendButton.setDisable(false);
    }

    /** 会话绑定的智能体编码（列表内找不到或未记录时回退默认智能体） */
    private String agentOfSession(String sessionId) {
        if (sessionId == null) {
            return DEFAULT_AGENT;
        }
        return sessions.stream()
                .filter(s -> sessionId.equals(s.getSessionId()))
                .map(ChatSession::getAgentCode)
                .filter(code -> code != null && !code.isBlank())
                .findFirst()
                .orElse(DEFAULT_AGENT);
    }

    private void switchToSession(String sessionId) {
        currentSessionId = sessionId;
        renderSessionMessages(sessionId);
        // 高亮当前会话行
        sidebar.renderSessions();
        updateHeaderTitle();
    }

    /** 从库重放当前会话的消息：逐条按角色上屏，携带主键以供删除/编辑/重生成。 */
    private void renderSessionMessages(String sessionId) {
        messageRenderer.clear();
        if (sessionId == null) {
            return;
        }
        List<ChatConversation> history = chatService.history(sessionId);
        for (ChatConversation c : history) {
            boolean self = ConversationRole.USER.getCode().equalsIgnoreCase(c.getRole());
            String content = c.getContent() == null ? "" : c.getContent();
            long id = c.getId() == null ? 0L : c.getId();
            MessageMetadataCodec.MessageMetadata meta = MessageMetadataCodec.read(c.getMetadata());
            if (self) {
                List<StoredAttachment> atts = meta.attachments();
                List<StoredAttachment> imgs = atts.stream().filter(a -> !a.isFile()).toList();
                List<StoredAttachment> files = atts.stream().filter(a -> a.isFile()).toList();
                messageRenderer.appendUserBubble(content, attachmentBar.thumbsFromStored(imgs),
                        attachmentBar.buildFileRows(files), id);
            } else {
                if (meta.interrupted() && !content.isEmpty()) {
                    content = content + "\n" + I18n.t("chat.interrupted");
                }
                messageRenderer.appendAssistantMarkdown(content, id, c.getToolCalls());
            }
        }
        updateUsageFromSession(sessionId);
    }

    // ===================== 本轮用量条（输入区底栏）=====================

    /** 扫描本帧窗口内的 USAGE 事件（一轮只在收尾时下发一次），刷新用量条。 */
    private void updateUsageFromBatch(List<StreamEvent> batch) {
        TokenUsage usage = null;
        for (StreamEvent e : batch) {
            if (e.getType() == StreamEventType.USAGE && e.getUsage() != null) {
                usage = e.getUsage();
            }
        }
        if (usage != null) {
            showUsage(usage, toolbar.selectedModelCode());
        }
    }

    /** 切换/重放会话时，取该会话最后一条带用量的助手消息刷新用量条；无则隐藏。 */
    private void updateUsageFromSession(String sessionId) {
        if (sessionId == null) {
            hideUsage();
            return;
        }
        List<ChatConversation> history = chatService.history(sessionId);
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatConversation c = history.get(i);
            if (ConversationRole.ASSISTANT.getCode().equalsIgnoreCase(c.getRole())
                    && (c.getTokensInput() != null || c.getTokensOutput() != null)) {
                TokenUsage u = new TokenUsage(nz(c.getTokensInput()), nz(c.getTokensOutput()),
                        nz(c.getTokensCached()), nz(c.getLlmCalls()),
                        c.getLlmElapsedMillis() == null ? 0L : c.getLlmElapsedMillis());
                if (u.isEmpty()) {
                    hideUsage();
                } else {
                    showUsage(u, c.getModelCode());
                }
                return;
            }
        }
        hideUsage();
    }

    private void showUsage(TokenUsage usage, String modelCode) {
        if (usage == null || usage.isEmpty()) {
            hideUsage();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append('\u2191').append(usage.inputTokens())
                .append(" \u2193").append(usage.outputTokens());
        if (usage.cachedTokens() > 0) {
            sb.append(" \u21e2").append(usage.cachedTokens());
        }
        if (usage.calls() > 0) {
            sb.append(" \u00b7 ").append(I18n.t("chat.usage.calls", usage.calls()));
        }
        double[] cost = chatService.modelCostPer1k(modelCode);
        if (cost != null) {
            double money = usage.inputTokens() / 1000.0 * cost[0] + usage.outputTokens() / 1000.0 * cost[1];
            sb.append(" \u00b7 ").append(I18n.t("chat.usage.cost", String.format("%.2f", money)));
        }
        usageBar.setText(sb.toString());
        usageBar.setVisible(true);
        usageBar.setManaged(true);
    }

    private void hideUsage() {
        if (usageBar != null) {
            usageBar.setVisible(false);
            usageBar.setManaged(false);
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    // ===================== 消息气泡操作：复制 / 重生成 / 编辑重发 / 删除 =====================

    @Override
    public void onCopy(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(rawText);
        Clipboard.getSystemClipboard().setContent(content);
    }

    @Override
    public void onRegenerate() {
        if (activeStream != null) {
            return;
        }
        String sid = currentSessionId;
        if (sid == null) {
            return;
        }
        ChatConversation lastUser = lastUserMessage(sid);
        if (lastUser == null) {
            return;
        }
        String text = lastUser.getContent() == null ? "" : lastUser.getContent();
        // regenerate 组装时已同步删掉库里的最后一条助手消息；先重放去掉旧气泡，再流式补新的
        Flux<StreamEvent> flux = chatService.regenerate(buildRequest(sid, text));
        renderSessionMessages(sid);
        runStream(flux, sid);
    }

    @Override
    public void onEditResend(long conversationId, String currentText) {
        if (activeStream != null || conversationId <= 0) {
            return;
        }
        String sid = currentSessionId;
        if (sid == null) {
            return;
        }
        java.util.Optional<String> edited = GimDialog.promptText(ownerWindow(),
                I18n.t("chat.msg.editTitle"), I18n.t("chat.msg.editTitle"), currentText, 8000);
        if (edited.isEmpty() || edited.get().isBlank()) {
            return;
        }
        // editAndResend 组装时已截断该条及其后全部并落了新的用户消息；重放后再流式应答
        Flux<StreamEvent> flux = chatService.editAndResend(buildRequest(sid, edited.get()), conversationId);
        renderSessionMessages(sid);
        runStream(flux, sid);
    }

    @Override
    public void onDelete(long conversationId) {
        if (activeStream != null || conversationId <= 0) {
            return;
        }
        String sid = currentSessionId;
        if (sid == null) {
            return;
        }
        if (!GimDialog.confirm(ownerWindow(), I18n.t("chat.msg.delete"), I18n.t("chat.msg.deleteConfirm"))) {
            return;
        }
        chatService.deleteMessage(sid, conversationId);
        renderSessionMessages(sid);
        refreshSessions();
    }

    /** 以工具条当前模型/审批档构造一条纯文本请求（重生成/编辑重发复用，不带图片附件）。 */
    private ChatRequest buildRequest(String sessionId, String text) {
        return ChatRequest.builder()
                .agentCode(agentOfSession(sessionId))
                .sessionId(sessionId)
                .text(text)
                .providerCode(toolbar.selectedProviderCode())
                .modelCode(toolbar.selectedModelCode())
                .permissionMode(toolbar.approvalMode())
                .autoApprove(toolbar.approvalAutoApprove())
                .build();
    }

    /** 会话内最后一条用户消息（重生成据此取原文）；无则返回 {@code null}。 */
    private ChatConversation lastUserMessage(String sessionId) {
        ChatConversation found = null;
        for (ChatConversation c : chatService.history(sessionId)) {
            if (ConversationRole.USER.getCode().equalsIgnoreCase(c.getRole())) {
                found = c;
            }
        }
        return found;
    }

    /** 弹框归属窗口：取输入框所在场景的窗口。 */
    private Window ownerWindow() {
        return inputField != null && inputField.getScene() != null ? inputField.getScene().getWindow() : null;
    }

    @Override
    public void showBlankConversation() {
        currentSessionId = null;
        messageRenderer.clear();
        updateHeaderTitle();
    }

    @Override
    public void refreshSessions() {
        sessions.setAll(chatService.listSessions());
        sidebar.renderSessions();
        updateHeaderTitle();
    }

    /**
     * 对话区顶部标题栏：显示当前会话名。
     * <p>无会话（新任务空态）时整条隐藏且不占位，欢迎页仍居中；标题取自侧栏同一份
     * {@code sessions}，首条消息后 {@code refreshSessions} 重载即把「新会话」换成内容摘要。</p>
     */
    private void updateHeaderTitle() {
        if (chatHeader == null || sessionTitleLabel == null) {
            return;
        }
        ChatSession current = currentSessionId == null ? null : sessions.stream()
                .filter(s -> currentSessionId.equals(s.getSessionId()))
                .findFirst().orElse(null);
        String title = current == null || current.getTitle() == null ? "" : current.getTitle();
        sessionTitleLabel.setText(title);
        // 标题栏常驻：新任务空态也要让右上角的终端按钮可达；无会话时标题留空，
        // 标签仍占位（HBox.hgrow）把终端按钮顶到最右。
        chatHeader.setVisible(true);
        chatHeader.setManaged(true);
    }

    /** 右上角终端按钮：在底部展开/收起终端抽屉（收起只摘除节点、进程与历史保留）。 */
    @FXML
    private void handleToggleTerminal() {
        if (terminalVisible()) {
            hideTerminal();
        } else {
            showTerminal();
        }
    }

    /** 终端抽屉是否已在 SplitPane 中展开（除消息区外还有第二个可拖拽项）。 */
    private boolean terminalVisible() {
        return chatSplit != null && chatSplit.getItems().size() > 1;
    }

    private void showTerminal() {
        if (chatSplit == null) {
            return;
        }
        if (!terminalVisible()) {
            chatSplit.getItems().add(terminalView.getNode());
        }
        terminalView.ensureStarted();
        chatSplit.setDividerPositions(0.68);
    }

    private void hideTerminal() {
        if (chatSplit != null && terminalVisible()) {
            chatSplit.getItems().remove(terminalView.getNode());
        }
    }

    // ===================== 侧栏顶部：智能体切换卡 =====================

    /** 读取偏好里的侧栏智能体；定义已被删除时回落默认智能体，避免新建会话指向空定义 */
    private String restoreSelectedAgent() {
        String saved = UserPrefs.get(PREF_SIDEBAR_AGENT, DEFAULT_AGENT);
        return agentAdminService.find(saved).isPresent() ? saved : DEFAULT_AGENT;
    }

    /**
     * 新建会话的归属智能体：偏好里的定义已被删除时回退默认智能体。
     * <p>设置页只拦住「有会话引用」的智能体，零会话的新建智能体可以随时被删掉；
     * 不兜住这个回退，新建会话就可能指向一个没有定义的编码。</p>
     * <p>这里只做解析、不重画卡片：卡片渲染本身要读这个编码，互相调用会递归。</p>
     */
    @Override
    public String currentAgentCode() {
        if (selectedAgentCode == null || agentAdminService.find(selectedAgentCode).isEmpty()) {
            selectedAgentCode = DEFAULT_AGENT;
        }
        return selectedAgentCode;
    }
    
    @Override
    public List<ChatSession> sessions() {
        return sessions;
    }
    
    @Override
    public String currentSessionId() {
        return currentSessionId;
    }
    
    /** 打开某会话：选中对话页并切换到该会话（供侧栏行点击/搜索面板回调）。 */
    @Override
    public void openSessionById(String sessionId) {
        if (navChat != null) {
            navChat.setSelected(true);
        }
        switchToSession(sessionId);
    }

    /**
     * 切换智能体：写回偏好，重画卡片，会话列表换成它的会话。
     * <p>不趁手去换页面：在拓展/定时任务页改选智能体不应该把用户拉回对话页。</p>
     */
    @Override
    public void selectAgent(String agentCode) {
        if (agentCode == null || agentCode.equals(selectedAgentCode)) {
            return;
        }
        selectedAgentCode = agentCode;
        UserPrefs.put(PREF_SIDEBAR_AGENT, agentCode);
        sidebar.renderAgentSwitcher();
        refreshSessions();
        dropForeignSession();
    }

    /** 当前打开的会话若已不属于选中的智能体，就回到新任务空态（否则列表与主区不一致） */
    private void dropForeignSession() {
        if (currentSessionId == null) {
            return;
        }
        boolean belongs = sidebar.sessionsOfAgent().stream()
                .anyMatch(s -> currentSessionId.equals(s.getSessionId()));
        if (!belongs) {
            showBlankConversation();
        }
    }

    /** 新建/编辑后就接管该智能体：重画卡片与会话列表，不弄丢还开着的会话 */
    @Override
    public void adoptAgent(String agentCode) {
        selectedAgentCode = agentCode;
        UserPrefs.put(PREF_SIDEBAR_AGENT, agentCode);
        sidebar.renderAgentSwitcher();
        refreshSessions();
        dropForeignSession();
    }

    /** 左侧菜单栏展开/收起：点击工具栏按钮切换，带宽度过渡动画，状态持久化到偏好 */
    @FXML
    private void handleToggleSidebar() {
        sidebarCollapsed = !sidebarCollapsed;
        UserPrefs.put(PREF_SIDEBAR_COLLAPSED, String.valueOf(sidebarCollapsed));
        applyContentMargin();
        animateSidebar(!sidebarCollapsed);
    }

    /**
     * 侧栏收起时给内容区补回左侧留白（与右侧 content-margin 的 4 对称），展开时去掉。
     * <p>展开态侧栏紧贴内容区、由 sidebar-margin 负责左沿留白；收起后 maxWidth 归 0 会连那 4 一起没了，
     * 故此处把留白责任临时交给内容区自身。</p>
     */
    private void applyContentMargin() {
        if (contentMargin == null) {
            return;
        }
        contentMargin.getStyleClass().remove("no-sidebar");
        if (sidebarCollapsed) {
            contentMargin.getStyleClass().add("no-sidebar");
        }
    }

    /** 启动侧栏伸缩动画：maxWidth 在 0 ↔ 满量程间缓动；收起后把容器设为鼠标透明 */
    private void animateSidebar(boolean expand) {
        if (sidebarMargin == null) {
            return;
        }
        if (sidebarAnim != null && sidebarAnim.getStatus() == Animation.Status.RUNNING) {
            sidebarAnim.stop();
        }
        sidebarMargin.setMouseTransparent(false);
        double to = expand ? SIDEBAR_FULL_WIDTH : 0;
        sidebarAnim = new Timeline(new KeyFrame(
                javafx.util.Duration.millis(SIDEBAR_ANIM_MS),
                new KeyValue(sidebarMargin.maxWidthProperty(), to, Interpolator.EASE_BOTH)));
        sidebarAnim.setOnFinished(e -> sidebarMargin.setMouseTransparent(!expand));
        sidebarAnim.play();
    }

    /** 根据当前 sidebarCollapsed 标志瞬时应用侧栏宽度（启动恢复用，不走动画） */
    private void applySidebarState() {
        if (sidebarMargin == null) {
            return;
        }
        sidebarMargin.setMaxWidth(sidebarCollapsed ? 0 : SIDEBAR_FULL_WIDTH);
        sidebarMargin.setMouseTransparent(sidebarCollapsed);
        applyContentMargin();
    }

    /** 构造侧栏开关按钮，挂到标题栏右侧（标题文字后、弹性 spacer 前） */
    private void installSidebarToggle() {
        if (sidebarToggle != null) {
            return;
        }
        Button btn = new Button();
        btn.setGraphic(buildSidebarToggleIcon());
        btn.getStyleClass().add("sidebar-toggle");
        btn.setFocusTraversable(false);
        btn.setOnAction(e -> handleToggleSidebar());
        sidebarToggle = btn;
        appShell.onTitleBarReady(bar -> bar.addAfterBrand(btn));
    }

    /** 绘制侧栏开关图标：圆角矩形外框 + 左侧竖线分隔（匹配 Fluent “PanelLeft” 风格）。
     *  <p>容器高度与按钮内容区（标题栏 38px）一致，形状画在容器垂直正中，
     *  规避 Button 对无基线 graphic 顶部对齐导致的图标偏上问题。</p> */
    private static Node buildSidebarToggleIcon() {
        double paneH = 38;
        double iconW = 14;
        double iconH = 12;
        double top = (paneH - iconH) / 2;
        double left = 1;

        Rectangle frame = new Rectangle(left, top, iconW, iconH);
        frame.setArcWidth(4);
        frame.setArcHeight(4);
        frame.setFill(null);
        frame.setStrokeWidth(1.4);
        frame.getStyleClass().add("sidebar-icon-stroke");

        Line divider = new Line(left + 4.5, top, left + 4.5, top + iconH);
        divider.setStrokeWidth(1.4);
        divider.getStyleClass().add("sidebar-icon-stroke");

        Pane icon = new Pane(frame, divider);
        icon.setPrefSize(16, paneH);
        icon.setMinSize(16, paneH);
        return icon;
    }

    /** 搜索按钮：弹出命令面板，在当前智能体的会话标题与消息正文里检索，选中即打开（消息行还会滚到该条）。 */
    @FXML
    public void handleOpenSearch() {
        List<ChatSession> scoped = sidebar.sessionsOfAgent();
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (ChatSession s : scoped) {
            allowed.add(s.getSessionId());
        }
        SessionSearchDialog.show(currentWindow(), scoped,
                keyword -> chatService.searchContent(keyword, 200).stream()
                        .filter(h -> allowed.contains(h.sessionId()))
                        .limit(50)
                        .toList(),
                s -> openSessionById(s.getSessionId()),
                this::openMessageHit);
    }

    /** 打开命中的消息：先切到其所属会话，再在重放完成后滚动并短暂高亮该条。 */
    private void openMessageHit(com.gettyio.gim.agent.domain.entity.MessageHit hit) {
        if (hit == null) {
            return;
        }
        openSessionById(hit.sessionId());
        Platform.runLater(() -> messageRenderer.revealAndHighlight(hit.conversationId()));
    }

    // ===================== 输入框工具条：附件 / 模型 / 审批 =====================

    /**
     * 回形针按钮：弹出「文件 / 文件夹」子菜单。
     * <p>图片不单列——它本就是文件的一种：在「文件」选择器里选中图片仍走 Base64 多模态通道，
     * 选中其它文件则只记路径引用，发送时拼进正文由 Agent 用文件工具按需读取。</p>
     * <p>文件夹走 {@link DirectoryChooser}（{@code FileChooser} 本身不支持选目录）。</p>
     */
    @FXML
    public void handleAttach() {
        attachmentBar.handleAttach();
    }

    /** 点模型按钮：委托工具条模型菜单（实现见 {@code ToolbarPickers}）。 */
    @FXML
    public void showModelMenu() {
        toolbar.showModelMenu();
    }

    /** 点审批按钮：委托工具条审批菜单（实现见 {@code ToolbarPickers}）。 */
    @FXML
    public void showApprovalMenu() {
        toolbar.showApprovalMenu();
    }

    /**
     * 点模板按钮：弹出启用中的提示词模板，选中把内容插入输入框光标处（不发送、不覆盖已有文本）。
     * <p>无启用模板时仅显示一条禁用提示项。</p>
     */
    @FXML
    public void showTemplateMenu() {
        if (templateMenu == null) {
            templateMenu = new ContextMenu();
        } else {
            templateMenu.getItems().clear();
        }
        List<PromptTemplate> templates = promptTemplateAdminService.listEnabled();
        if (templates.isEmpty()) {
            MenuItem empty = new MenuItem(I18n.t("chat.template.empty"));
            empty.setDisable(true);
            templateMenu.getItems().add(empty);
        } else {
            for (PromptTemplate t : templates) {
                String name = t.getTemplateName() == null || t.getTemplateName().isBlank()
                        ? t.getTemplateCode() : t.getTemplateName();
                MenuItem item = new MenuItem(name, UiTheme.icon("\uE73A"));
                String content = t.getContent() == null ? "" : t.getContent();
                item.setOnAction(e -> {
                    inputField.insertText(inputField.getCaretPosition(), content);
                    inputField.requestFocus();
                    inputField.positionCaret(inputField.getCaretPosition() + content.length());
                });
                templateMenu.getItems().add(item);
            }
        }
        templateMenu.show(templateButton, javafx.geometry.Side.TOP, 0, 0);
    }
}
