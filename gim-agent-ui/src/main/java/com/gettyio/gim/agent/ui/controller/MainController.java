package com.gettyio.gim.agent.ui.controller;

import com.gettyio.gim.agent.core.chat.ChatRequest;
import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.ContextCompactionResult;
import com.gettyio.gim.agent.core.chat.FileAttachment;
import com.gettyio.gim.agent.core.knowledge.KnowledgeService;
import com.gettyio.gim.agent.core.plan.PlanService;
import com.gettyio.gim.agent.core.chat.ImageAttachment;
import com.gettyio.gim.agent.core.chat.MessageMetadataCodec;
import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.core.chat.StoredAttachment;
import com.gettyio.gim.agent.core.chat.StreamEvent;
import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.entity.ModelConfig;
import com.gettyio.gim.agent.domain.enums.ConversationRole;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.enums.StreamEventType;
import com.gettyio.gim.agent.tools.image.ImageGenerationClient;
import com.gettyio.gim.agent.ui.UiTheme;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UserPrefs;
import com.gettyio.gim.agent.ui.config.AgentAdminService;
import com.gettyio.gim.agent.ui.config.PromptTemplateAdminService;
import com.gettyio.gim.agent.ui.config.ProviderAdminService;
import com.gettyio.gim.agent.ui.config.SecurityAdminService;
import com.gettyio.gim.agent.ui.export.ExportService;
import com.gettyio.gim.agent.ui.export.SessionImageExporter;
import com.gettyio.gim.agent.ui.view.ExtensionsView;
import com.gettyio.gim.agent.ui.view.TerminalView;
import com.gettyio.gim.agent.ui.view.TasksView;
import com.gettyio.gim.agent.ui.view.UsageView;
import com.gettyio.gim.agent.ui.window.AgentDialog;
import com.gettyio.gim.agent.ui.window.AppShell;
import com.gettyio.gim.agent.ui.window.GimDialog;
import com.gettyio.gim.agent.ui.window.ImageGenerateDialog;
import com.gettyio.gim.agent.ui.window.SessionSearchDialog;
import com.gettyio.gim.agent.ui.window.SettingsWindow;
import com.gettyio.gim.agent.ui.window.TitleBar;
import io.agentscope.core.permission.PermissionMode;
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
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.shape.Rectangle;
import javafx.scene.input.KeyCode;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
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
import reactor.core.publisher.Mono;
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
import java.util.Optional;

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
    /** 「回到底部」悬浮按钮：滚动离开最新消息时显现（显隐与滚动动画由 {@link com.gettyio.gim.agent.ui.chat.ScrollToBottomButton} 驱动） */
    @FXML
    private Button scrollBottomBtn;
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
    /** 上下文占用进度条 + 文案：发送前预估当前会话 token / 上限，临界提示将触发压缩 */
    @FXML
    private javafx.scene.control.ProgressBar contextGauge;
    @FXML
    private Label contextBar;
    /** 上下文占用区容器（进度条 + 百分比文案）：整体作为悬停弹层触发区 */
    @FXML
    private HBox contextStats;
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
    /** 规划轮跑完后的「计划待确认」横幅：PLAN.md 预览 + 编辑/确认执行（显隐由 reviewPlan/确认驱动） */
    @FXML
    private VBox planBanner;
    @FXML
    private Label planBannerTitle;
    @FXML
    private Button planEditButton;
    @FXML
    private Button planConfirmButton;
    @FXML
    private VBox planPreview;
    @FXML
    private SplitPane chatSplit;
    /** 外层横向分栏：左聊天（chatSplit），右工作区面板（动态挂入） */
    @FXML
    private SplitPane workspaceSplit;
    @FXML
    private Button workspaceButton;
    @FXML
    private Button terminalButton;
    @FXML
    private FlowPane attachChipsBox;
    @FXML
    private Button attachButton;
    @FXML
    private Button templateButton;
    @FXML
    private Button micButton;
    @FXML
    private Button modelButton;
    @FXML
    private Button approvalButton;
    @FXML
    private Button compareButton;
    @FXML
    private VBox sidebarVBox;
    /** 侧栏最外层容器（sidebar-margin，左/下留白）：伸缩动画通过它的 maxWidth 实现 */
    @FXML
    private StackPane sidebarMargin;
    /** 内容区最外层容器（content-margin）：侧栏收起时补回左侧留白，保持与右侧边距对称 */
    @FXML
    private StackPane contentMargin;
    /** 协作类：侧栏收起/展开（开关按钮挂载、伸缩动画、留白补偿、偏好持久化），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.SidebarToggle sidebarToggle;

    /** 协作类：消息列表渲染（气泡追加/帧合并刷新/滚动跟随），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.MessageListRenderer messageRenderer;

    /** 协作类：输入框附件条（待发图片/文件引用、回形针菜单、缩略图），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.AttachmentBar attachmentBar;

    /** 协作类：工具条三个下拉（齿轮/模型/审批），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.ToolbarPickers toolbar;

    /** 协作类：侧栏（智能体切换卡 + 会话列表渲染/交互），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.SessionSidebar sidebar;

    /** 协作类：底栏状态条（本轮用量 + 上下文占用），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.ChatStatusBar statusBar;

    /** 协作类：底部终端抽屉（展开/收起、进程与历史保留），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.TerminalDrawer terminalDrawer;

    /** 协作类：工作区右侧栏（文件树 + 就地阅读），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.WorkspacePanel workspacePanel;

    @Autowired
    private ChatService chatService;

    /** 网关直连：多模型对比绕开落盘层直接订阅各列流（只读、不进历史） */
    @Autowired
    private com.gettyio.gim.agent.core.chat.AgentGateway agentGateway;

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
    private KnowledgeService knowledgeService;

    @Autowired
    private PlanService planService;

    @Autowired
    private PromptTemplateAdminService promptTemplateAdminService;

    @Autowired
    private com.gettyio.gim.agent.ui.throttle.ModelRateGovernor rateGovernor;

    @Autowired
    private com.gettyio.gim.agent.ui.notify.SystemNotifier systemNotifier;

    @Autowired
    private com.gettyio.gim.agent.ui.voice.VoiceInputService voiceInputService;

    @Autowired
    private com.gettyio.gim.agent.ui.voice.TtsService ttsService;

    /** 图像生成客户端：附件菜单「生成图片」入口调用（配置从加密 settings 实时取用） */
    @Autowired
    private ImageGenerationClient imageGenerationClient;

    /** 会话导出服务：侧栏「导出长图/导出网页」共用（Markdown/PDF/DOCX 走设置页数据维护卡片） */
    @Autowired
    private ExportService exportService;

    /** 读应用级设置（上下文 token 上限）；仅取 settingDao 的只读用途 */
    @Autowired
    private com.gettyio.gim.agent.domain.GimDatabase database;

    private final ObservableList<ChatSession> sessions = FXCollections.observableArrayList();
    private String currentSessionId;
    private boolean settingsOpen;
    /** 顶部卡片选中的智能体：新建会话归属它，它的会话构成侧栏列表，选中即写回偏好 */
    private String selectedAgentCode = DEFAULT_AGENT;
    /** 在途流式订阅：保留句柄以便用户点「停止」时取消；仅在 FX 线程读写 */
    private Disposable activeStream;
    /** 在途流所属会话：停止时需告知网关中断哪个会话的 Agent 循环 */
    private String streamingSessionId;
    /** 在途多模型对比：逐列订阅句柄（停止时全部取消）；仅在 FX 线程读写 */
    private final List<Disposable> compareStreams = new ArrayList<>();
    /** 在途对比的临时会话 id（每列一个，停止时逐个通知网关中断） */
    private final List<String> compareSessions = new ArrayList<>();
    /** 在途对比归属的智能体编码（停止时用）；无对比在途时为 null */
    private String compareAgentCode;
    /** 是否有对比在途（isBusy 判定与停止分流） */
    private boolean comparing;
    /** 本轮对比的总列数与已完成列数：全部列终结（完成/出错/停止）后退出流式态 */
    private int compareDoneTotal;
    private int compareCompleted;
    /** 模板下拉菜单：复用单一实例，避免反复点击累积弹出 */
    private ContextMenu templateMenu;
    /** 横幅当前展示的计划归属智能体（编辑/确认时定位 PLAN.md） */
    private String planBannerAgent;

    @FXML
    private void initialize() {
        scrollPane.setFitToWidth(true);
        applyRoundedClip();
        applySidebarClip();
        applySidebarMarginClip();
        messageRenderer = new com.gettyio.gim.agent.ui.chat.MessageListRenderer(scrollPane, messagesContainer, this);
        // 滚动到顶向上补页（长会话历史分页）
        messageRenderer.setOnTopReached(this::loadOlderMessages);
        // 「回到底部」悬浮按钮：停靠消息区底部中央，离开最新消息时显现，点击平滑回底
        StackPane.setAlignment(scrollBottomBtn, javafx.geometry.Pos.BOTTOM_CENTER);
        StackPane.setMargin(scrollBottomBtn, new javafx.geometry.Insets(0, 0, 16, 0));
        new com.gettyio.gim.agent.ui.chat.ScrollToBottomButton(scrollPane, scrollBottomBtn);
        attachmentBar = new com.gettyio.gim.agent.ui.chat.AttachmentBar(
                attachChipsBox, inputField, attachButton, gimPaths, this);
        toolbar = new com.gettyio.gim.agent.ui.chat.ToolbarPickers(
                themeMenuButton, modelButton, approvalButton, templateButton, compareButton, inputField,
                providerAdminService, securityAdminService, promptTemplateAdminService, this);
        sidebar = new com.gettyio.gim.agent.ui.chat.SessionSidebar(
                sessionsBox, sessionsHeader, recentHeader, recentBox, recentSectionCaret,
                sessionsCount, agentSwitcher, agentAvatar, agentNameLabel, agentDescLabel,
                sessionsSectionCaret, agentAdminService, chatService, this);
        statusBar = new com.gettyio.gim.agent.ui.chat.ChatStatusBar(
                usageBar, contextBar, contextGauge, contextStats, inputField, chatService, rateGovernor, database,
                toolbar::selectedModelCode, this::currentSessionId,
                new com.gettyio.gim.agent.ui.chat.ChatStatusBar.ContextCompactor() {
                    @Override
                    public boolean busy() {
                        return isBusy();
                    }

                    @Override
                    public Mono<ContextCompactionResult> compact(String sessionId) {
                        return chatService.compact(agentOfSession(sessionId), sessionId);
                    }
                });
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
        // 输入变化即时刷新上下文占用条（预估下一轮的提示词体量）
        inputField.textProperty().addListener((obs, o, nv) -> statusBar.updateContext());
        // 按住说话：松开后转写文本插入输入框（QuickAsk 小窗同一套安装逻辑）
        com.gettyio.gim.agent.ui.voice.MicHoldButton.install(micButton, voiceInputService,
                this::insertVoiceText,
                () -> GimDialog.message(ownerWindow(), I18n.t("voice.title"), null, I18n.t("voice.notConfigured")),
                (title, msg) -> GimDialog.message(ownerWindow(), title, null, msg));

        // 侧栏导航切换主区页面（对话 / 定时任务 / 技能）
        // 「新任务」项在对话页恒处于选中态，常驻底色会被读成“一直聚焦”，此处另挂样式类去掉底色，
        // 只保留悬停与按下反馈（见 CSS 的 .nav-new-task）
        navChat.getStyleClass().add("nav-new-task");
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
        // 启动焦点交接：不任由 JavaFX 把焦点给场景里首个可 Tab 到的控件（那是侧栏「新任务」导航项，
        // 一开机就顶着一个焦点态）。焦点跟着内容走：有会话即落在该会话，空态即落在新任务输入区，
        // 两种情形都是把光标交给输入框，导航按钮不参与。
        Platform.runLater(this::focusComposer);
        // 「会话」标题行点击：整块区域展开/收起
        sessionsHeader.setOnMouseClicked(e -> sidebar.toggleSessionsSection());
        // 「最近会话」标题行点击：平级独立区展开/收起
        recentHeader.setOnMouseClicked(e -> sidebar.toggleRecentSection());

        // 恢复侧栏收起/展开状态，并把开关按钮挂到标题栏
        sidebarToggle = new com.gettyio.gim.agent.ui.chat.SidebarToggle(sidebarMargin, contentMargin, appShell);
        sidebarToggle.restore();

        // 底部终端抽屉：shell 进程退出时由视图回调宿主收起（进程与历史保留）
        terminalDrawer = new com.gettyio.gim.agent.ui.chat.TerminalDrawer(chatSplit, terminalView);
        terminalView.setOnCloseRequest(terminalDrawer::hide);

        // 工作区右侧栏：文件树 + 就地阅读；把聊天内联 HTML/SVG 产物预览从弹窗改导入本面板
        workspacePanel = new com.gettyio.gim.agent.ui.chat.WorkspacePanel(workspaceSplit, gimPaths);
        com.gettyio.gim.agent.ui.chat.ArtifactPreviewWindow.setPreviewSink(
                (owner, code, lang) -> workspacePanel.showArtifact(code, lang));

        applyI18n();
        toolbar.buildThemeMenu();
        // 审批初始档：读设置「安全」页持久化的全局默认（未设置回退询问）
        toolbar.initApprovalFromDefault();
        // 输入框工具条：附件芯片行仅在有待发图时占位；模型与审批下拉填充可选项
        attachChipsBox.visibleProperty().bind(Bindings.isNotEmpty(attachChipsBox.getChildren()));
        attachChipsBox.managedProperty().bind(attachChipsBox.visibleProperty());
        toolbar.renderModelButton();
        toolbar.renderApprovalButton();
        toolbar.renderCompareButton();
        // 语言切换：静态文案、智能体卡片与列表都需重刷（展示名与占位文案都是本地化的）
        I18n.onChange(lang -> {
            applyI18n();
            toolbar.buildThemeMenu();
            sidebar.renderAgentSwitcher();
            sidebar.renderSessions();
            toolbar.renderModelButton();
            toolbar.renderApprovalButton();
            toolbar.renderCompareButton();
            // 占用条百分比与浮层内按钮文案随语言即时刷新
            statusBar.updateContext();
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
        if (workspaceButton != null) {
            if (workspaceButton.getTooltip() == null) {
                workspaceButton.setTooltip(new Tooltip());
            }
            workspaceButton.getTooltip().setText(I18n.t("chat.workspace.toggle"));
        }
        if (sidebarToggle != null) {
            sidebarToggle.refreshLabel();
        }
        if (modelBannerText != null) {
            modelBannerText.setText(I18n.t("chat.banner.noModel"));
        }
        if (modelBannerAction != null) {
            modelBannerAction.setText(I18n.t("chat.banner.configure"));
        }
        if (planBannerTitle != null) {
            planBannerTitle.setText(I18n.t("plan.banner.title"));
        }
        if (planEditButton != null) {
            planEditButton.setText(I18n.t("common.edit"));
        }
        if (planConfirmButton != null) {
            planConfirmButton.setText(I18n.t("plan.banner.confirm"));
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

    /** 附件条「存入知识库」：抽文本落盘到当前智能体 knowledge/ 并回报结果（与本轮发送无关）。 */
    @Override
    public void importAttachmentToKnowledge(FileAttachment attachment) {
        if (attachment == null || attachment.base64Data() == null) {
            return;
        }
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(attachment.base64Data());
            String stored = knowledgeService.importDocument(
                    currentAgentCode(), attachment.fileName(), attachment.mediaType(), bytes, null);
            GimDialog.message(currentWindow(), I18n.t("knowledge.title"), null,
                    I18n.t("knowledge.attach.ok", attachment.fileName(), stored));
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            GimDialog.message(currentWindow(), I18n.t("knowledge.title"), null,
                    msg == null || msg.isBlank() ? I18n.t("common.error.unknown") : msg);
        }
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
        if (isBusy()) {
            return;
        }
        // 手动压缩在途：上下文正被改写，拒绝并发送入（core 侧 inflight 计数亦有兜底）
        if (statusBar.isCompacting()) {
            GimDialog.message(ownerWindow(), I18n.t("context.compact.title"), null, I18n.t("context.compact.wait"));
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
        // 对比模式：工具条勾选了 ≥2 个模型时走并行对比链路（只读、不落库、不进历史）
        if (toolbar.compareEnabled()) {
            sendCompare(text, hasText, hasImages, hasRefs, hasDocs);
            return;
        }
        // 发送前的两道硬拦：非视觉模型禁用图片发送、模型速率（RPM/TPM）超限节流。拦下时保留草稿不建会话
        String gateModel = effectiveModelForSend();
        if (hasImages && gateModel != null && !providerAdminService.modelSupportsVision(gateModel)) {
            GimDialog.message(ownerWindow(), I18n.t("vision.blocked.title"),
                    I18n.t("vision.blocked.title"), I18n.t("vision.blocked"));
            return;
        }
        String rateBlocked = gateModel == null ? null : rateGovernor.blockedReason(gateModel);
        if (rateBlocked != null) {
            GimDialog.message(ownerWindow(), I18n.t("rate.limit.title"),
                    I18n.t("rate.limit.title"), rateBlocked);
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
                .planMode(toolbar.planModeRequested())
                .build();
        rateGovernor.recordRequest(gateModel);
        runStream(chatService.chat(req), sessionId, req.isPlanMode());
        statusBar.updateContext();
    }

    /**
     * 对比发送：组装正文 → 上屏用户气泡 → 挂对比卡片 → 每列独立订阅网关流。
     * <p>对比固定只读模式（EXPLORE：读类工具正常、副作用工具被拒且不弹确认），不落库：
     * 每列走临时会话 id，与真实会话状态互不影响；文档附件暂不支持（正文抽取链路在 ChatService 内）。</p>
     */
    private void sendCompare(String text, boolean hasText, boolean hasImages, boolean hasRefs, boolean hasDocs) {
        List<com.gettyio.gim.agent.ui.chat.CompareCard.Target> targets = toolbar.compareTargets();
        if (hasDocs) {
            GimDialog.message(ownerWindow(), I18n.t("compare.menu.title"), null, I18n.t("compare.doc.unsupported"));
            return;
        }
        if (hasImages) {
            List<String> noVision = targets.stream()
                    .filter(t -> !providerAdminService.modelSupportsVision(t.modelCode()))
                    .map(com.gettyio.gim.agent.ui.chat.CompareCard.Target::label)
                    .toList();
            if (!noVision.isEmpty()) {
                GimDialog.message(ownerWindow(), I18n.t("vision.blocked.title"), I18n.t("vision.blocked.title"),
                        I18n.t("compare.vision.blocked", String.join("、", noVision)));
                return;
            }
        }
        for (com.gettyio.gim.agent.ui.chat.CompareCard.Target t : targets) {
            String rateBlocked = rateGovernor.blockedReason(t.modelCode());
            if (rateBlocked != null) {
                GimDialog.message(ownerWindow(), I18n.t("rate.limit.title"),
                        I18n.t("rate.limit.title"), rateBlocked);
                return;
            }
        }
        String body = hasText ? text : "";
        if (hasRefs) {
            String refBlock = attachmentBar.renderRefBlock();
            if (!refBlock.isEmpty()) {
                body = body.isEmpty() ? refBlock : body + "\n\n" + refBlock;
            }
        }
        List<ImageAttachment> images = attachmentBar.snapshotImages();
        inputField.clear();
        List<Node> fileRows = attachmentBar.buildFileRows(attachmentBar.previewDocRefs());
        attachmentBar.clearPending();
        // 与普通发送一致：用户气泡先上屏（不落库，主键 0）
        messageRenderer.appendUserBubble(body, attachmentBar.thumbsFromBase64(images), fileRows, 0);
        runCompare(body, images, targets);
        statusBar.updateContext();
    }

    /** 对比流的生命周期编排：逐列订阅 → 全部列终态后退出流式态；发出即清勾选（对比为一次性动作）。 */
    private void runCompare(String body, List<ImageAttachment> images,
                            List<com.gettyio.gim.agent.ui.chat.CompareCard.Target> targets) {
        String agentCode = currentAgentCode();
        com.gettyio.gim.agent.ui.chat.CompareCard card = new com.gettyio.gim.agent.ui.chat.CompareCard(targets);
        messageRenderer.appendNode(card.node());

        comparing = true;
        compareAgentCode = agentCode;
        compareSessions.clear();
        compareStreams.clear();
        compareDoneTotal = targets.size();
        compareCompleted = 0;
        enterStreamingUi(null);

        for (int i = 0; i < targets.size(); i++) {
            com.gettyio.gim.agent.ui.chat.CompareCard.Target t = targets.get(i);
            com.gettyio.gim.agent.ui.chat.CompareCard.Column col = card.column(i);
            // 临时会话 id：与真实会话隔离（不进历史，停止时按它逐个中断网关侧生成）
            String tempSession = "cmp-" + java.util.UUID.randomUUID();
            compareSessions.add(tempSession);
            ChatRequest req = ChatRequest.builder()
                    .agentCode(agentCode)
                    .sessionId(tempSession)
                    .text(body)
                    .providerCode(t.providerCode())
                    .modelCode(t.modelCode())
                    .images(images)
                    .permissionMode(PermissionMode.EXPLORE)
                    .build();
            rateGovernor.recordRequest(t.modelCode());
            Disposable stream = agentGateway.stream(req)
                    .bufferTimeout(256, FRAME_WINDOW)
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnCancel(() -> Platform.runLater(() -> {
                        col.stopped();
                        compareColumnDone();
                    }))
                    .subscribe(
                            batch -> Platform.runLater(() -> {
                                for (StreamEvent e : batch) {
                                    if (e.getType() == StreamEventType.TEXT_DELTA) {
                                        col.appendDelta(e.getText());
                                    } else if (e.getType() == StreamEventType.TOOL_START) {
                                        col.noteTool(e.getToolName());
                                    } else if (e.getType() == StreamEventType.USAGE) {
                                        col.setUsage(e.getUsage());
                                    }
                                }
                            }),
                            err -> Platform.runLater(() -> {
                                log.warn("对比流失败: model={}", t.modelCode(), err);
                                col.fail(err.getMessage());
                                compareColumnDone();
                            }),
                            () -> Platform.runLater(() -> {
                                col.complete();
                                compareColumnDone();
                            }));
            compareStreams.add(stream);
        }
        toolbar.clearCompareSelection();
        toolbar.renderCompareButton();
    }

    /** 停止对比：先打断各列网关侧的模型/工具循环，再取消订阅（doOnCancel 逐列收尾为「已停止」）。 */
    private void stopCompare() {
        String agent = compareAgentCode;
        for (String sid : List.copyOf(compareSessions)) {
            if (agent != null) {
                chatService.stop(agent, sid);
            }
        }
        for (Disposable stream : List.copyOf(compareStreams)) {
            stream.dispose();
        }
    }

    /** 一列终结（完成/出错/停止）：全部列终结后退出流式态并清在途记录。 */
    private void compareColumnDone() {
        compareCompleted++;
        if (compareCompleted < compareDoneTotal) {
            return;
        }
        comparing = false;
        compareAgentCode = null;
        compareStreams.clear();
        compareSessions.clear();
        exitStreamingUi();
    }

    /**
     * 本轮发送实际使用的模型编码（供 vision/速率判定）：工具条手选优先，否则取应用默认模型；
     * 两者皆空（未配置默认）时返回 {@code null}，判定层据此一律放行。
     */
    private String effectiveModelForSend() {
        String sel = toolbar.selectedModelCode();
        if (sel != null && !sel.isBlank()) {
            return sel;
        }
        return providerAdminService.getDefaultModelCode().orElse(null);
    }

    /**
     * 供启动接线/托盘唤回：聚焦主窗输入框并把光标移到文末（主窗显隐由生命周期处理）。
     * <p>仅在对话页时生效：切到定时任务/技能/用量页时输入框已脱离场景树，聚焦会失败并
     * 把焦点留在原处（可能正是侧栏导航项）。</p>
     */
    public void focusComposer() {
        if (inputField == null || navChat == null || !navChat.isSelected()) {
            return;
        }
        inputField.requestFocus();
        inputField.positionCaret(inputField.getLength());
    }

    /**
     * 截屏提问：冻结屏幕 → 拖框圈选区域 → 作为图片附件注入当前输入框（全局热键接线调用）。
     * <p>取消圈选或抓取失败时静默；成功注入后回焦输入框，等待用户补充问题再发送。</p>
     */
    public void captureScreenshotToComposer() {
        com.gettyio.gim.agent.ui.desktop.ScreenshotCapture.captureRegion(bytes ->
                Platform.runLater(() -> {
                    attachmentBar.addImage("screenshot.png", bytes);
                    if (inputField != null) {
                        inputField.requestFocus();
                    }
                }));
    }

    /**
     * 附件菜单「生成图片」：输入提示词 → 直插一轮本地对话（用户气泡 + 生成气泡）→ 后台调生图服务 →
     * 落库（{@link ChatService#insertLocalExchange}）并以 Markdown 图片语法展示。
     * <p>生成不占在途流（期间可继续对话）；落库先于展示。完成后若仍停在原会话且无流在途，
     * 整会话重放补齐主键；否则就地渲染当前气泡，切走再切回由重放自然补上。</p>
     */
    @Override
    public void requestImageGeneration() {
        if (!securityAdminService.isImageConfigured()) {
            GimDialog.message(ownerWindow(), I18n.t("image.dialog.title"), null, I18n.t("image.notConfigured"));
            return;
        }
        Optional<String> input = ImageGenerateDialog.show(ownerWindow());
        if (input.isEmpty()) {
            return;
        }
        String prompt = input.get();
        if (currentSessionId == null) {
            // 与发送首条消息同口径：生图也建会话，归属当前选中的智能体
            currentSessionId = chatService.createSession(currentAgentCode());
            refreshSessions();
        }
        String sid = currentSessionId;
        String agent = agentOfSession(sid);
        messageRenderer.appendUserBubble(prompt, List.of(), List.of(), 0);
        com.gettyio.gim.agent.ui.chat.AssistantBubble bubble = messageRenderer.appendAssistantBubble();
        bubble.appendPlain(I18n.t("image.generating"));
        Thread worker = new Thread(() -> {
            String assistantMd;
            try {
                byte[] bytes = imageGenerationClient.generate(prompt);
                Path file = ImageGenerationClient.save(gimPaths.generatedDir(), bytes);
                String mdPath = file.toAbsolutePath().toString().replace('\\', '/');
                assistantMd = "![" + imageAlt(prompt) + "](<" + mdPath + ">)";
            } catch (Exception e) {
                log.warn("图像生成失败", e);
                assistantMd = I18n.t("image.failed", String.valueOf(e.getMessage()));
            }
            String md = assistantMd;
            Platform.runLater(() -> {
                chatService.insertLocalExchange(sid, agent, prompt, md);
                refreshSessions();
                if (sid.equals(currentSessionId) && !isBusy()) {
                    renderSessionMessages(sid);
                } else {
                    bubble.renderMarkdown(md);
                }
            });
        }, "gim-image-gen");
        worker.setDaemon(true);
        worker.start();
    }

    /** 生成图 Markdown 的 alt 文本：压平换行与方括号并截断，避免破坏图片语法。 */
    private static String imageAlt(String prompt) {
        String t = prompt.replaceAll("[\\[\\]()\\r\\n]+", " ").trim();
        return t.length() <= 60 ? t : t.substring(0, 60) + "\u2026";
    }

    /**
     * 统一的流式收发：建一条空气泡，按帧增量渲染，收尾后从库重放（补齐主键与操作按钮）。
     * <p>{@code chat}/{@code regenerate}/{@code editAndResend} 三种入口都汇到这里；请求侧的落盘
     * （用户消息、删除旧助手）已在 {@code flux} 组装时于服务层同步完成，此处只订阅流。</p>
     */
    private void runStream(Flux<StreamEvent> flux, String sessionId) {
        runStream(flux, sessionId, false);
    }

    /**
     * 统一的流式收发（带规划轮标志）：当 {@code planTurn} 为真且本轮跑完后，
     * 若智能体工作区已生成 {@code plans/PLAN.md}，弹出可编辑面板供用户确认/修改计划。
     */
    private void runStream(Flux<StreamEvent> flux, String sessionId, boolean planTurn) {
        com.gettyio.gim.agent.ui.chat.AssistantBubble bubble = messageRenderer.appendAssistantBubble();
        StringBuilder acc = new StringBuilder();
        enterStreamingUi(sessionId);
        activeStream = flux
                .bufferTimeout(256, FRAME_WINDOW)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        batch -> Platform.runLater(() -> {
                            messageRenderer.applyBatch(batch, bubble, acc);
                            statusBar.updateUsageFromBatch(batch);
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
                            // 规划轮跑完：展示并让用户确认/编辑计划文件
                            if (planTurn) {
                                reviewPlan(agentOfSession(sessionId));
                            }
                            // 每轮收尾后查一次月度预算，超限当日提醒一次
                            checkMonthlyBudget();
                        })
                );
    }

    /**
     * 月度预算告警：流完成后在后台线程聚合当月估算费用，超限则托盘/弹窗提醒；
     * 去重（当日一次）与预算解析都在 {@link com.gettyio.gim.agent.ui.throttle.ModelRateGovernor}。
     */
    private void checkMonthlyBudget() {
        Schedulers.boundedElastic().schedule(() ->
                rateGovernor.budgetWarningIfExceeded()
                        .ifPresent(msg -> systemNotifier.notifyBudget(msg)));
    }

    /**
     * 规划轮跑完：顶部展示「计划待确认」横幅（PLAN.md 预览 + 编辑/确认执行），替代旧弹窗入口。
     */
    private void reviewPlan(String agentCode) {
        if (planBanner == null) {
            com.gettyio.gim.agent.ui.window.PlanReviewDialog.show(ownerWindow(), planService, agentCode);
            return;
        }
        planBannerAgent = agentCode;
        renderPlanPreview();
        planBanner.setVisible(true);
        planBanner.setManaged(true);
    }

    /** 重渲染横幅内的计划预览（首次展示与编辑保存后复用）。 */
    private void renderPlanPreview() {
        planPreview.getChildren()
                .setAll(com.gettyio.gim.agent.ui.chat.MarkdownRenderer.render(planService.read(planBannerAgent)));
    }

    /** 横幅「编辑」：弹出可编辑面板写回 PLAN.md，返回后就地刷新预览。 */
    @FXML
    public void handlePlanEdit() {
        if (planBannerAgent == null) {
            return;
        }
        com.gettyio.gim.agent.ui.window.PlanReviewDialog.show(ownerWindow(), planService, planBannerAgent);
        renderPlanPreview();
    }

    /** 横幅「确认执行」：收横幅、关工具条规划开关，自动发送「按计划继续执行」续聊。 */
    @FXML
    public void handlePlanConfirm() {
        hidePlanBanner();
        toolbar.setPlanModeRequested(false);
        inputField.setText(I18n.t("plan.banner.continue"));
        handleSend();
    }

    /** 收起计划确认横幅并清空归属记录（确认/切换会话/新任务共用）。 */
    private void hidePlanBanner() {
        if (planBanner != null) {
            planBanner.setVisible(false);
            planBanner.setManaged(false);
        }
        planBannerAgent = null;
    }

    /** 发送按钮的停止态入口：在途流存在则停止，否则正常发送（FXML 绑此方法）。 */
    @FXML
    public void handleSendOrStop() {
        if (isBusy()) {
            handleStop();
            return;
        }
        handleSend();
    }

    /** 是否有任何在途生成（普通流式或对比）：发送互斥与停止分流的统一判定。 */
    private boolean isBusy() {
        return activeStream != null || comparing;
    }

    /** 停止生成：对比在途时停对比；否则先打断网关侧的模型/工具循环，再取消订阅（doOnCancel 落盘半截应答）。 */
    private void handleStop() {
        if (comparing) {
            stopCompare();
            return;
        }
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

    /** 进入流式态：发送按钮原地变「停止」（Segoe Stop 图标、保持可点），记录在途会话。 */
    private void enterStreamingUi(String sessionId) {
        streamingSessionId = sessionId;
        Node graphic = sendButton.getGraphic();
        if (graphic instanceof Label icon) {
            icon.setText("\uE71A");
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
            icon.setText("\uE74A");
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
        hidePlanBanner();
        renderSessionMessages(sessionId);
        // 高亮当前会话行
        sidebar.renderSessions();
        updateHeaderTitle();
    }

    /** 已加载的最旧历史主键（游标）；0 表示无可补页 */
    private long oldestLoadedId;
    /** 还有更早历史未加载 */
    private boolean hasMoreOlder;
    /** 向上补页进行中（防抖） */
    private boolean loadingOlder;

    /**
     * 从库重放当前会话的消息：只拉最新一页（{@link ChatService#HISTORY_PAGE_SIZE}）避免长会话全量上屏，
     * 滚动到顶再向上补页；逐条按角色上屏，携带主键以供删除/编辑/重生成/分支。
     */
    private void renderSessionMessages(String sessionId) {
        // 先复位分页态：clear 会把 vvalue 拉回 0，不能让旧会话的补页态误触发
        hasMoreOlder = false;
        loadingOlder = false;
        oldestLoadedId = 0;
        messageRenderer.clear();
        if (sessionId == null) {
            return;
        }
        List<ChatConversation> history = chatService.recentHistory(sessionId, ChatService.HISTORY_PAGE_SIZE);
        for (ChatConversation c : history) {
            renderMessage(c, false);
        }
        hasMoreOlder = history.size() >= ChatService.HISTORY_PAGE_SIZE;
        if (!history.isEmpty() && history.get(0).getId() != null) {
            oldestLoadedId = history.get(0).getId();
        }
        statusBar.updateUsageFromSession(sessionId);
        statusBar.updateContext();
    }

    /** 渲染单条历史消息；{@code prepend=true} 插到列表头（向上补页，不触发 promote 与滚动）。 */
    private void renderMessage(ChatConversation c, boolean prepend) {
        boolean self = ConversationRole.USER.getCode().equalsIgnoreCase(c.getRole());
        String content = c.getContent() == null ? "" : c.getContent();
        long id = c.getId() == null ? 0L : c.getId();
        MessageMetadataCodec.MessageMetadata meta = MessageMetadataCodec.read(c.getMetadata());
        if (self) {
            List<StoredAttachment> atts = meta.attachments();
            List<StoredAttachment> imgs = atts.stream().filter(a -> !a.isFile()).toList();
            List<StoredAttachment> files = atts.stream().filter(a -> a.isFile()).toList();
            if (prepend) {
                messageRenderer.prependUserBubble(content, attachmentBar.thumbsFromStored(imgs),
                        attachmentBar.buildFileRows(files), id);
            } else {
                messageRenderer.appendUserBubble(content, attachmentBar.thumbsFromStored(imgs),
                        attachmentBar.buildFileRows(files), id);
            }
        } else {
            if (meta.interrupted() && !content.isEmpty()) {
                content = content + "\n" + I18n.t("chat.interrupted");
            }
            if (prepend) {
                messageRenderer.prependAssistantMarkdown(content, id, c.getToolCalls(), meta.thinking(), c.getCreatedAt());
            } else {
                messageRenderer.appendAssistantMarkdown(content, id, c.getToolCalls(), meta.thinking(), c.getCreatedAt());
            }
        }
    }

    /**
     * 滚动到顶补页：拉取游标之前的一页历史倒序插到头部，并按新增高度补偿滚动偏移，
     * 保持当前阅读位置不跳动。补满一整页才认为可能还有更早（与首页同口径）。
     */
    private void loadOlderMessages() {
        if (loadingOlder || !hasMoreOlder || isBusy()) {
            return;
        }
        String sid = currentSessionId;
        if (sid == null || oldestLoadedId <= 0) {
            return;
        }
        loadingOlder = true;
        List<ChatConversation> older = chatService.historyBefore(sid, oldestLoadedId, ChatService.HISTORY_PAGE_SIZE);
        hasMoreOlder = older.size() >= ChatService.HISTORY_PAGE_SIZE;
        if (older.isEmpty()) {
            loadingOlder = false;
            return;
        }
        scrollPane.layout();
        double contentH = messagesContainer.getHeight();
        double viewH = scrollPane.getViewportBounds().getHeight();
        double offset = scrollPane.getVvalue() * Math.max(0, contentH - viewH);
        // older 升序（旧→新）：倒序逐条 add(0)，最旧的最后插入即仍在最顶
        for (int i = older.size() - 1; i >= 0; i--) {
            renderMessage(older.get(i), true);
        }
        if (older.get(0).getId() != null) {
            oldestLoadedId = older.get(0).getId();
        }
        javafx.application.Platform.runLater(() -> {
            scrollPane.layout();
            double newContentH = messagesContainer.getHeight();
            double denom = Math.max(1, newContentH - viewH);
            scrollPane.setVvalue(Math.min(1.0, Math.max(0.0, (offset + (newContentH - contentH)) / denom)));
            loadingOlder = false;
        });
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

    /** 朗读助手消息：未配置时提示先设置；合成/播放的错误与结束统一恢复气泡按钮两态。 */
    @Override
    public void onSpeak(String rawText, Runnable onEnded) {
        if (!ttsService.isConfigured()) {
            GimDialog.message(ownerWindow(), I18n.t("chat.msg.speak"), null, I18n.t("voice.tts.notConfigured"));
            onEnded.run();
            return;
        }
        ttsService.speak(rawText, err -> {
            if (err != null && !err.isBlank()) {
                GimDialog.message(ownerWindow(), I18n.t("chat.msg.speak"), null, I18n.t("voice.tts.failed", err));
            }
            onEnded.run();
        });
    }

    @Override
    public void onSpeakStop() {
        ttsService.stop();
    }

    @Override
    public void onRegenerate() {
        if (isBusy()) {
            return;
        }
        if (statusBar.isCompacting()) {
            GimDialog.message(ownerWindow(), I18n.t("context.compact.title"), null, I18n.t("context.compact.wait"));
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
        ChatRequest req = buildRequest(sid, text);
        Flux<StreamEvent> flux = chatService.regenerate(req);
        renderSessionMessages(sid);
        runStream(flux, sid, req.isPlanMode());
    }

    @Override
    public void onEditResend(long conversationId, String currentText) {
        if (isBusy() || conversationId <= 0) {
            return;
        }
        if (statusBar.isCompacting()) {
            GimDialog.message(ownerWindow(), I18n.t("context.compact.title"), null, I18n.t("context.compact.wait"));
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
        ChatRequest req = buildRequest(sid, edited.get());
        Flux<StreamEvent> flux = chatService.editAndResend(req, conversationId);
        renderSessionMessages(sid);
        runStream(flux, sid, req.isPlanMode());
    }

    @Override
    public void onDelete(long conversationId) {
        if (isBusy() || conversationId <= 0) {
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

    @Override
    public void onFork(long conversationId) {
        if (isBusy() || conversationId <= 0) {
            return;
        }
        String sid = currentSessionId;
        if (sid == null) {
            return;
        }
        // 流式中不分支：新会话复制不到正在生成的尾巴，反而让用户误以为已在原会话续聊
        if (!GimDialog.confirm(ownerWindow(), I18n.t("chat.msg.fork"), I18n.t("chat.msg.forkConfirm"))) {
            return;
        }
        String newId = chatService.forkSession(sid, conversationId);
        refreshSessions();
        openSessionById(newId);
    }

    /** 把语音识别文本插入输入框光标处（留一个空格便于续写），并抢回焦点。 */
    private void insertVoiceText(String text) {
        int at = Math.max(0, inputField.getCaretPosition());
        inputField.insertText(at, text + " ");
        inputField.requestFocus();
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
                .planMode(toolbar.planModeRequested())
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
        hidePlanBanner();
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
        terminalDrawer.toggle();
    }

    /** 右上角工作区按钮：展开/收起右侧工作区栏（收起仅摘节点、树与阅读状态保留）。 */
    @FXML
    private void handleToggleWorkspace() {
        if (workspacePanel != null) {
            workspacePanel.toggle();
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

    // ===================== 会话导出（长图 / 独立 HTML） =====================

    /** 侧栏「导出长图」：弹保存框后以话题卡片 HTML 离屏快照为 PNG（成功/失败弹框反馈）。 */
    @Override
    public void exportSessionImage(String sessionId) {
        ChatSession session = exportService.findSession(sessionId).orElse(null);
        if (session == null) {
            GimDialog.message(ownerWindow(), I18n.t("session.export.image"), null, I18n.t("settings.export.noSession"));
            return;
        }
        File file = chooseExportTarget(I18n.t("session.export.image"), session, ".png", "PNG (*.png)");
        if (file == null) {
            return;
        }
        Path target = withExtension(file.toPath(), ".png");
        // 长图须在 FX 线程快照：先在后台备好 HTML，回 FX 线程交给导出器（其内部再异步写盘）
        Thread t = new Thread(() -> {
            String html;
            try {
                html = exportService.renderSessionHtml(sessionId);
            } catch (RuntimeException e) {
                log.warn("导出会话长图：HTML 渲染失败", e);
                Platform.runLater(() -> GimDialog.message(ownerWindow(), I18n.t("session.export.image"), null,
                        I18n.t("settings.data.fail", String.valueOf(e.getMessage()))));
                return;
            }
            Platform.runLater(() -> SessionImageExporter.export(ownerWindow(), html, target,
                    saved -> GimDialog.message(ownerWindow(), I18n.t("session.export.image"), null,
                            I18n.t("settings.export.done", saved.getFileName())),
                    err -> GimDialog.message(ownerWindow(), I18n.t("session.export.image"), null, err)));
        }, "session-image-render");
        t.setDaemon(true);
        t.start();
    }

    /** 侧栏「导出网页」：话题卡片式独立 HTML（样式与本地图片内嵌，单文件可分享）；渲染在后台线程。 */
    @Override
    public void exportSessionHtml(String sessionId) {
        ChatSession session = exportService.findSession(sessionId).orElse(null);
        if (session == null) {
            GimDialog.message(ownerWindow(), I18n.t("session.export.html"), null, I18n.t("settings.export.noSession"));
            return;
        }
        File file = chooseExportTarget(I18n.t("session.export.html"), session, ".html", "HTML (*.html)");
        if (file == null) {
            return;
        }
        Path target = withExtension(file.toPath(), ".html");
        Thread t = new Thread(() -> {
            try {
                Path saved = exportService.exportSessionHtml(sessionId, target);
                Platform.runLater(() -> GimDialog.message(ownerWindow(), I18n.t("session.export.html"), null,
                        I18n.t("settings.export.done", saved.getFileName())));
            } catch (RuntimeException e) {
                log.warn("导出会话网页失败", e);
                Platform.runLater(() -> GimDialog.message(ownerWindow(), I18n.t("session.export.html"), null,
                        I18n.t("settings.data.fail", String.valueOf(e.getMessage()))));
            }
        }, "session-html-export");
        t.setDaemon(true);
        t.start();
    }

    /** 弹「另存为」对话框（标题、建议文件名与扩展名过滤器按导出格式定制）；取消返回 null。 */
    private File chooseExportTarget(String title, ChatSession session, String ext, String filterText) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.setInitialFileName(exportService.suggestedFileName(
                session.getTitle() == null || session.getTitle().isBlank() ? session.getSessionId() : session.getTitle(),
                ext));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(filterText, "*" + ext));
        return chooser.showSaveDialog(ownerWindow());
    }

    /** 用户未按选中格式补后缀时自动补上（与设置页导出同规则）。 */
    private static Path withExtension(Path p, String ext) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(ext) ? p : Path.of(p.toString() + ext);
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

    /** 点对比按钮：委托工具条对比多选菜单（实现见 {@code ToolbarPickers}）。 */
    @FXML
    public void showCompareMenu() {
        toolbar.showCompareMenu();
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

    /** 点模板按钮：委托工具条模板菜单（实现见 {@code ToolbarPickers}）。 */
    @FXML
    public void showTemplateMenu() {
        toolbar.showTemplateMenu();
    }
}
