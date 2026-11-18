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

package com.gettyio.gim.agent.ui.controller;

import com.gettyio.gim.agent.core.chat.ChatRequest;
import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.ContextCompactionResult;
import com.gettyio.gim.agent.core.chat.FileAttachment;
import com.gettyio.gim.agent.core.knowledge.KnowledgeService;
import com.gettyio.gim.agent.core.plan.PlanService;
import com.gettyio.gim.agent.core.chat.ImageAttachment;
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
import com.gettyio.gim.agent.tools.image.ImageGenerationClient;
import com.gettyio.gim.agent.ui.theme.UiTheme;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.prefs.UserPrefs;
import com.gettyio.gim.agent.ui.config.AgentAdminService;
import com.gettyio.gim.agent.ui.config.PromptTemplateAdminService;
import com.gettyio.gim.agent.ui.config.ProviderAdminService;
import com.gettyio.gim.agent.ui.config.SecurityAdminService;
import com.gettyio.gim.agent.ui.export.ExportService;
import com.gettyio.gim.agent.ui.desktop.LocalOpenPolicy;
import com.gettyio.gim.agent.ui.chat.ChatStreamDispatcher;
import com.gettyio.gim.agent.ui.chat.payload.WebChatTool;
import com.gettyio.gim.agent.ui.view.ExtensionsView;
import com.gettyio.gim.agent.ui.view.TerminalView;
import com.gettyio.gim.agent.ui.view.TasksView;
import com.gettyio.gim.agent.ui.view.UsageView;
import com.gettyio.gim.agent.ui.window.dialog.AboutDialog;
import com.gettyio.gim.agent.ui.window.dialog.AgentDialog;
import com.gettyio.gim.agent.ui.window.AppShell;
import com.gettyio.gim.agent.ui.window.dialog.GimDialog;
import com.gettyio.gim.agent.ui.window.dialog.ImageGenerateDialog;
import com.gettyio.gim.agent.ui.window.dialog.SessionSearchDialog;
import com.gettyio.gim.agent.ui.window.settings.SettingsWindow;
import com.gettyio.gim.agent.ui.window.TitleBar;
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
import javafx.scene.control.TextInputControl;
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
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 主窗口控制器：侧边栏导航驱动页面切换（对话/定时任务/技能），
 * 对话页为会话列表切换 + 流式渲染（帧合并 ≥33ms）。
 * <p>侧栏顶部是智能体切换卡：选中的智能体既决定新建会话的归属，也决定
 * 下方会话列表展示哪一份数据（列表只列它的会话，行内不再重复标注）。</p>
 *
 * @author gogym
 */
public class MainController implements com.gettyio.gim.agent.ui.chat.sidebar.SessionSidebarHost,
        com.gettyio.gim.agent.ui.chat.MessageActionListener,
        com.gettyio.gim.agent.ui.chat.ChatWebView.ChatViewEventsHolder,
        com.gettyio.gim.agent.ui.chat.CompareRunner.Host {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    /** 未选择智能体时使用的内置默认智能体编码 */
    private static final String DEFAULT_AGENT = MultiAgentGateway.DEFAULT_AGENT_CODE;
    /** 帧合并窗口：至少每 33ms 刷新一次 */
    private static final Duration FRAME_WINDOW = Duration.ofMillis(33);
    /** 侧栏偏好键：记住上次选择的智能体，新建会话默认归它 */
    private static final String PREF_SIDEBAR_AGENT = "sidebar.agent";
    

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
    private StackPane chatHost;
    @FXML
    private VBox welcomePane;
    /** 「回到底部」悬浮按钮：WebView 滚动状态经桥回抛后显隐，点击平滑回底 */
    @FXML
    private Button scrollBottomBtn;
    @FXML
    private StackPane inputFieldHost;
    /** 输入框组件：JCEF contenteditable（支持彩色 emoji），替代旧 JavaFX TextArea */
    private com.gettyio.gim.agent.ui.jcef.JcefComposer inputField;
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
    /** 左下用户区：账号 chip（头像 + 账号名），点击直达设置「账号」页；头像取账号名首字 */
    @FXML
    private HBox userChip;
    @FXML
    private Label userAvatar;
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
    private com.gettyio.gim.agent.ui.chat.sidebar.SidebarToggle sidebarToggle;

    /** 协作类：聊天区整页 WebView 渲染宿主（历史/流式/对比/主题），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.ChatWebView chatView;

    /** 聊天消息 → WebView JSON 载荷构建器（附件内联 data URI、工具列解码），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.ChatMessagePayload payload;

    /** 消息区是否为空：由 WebView 经桥回抛 onEmpty 驱动，绑定欢迎页显隐 */
    private final javafx.beans.property.BooleanProperty chatEmpty =
            new javafx.beans.property.SimpleBooleanProperty(true);

    /** 流式临时气泡主键（负数递减，与落盘主键不冲突） */
    private long streamSeq;

    /** 协作类：输入框附件条（待发图片/文件引用、回形针菜单、缩略图），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.AttachmentBar attachmentBar;

    /** 拖拽投放提示层（拖入文件悬停时浮出「松开以添加附件」），在 initialize() 中构造 */
    private StackPane dropOverlay;
    private Label dropOverlayLabel;

    /** 协作类：工具条三个下拉（齿轮/模型/审批），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.ToolbarPickers toolbar;

    /** 协作类：侧栏（智能体切换卡 + 会话列表渲染/交互），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.sidebar.SessionSidebar sidebar;

    /** 协作类：底栏状态条（本轮用量 + 上下文占用），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.ChatStatusBar statusBar;

    /** 协作类：底部终端抽屉（展开/收起、进程与历史保留），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.TerminalDrawer terminalDrawer;

    /** 协作类：工作区右侧栏（文件树 + 就地阅读），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.workspace.WorkspacePanel workspacePanel;

    /** 协作类：会话历史分页（重放/补页/顶部回收游标），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.ChatHistoryPager historyPager;

    /** 协作类：会话导出命令（长图/独立网页），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.SessionExporter sessionExporter;

    /** 协作类：多模型对比运行器（发送校验/逐列订阅/停止分流），在 initialize() 中构造 */
    private com.gettyio.gim.agent.ui.chat.CompareRunner compareRunner;

    private final ChatService chatService;

    /** 网关直连：多模型对比绕开落盘层直接订阅各列流（只读、不进历史） */
    private final com.gettyio.gim.agent.core.chat.AgentGateway agentGateway;

    private final SettingsWindow settingsWindow;

    private final AppShell appShell;

    private final TasksView tasksView;

    private final ExtensionsView extensionsView;

    private final UsageView usageView;

    private final TerminalView terminalView;

    private final AgentAdminService agentAdminService;

    private final GimPaths gimPaths;

    private final ProviderAdminService providerAdminService;

    private final SecurityAdminService securityAdminService;

    /** 本地登录认证（软锁）：发送消息视为活跃使用，顺延免登录会话（写盘节流在服务内）。 */
    private final com.gettyio.gim.agent.domain.security.LocalAuthService localAuthService;

    /** 应用生命周期：齿轮菜单「退出登录」清除会话并在窗口内呈现登录页 */
    private final com.gettyio.gim.agent.ui.window.AppLifecycle appLifecycle;

    private final KnowledgeService knowledgeService;

    private final PlanService planService;

    private final PromptTemplateAdminService promptTemplateAdminService;

    private final com.gettyio.gim.agent.ui.throttle.ModelRateGovernor rateGovernor;

    private final com.gettyio.gim.agent.ui.notify.SystemNotifier systemNotifier;

    private final com.gettyio.gim.agent.ui.voice.VoiceInputService voiceInputService;

    private final com.gettyio.gim.agent.ui.voice.TtsService ttsService;

    /** 图像生成客户端：附件菜单「生成图片」入口调用（配置从加密 settings 实时取用） */
    private final ImageGenerationClient imageGenerationClient;

    /** 会话导出服务：侧栏「导出长图/导出网页」共用（Markdown/PDF/DOCX 走设置页数据维护卡片） */
    private final ExportService exportService;

    /** 读应用级设置（上下文 token 上限）；仅取 settingDao 的只读用途 */
    private final com.gettyio.gim.agent.domain.GimDatabase database;

    /** 由组合根在 FX 线程装配（FXML 控制器工厂返回本实例），参数顺序与字段声明一致。 */
    public MainController(ChatService chatService,
                          com.gettyio.gim.agent.core.chat.AgentGateway agentGateway,
                          SettingsWindow settingsWindow, AppShell appShell, TasksView tasksView,
                          ExtensionsView extensionsView, UsageView usageView, TerminalView terminalView,
                          AgentAdminService agentAdminService, GimPaths gimPaths,
                          ProviderAdminService providerAdminService, SecurityAdminService securityAdminService,
                          com.gettyio.gim.agent.domain.security.LocalAuthService localAuthService,
                          com.gettyio.gim.agent.ui.window.AppLifecycle appLifecycle,
                          KnowledgeService knowledgeService, PlanService planService,
                          PromptTemplateAdminService promptTemplateAdminService,
                          com.gettyio.gim.agent.ui.throttle.ModelRateGovernor rateGovernor,
                          com.gettyio.gim.agent.ui.notify.SystemNotifier systemNotifier,
                          com.gettyio.gim.agent.ui.voice.VoiceInputService voiceInputService,
                          com.gettyio.gim.agent.ui.voice.TtsService ttsService,
                          ImageGenerationClient imageGenerationClient,
                          ExportService exportService,
                          com.gettyio.gim.agent.domain.GimDatabase database) {
        this.chatService = chatService;
        this.agentGateway = agentGateway;
        this.settingsWindow = settingsWindow;
        this.appShell = appShell;
        this.tasksView = tasksView;
        this.extensionsView = extensionsView;
        // 知识页进入时默认跟随侧栏当前智能体（与新建会话归属同口径）
        extensionsView.bindCurrentAgent(this::currentAgentCode);
        this.usageView = usageView;
        this.terminalView = terminalView;
        this.agentAdminService = agentAdminService;
        this.gimPaths = gimPaths;
        this.providerAdminService = providerAdminService;
        this.securityAdminService = securityAdminService;
        this.localAuthService = localAuthService;
        this.appLifecycle = appLifecycle;
        this.knowledgeService = knowledgeService;
        this.planService = planService;
        this.promptTemplateAdminService = promptTemplateAdminService;
        this.rateGovernor = rateGovernor;
        this.systemNotifier = systemNotifier;
        this.voiceInputService = voiceInputService;
        this.ttsService = ttsService;
        this.imageGenerationClient = imageGenerationClient;
        this.exportService = exportService;
        this.database = database;
    }

    private final ObservableList<ChatSession> sessions = FXCollections.observableArrayList();
    private String currentSessionId;
    private boolean settingsOpen;
    /** 顶部卡片选中的智能体：新建会话归属它，它的会话构成侧栏列表，选中即写回偏好 */
    private String selectedAgentCode = DEFAULT_AGENT;
    /** 在途流式订阅：保留句柄以便用户点「停止」时取消；仅在 FX 线程读写 */
    private Disposable activeStream;
    /** 在途流所属会话：停止时需告知网关中断哪个会话的 Agent 循环 */
    private String streamingSessionId;
    /** 在途轮的临时气泡主键（与 {@link #streamingSessionId} 同生命周期）：重放后据此重建同一气泡继续接增量。 */
    private long streamingBubbleId;
    /**
     * 在途轮已推给前端的事件缓冲：本轮落库前它是唯一副本。
     * <p>整页重放只画已落库的消息，而 {@code chatView.clear()}/{@code renderHistory} 会清掉 JS 侧
     * 的临时气泡表——没有它，切页或换会话再回来就看不到思考与工具过程，只能等本轮跑完落库才
     * 「啪」地出现。</p>
     */
    private final com.gettyio.gim.agent.ui.chat.TurnReplayBuffer streamingReplay =
            new com.gettyio.gim.agent.ui.chat.TurnReplayBuffer();
    /**
     * 等整页重放上屏后要做的一次事（搜索命中跳转的消息主键；0 表示没有）。
     * <p>重放改到池线程构建、FX 上屏，DOM 何时换新不再由调用点决定，故这类「这页画完之后」的
     * 动作统一交 {@link #afterPagePainted(String)} 执行。</p>
     */
    private long pendingRevealId;
    /** 模板下拉菜单：复用单一实例，避免反复点击累积弹出 */
    private ContextMenu templateMenu;
    /** 消息右键菜单：复用单一实例，右键换目标时先收再弹，不叠出多个弹窗 */
    private ContextMenu chatContextMenu;
    /** 聊天页当前是否有选区（chat.js selectionchange 翻转上报；Ctrl+C 让位判断用）。 */
    private volatile boolean chatHasSelection;
    /** 横幅当前展示的计划归属智能体（编辑/确认时定位 PLAN.md） */
    private String planBannerAgent;

    /** 主窗基础最小宽度（与 GimAgentApplication 启动值同源）：810 聊天视口 + 290 窗口差。 */
    private static final double MIN_WINDOW_WIDTH = 1100;
    /** 右侧栏展开后的最小窗口宽度：窗口→分栏差约 290px、聊天区占分栏 62%（WorkspacePanel divider 0.62），
     *  810 / 0.62 + 290 ≈ 1597 → 取 1600（聊天视口 ≈ 812 > 810），展开时应保证消息列排版不变。 */
    private static final double MIN_WINDOW_WIDTH_WORKSPACE = 1600;

    @FXML
    private void initialize() {
        applyRoundedClip();
        applySidebarClip();
        applySidebarMarginClip();
        payload = new com.gettyio.gim.agent.ui.chat.ChatMessagePayload(gimPaths);
        chatView = new com.gettyio.gim.agent.ui.chat.ChatWebView(this, this);
        chatHost.getChildren().add(chatView.node());
        // 先推一次主题：与后续渲染同走 WebView 就绪队列，保证 setTheme 先于内容脚本执行
        chatView.refreshTheme();
        // 「回到底部」悬浮按钮：停靠消息区底部中央，离开最新消息时显现，点击平滑回底
        StackPane.setAlignment(scrollBottomBtn, javafx.geometry.Pos.BOTTOM_CENTER);
        StackPane.setMargin(scrollBottomBtn, new javafx.geometry.Insets(0, 0, 16, 0));
        scrollBottomBtn.setOnAction(e -> chatView.scrollToBottom());
        // 输入框：JCEF contenteditable 组件挂入 FXML 占位（彩色 emoji）；Enter 提交在组件内接线
        inputField = new com.gettyio.gim.agent.ui.jcef.JcefComposer();
        inputFieldHost.getChildren().add(inputField.node());
        inputField.setOnSubmit(this::handleSend);
        // 输入框选区变化：最近交互在输入框 → 聊天区选区镜像让位（Ctrl+C 归 composer 链处理）
        inputField.setOnSelectionChanged(() -> chatHasSelection = false);
        attachmentBar = new com.gettyio.gim.agent.ui.chat.AttachmentBar(
                attachChipsBox, inputField, attachButton, this);
        installFileDropTarget();
        toolbar = new com.gettyio.gim.agent.ui.chat.ToolbarPickers(
                themeMenuButton, modelButton, approvalButton, templateButton, compareButton, inputField,
                providerAdminService, securityAdminService, promptTemplateAdminService, this);
        // 工具条选择按会话持久化：菜单选中即写当前会话行（空态尚无行，建会话时随写）
        toolbar.setSessionPrefsSink(this::persistToolbarPrefs);
        sidebar = new com.gettyio.gim.agent.ui.chat.sidebar.SessionSidebar(
                sessionsBox, sessionsHeader, recentHeader, recentBox, recentSectionCaret,
                sessionsCount, agentSwitcher, agentAvatar, agentNameLabel, agentDescLabel,
                sessionsSectionCaret, agentAdminService, chatService, this);
        statusBar = new com.gettyio.gim.agent.ui.chat.ChatStatusBar(
                usageBar, contextBar, contextGauge, contextStats, inputField, chatService, rateGovernor, database,
                toolbar::selectedModelCode, this::currentSessionId,
                new com.gettyio.gim.agent.ui.chat.ChatStatusBar.ContextCompactor() {
                    /** 是否正在生成（压缩按钮可用态与并发拒绝依据）。 */
                    @Override
                    public boolean busy() {
                        return isBusy();
                    }

                    /** 压缩指定会话上下文（按会话归属智能体执行）。 */
                    @Override
                    public Mono<ContextCompactionResult> compact(String sessionId) {
                        return chatService.compact(agentOfSession(sessionId), sessionId);
                    }
                });
        historyPager = new com.gettyio.gim.agent.ui.chat.ChatHistoryPager(
                chatService, chatView, payload, statusBar, chatEmpty, this::currentSessionId, this::isBusy,
                this::afterPagePainted);
        sessionExporter = new com.gettyio.gim.agent.ui.chat.SessionExporter(exportService, this::ownerWindow);
        compareRunner = new com.gettyio.gim.agent.ui.chat.CompareRunner(this,
                toolbar, providerAdminService, rateGovernor, agentGateway, chatService, chatView,
                payload, attachmentBar, inputField, statusBar, localAuthService, chatEmpty);
        // 无消息时展示居中欢迎页：Java 侧在发送/重放/清空时直接置位，JS 就绪时亦经桥回抛 onEmpty 校准
        welcomePane.visibleProperty().bind(chatEmpty);
        welcomePane.managedProperty().bind(welcomePane.visibleProperty());
        // 输入变化即时刷新上下文占用条（预估下一轮的提示词体量）
        inputField.textProperty().addListener((obs, o, nv) -> statusBar.updateContext());
        // 按住说话：松开后转写文本插入输入框（QuickAsk 小窗同一套安装逻辑）
        com.gettyio.gim.agent.ui.voice.MicHoldButton.install(micButton, voiceInputService,
                this::insertVoiceText,
                () -> GimDialog.message(ownerWindow(), I18n.t("voice.title"), null, I18n.t("voice.notConfigured")),
                (title, msg) -> GimDialog.message(ownerWindow(), title, null, msg));

        // 自动化页「查看会话」：注入跳转回调（先切到会话归属的智能体，再打开会话）
        tasksView.setSessionOpener(this::openTaskSession);

        // 侧栏导航切换主区页面（对话 / 自动化 / 技能）
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
        // 账号 chip（头像 + 账号名）：点击直达设置「账号」页；完整设置菜单仍走右侧齿轮
        userChip.setOnMouseClicked(e -> handleOpenSettings(I18n.t("settings.menu.account")));
        // 审批全局默认先落（空态基准）；下面恢复会话时以会话行的档位优先
        toolbar.initApprovalFromDefault();
        refreshSessions();
        List<ChatSession> scoped = sidebar.sessionsOfAgent();
        if (scoped.isEmpty()) {
            // 该智能体还没有会话：留在欢迎页空态，发送首条消息时才建会话（不在库里堆空行）
            showBlankConversation();
        } else {
            switchToSession(scoped.get(0).getSessionId());
        }
        // 启动不自动置焦：聚焦完全交给用户首次点击（输入框与导航控件都不参与初始焦点交接）
        // Enter 提交兜底（场景级）：焦点不在输入区时按 Enter 亦提交，但不改变焦点归属
        inputFieldHost.sceneProperty().addListener((obs, o, sc) -> {
            if (sc != null) {
                installEnterFallback(sc);
                installChatCopyShortcut(sc);
            }
        });
        // 「会话」标题行点击：整块区域展开/收起
        sessionsHeader.setOnMouseClicked(e -> sidebar.toggleSessionsSection());
        // 「最近会话」标题行点击：平级独立区展开/收起
        recentHeader.setOnMouseClicked(e -> sidebar.toggleRecentSection());

        // 恢复侧栏收起/展开状态，并把开关按钮挂到标题栏
        sidebarToggle = new com.gettyio.gim.agent.ui.chat.sidebar.SidebarToggle(sidebarMargin, contentMargin, appShell);
        sidebarToggle.restore();

        // 底部终端抽屉：shell 进程退出时由视图回调宿主收起（进程与历史保留）
        terminalDrawer = new com.gettyio.gim.agent.ui.chat.TerminalDrawer(chatSplit, terminalView);
        terminalView.setOnCloseRequest(terminalDrawer::hide);

        // 工作区右侧栏：文件树 + 就地阅读；把聊天内联 HTML/SVG 产物预览从弹窗改导入本面板
        workspacePanel = new com.gettyio.gim.agent.ui.chat.workspace.WorkspacePanel(workspaceSplit, gimPaths);
        // 展开/收起前先校准窗口宽度：右栏占去分栏 38%，窗口过窄时聊天视口会跌破内容列完整阈值
        workspacePanel.setVisibilityGuard(this::applyWorkspaceStageGuard);
        // 产物视图历史交付目录按会话标题分组：标题与侧栏同源（会话列表），缺失回退会话 ID
        workspacePanel.setSessionTitleResolver(sid -> sessions.stream()
                .filter(s -> sid.equals(s.getSessionId()))
                .map(ChatSession::getTitle)
                .filter(t -> t != null && !t.isBlank())
                .findFirst().orElse(null));
        // 聊天分栏底线宽：右栏 divider 拖拽不得把聊天视口压到内容列完整阈值
        // （810 = 800 限宽列 + 10 滚动条，web/chat.css）之下，杜绝气泡挤压贴边
        chatSplit.setMinWidth(810);
        com.gettyio.gim.agent.ui.chat.workspace.ArtifactPreviewWindow.setPreviewSink(
                (owner, code, lang) -> workspacePanel.showArtifact(code, lang));
        // 文件树入口跟随启动时恢复的会话与智能体（欢迎页空态则显示所选智能体的工作区视图）
        followWorkspace(currentSessionId);

        applyI18n();
        toolbar.buildThemeMenu();
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

    /**
     * 系统拖拽投放：从资源管理器拖入文件/文件夹即成为待发附件——图片走 Base64 多模态、
     * pdf/docx/xlsx/txt/md/csv 走正文抽取、文件夹/其它文件走路径引用（与回形针菜单同一分流，
     * 见 {@link com.gettyio.gim.agent.ui.chat.AttachmentBar#addDroppedFiles}）。
     * JCEF 画布不消费 JavaFX 拖拽事件，在页面容器 chatPage 上统一拦截即可覆盖消息区与输入区；
     * 悬停期间在聊天区浮出提示层，离开或投放后收起。
     * <p>另挂一条 Windows 原生兜底通道：主窗是 {@code TRANSPARENT} 窗口，Glass 的 OLE 拖放
     * 注册在此形态下不生效（上面的 DragEvent 处理器实际收不到事件），改由
     * {@link com.gettyio.gim.agent.ui.desktop.NativeFileDrop} 的 DragAcceptFiles +
     * WM_DROPFILES 接管「从资源管理器拖入」，最终同样汇入 addDroppedFiles。两通道在系统层互斥，
     * 不会双份入列。</p>
     */
    private void installFileDropTarget() {
        dropOverlayLabel = new Label(I18n.t("chat.drop.hint"));
        dropOverlay = new StackPane(dropOverlayLabel);
        dropOverlay.getStyleClass().add("drop-overlay");
        dropOverlay.setMouseTransparent(true);
        dropOverlay.setVisible(false);
        chatHost.getChildren().add(dropOverlay);

        chatPage.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                dropOverlay.setVisible(true);
                e.consume();
            }
        });
        chatPage.setOnDragExited(e -> dropOverlay.setVisible(false));
        chatPage.setOnDragDropped(e -> {
            dropOverlay.setVisible(false);
            if (!e.getDragboard().hasFiles()) {
                return;
            }
            boolean done = attachmentBar.addDroppedFiles(e.getDragboard().getFiles()) > 0;
            e.setDropCompleted(done);
            e.consume();
        });

        // 原生拖放接管：注意节点 sceneProperty 在 Scene 构造时即置位，而 Scene.getWindow() 要等
        // Stage.setScene 才有值，且此后 sceneProperty 不会再触发——必须改听 scene.windowProperty
        chatPage.sceneProperty().addListener((obs, o, sc) -> {
            if (sc == null) {
                return;
            }
            sc.windowProperty().addListener((obs2, o2, w) -> {
                if (w != null) {
                    com.gettyio.gim.agent.ui.desktop.NativeFileDrop.install(w,
                            files -> attachmentBar.addDroppedFiles(files));
                }
            });
            // 场景可能已晚于窗口挂接（重复挂场景），直接补一次；install 内部幂等
            if (sc.getWindow() != null) {
                com.gettyio.gim.agent.ui.desktop.NativeFileDrop.install(sc.getWindow(),
                        files -> attachmentBar.addDroppedFiles(files));
            }
        });
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
        // 账号名/首字头像随语言与登录态刷新（无账号时回退本地化占位）
        refreshUserBar();
        welcomeTitle.setText(I18n.t("welcome.title"));
        welcomeSubtitle.setText(I18n.t("welcome.subtitle"));
        privacyHint.setText(I18n.t("input.privacy"));
        inputField.setPromptText(I18n.t("input.prompt"));
        if (dropOverlayLabel != null) {
            dropOverlayLabel.setText(I18n.t("chat.drop.hint"));
        }
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
        if (workspacePanel != null) {
            workspacePanel.applyI18n();
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

    /** 用户区刷新：头像取账号名首字（大写化），名称直显账号名；无账号数据时回退本地化占位。 */
    private void refreshUserBar() {
        String name = localAuthService.username();
        boolean named = name != null && !name.isBlank();
        userAvatar.setText(named ? name.substring(0, 1).toUpperCase() : "G");
        userNameLabel.setText(named ? name : I18n.t("user.local"));
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
        AboutDialog.show(currentWindow(), gimPaths);
    }

    /** 齿轮菜单底部「退出登录」：确认后清除会话并在窗口内呈现登录页（与设置页账号卡同一套登录门）。 */
    @Override
    public void signOut() {
        if (!GimDialog.confirm(currentWindow(), I18n.t("settings.account.signout"),
                I18n.t("settings.account.signout.confirm"))) {
            return;
        }
        appLifecycle.lockToLogin();
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
     * <p>有生成在途时不重建视图：忙态下本来就发不出下一轮（{@link #handleSend()} 互斥），
     * 清空只会抹掉用户点回来正想看的那轮思考过程。此时点它等于「回到对话」，跑完再点才是新任务。</p>
     */
    @FXML
    public void handleNewSession() {
        if (navChat != null) {
            navChat.setSelected(true);
        }
        String running = streamingSessionId;
        if (running != null) {
            if (!running.equals(currentSessionId)) {
                switchToSession(running);
            }
            Platform.runLater(inputField::requestFocus);
            return;
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

    /** 发送入口（Enter/按钮）：忙态与压缩在途直接拒绝；取输入框最新全文后转发送主体。 */
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
        // 输入框 DOM 是文本真相源：先经桥取回最新全文（键盘转发链路的异步窗口内镜像可能滞后）
        inputField.requestSend(this::doSendWithText);
    }

    /** 发送主体：拿到输入框最新文本后执行发送（桥回期间忙态可能变化，复查后放行）。 */
    private void doSendWithText(String text) {
        if (isBusy()) {
            return;
        }
        boolean hasText = text != null && !text.isBlank();
        boolean hasImages = attachmentBar.hasImages();
        boolean hasRefs = attachmentBar.hasRefs();
        boolean hasDocs = attachmentBar.hasDocs();
        if (!hasText && !hasImages && !hasRefs && !hasDocs) {
            return;
        }
        // 对比模式：工具条勾选了 ≥2 个模型时走并行对比链路（只读、不落库、不进历史）
        if (toolbar.compareEnabled()) {
            compareRunner.send(text, hasText, hasImages, hasRefs, hasDocs);
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
            // 工作区右栏即时挂上该会话的专属目录入口（归属当前智能体；首次产出前为空，收尾后自动刷新）
            followWorkspace(currentSessionId, currentAgentCode());
            refreshSessions();
            // 空态里已做的工具条选择随写进新会话行（模型/审批档）
            persistToolbarPrefs(toolbar.currentSessionPrefs());
        }
        List<ImageAttachment> images = attachmentBar.snapshotImages();
        List<FileAttachment> docs = attachmentBar.snapshotDocs();
        String refBlock = attachmentBar.renderRefBlock();
        String body = hasText ? text : "";
        if (!refBlock.isEmpty()) {
            body = body.isEmpty() ? refBlock : body + "\n\n" + refBlock;
        }

        inputField.clear();
        List<StoredAttachment> liveFiles = attachmentBar.previewDocRefs();
        attachmentBar.clearPending();
        // 先以无主键(0)上屏，收尾后统一从库重放补齐主键与操作能力；一旦上屏即非空态
        chatEmpty.set(false);
        chatView.appendMessage(payload.userLive(0, body, images, liveFiles));
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
     * Enter 提交兜底（场景级）：修补「点击发送按钮/侧栏等可聚焦控件后焦点漂移、
     * Enter 静默失效」——焦点不在输入区域时按 Enter（无 Shift）等同点击发送。
     * <p>不改变焦点归属（交互约定：点击哪里焦点去哪里，点击输入框才聚焦该框），
     * 只保证 Enter 在任何焦点下都能送达发送链路。</p>
     * <p>终端与 JavaFX 文本输入控件自带键盘语义（执行命令/回车确认），
     * 输入框本体由 {@code JcefComposer} 的键盘链处理，三者一律放行。</p>
     */
    private void installEnterFallback(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != KeyCode.ENTER || e.isShiftDown() || isInInputArea(scene.getFocusOwner())) {
                return;
            }
            e.consume();
            handleSend();
        });
    }

    /** 节点（焦点所有者）是否位于输入区：JCEF 输入框、JavaFX 文本输入控件、底部终端。 */
    private boolean isInInputArea(Node node) {
        Node terminalRoot = terminalView == null ? null : terminalView.getNode();
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == inputFieldHost || n == terminalRoot || n instanceof TextInputControl) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ctrl+C 兜底：聊天区是 JCEF(Chromium) 渲染，选中文本的复制属 Chromium 编辑命令，
     * 但键盘不经该渲染链（OSR 下合成按键不产生编辑效果——输入框编辑早已全走 JS 注入），
     * 故改为：JS 读回页面选区 → 既有 onAction('copy') 桥通道 → Java 写系统剪贴板
     * （与悬停操作条的复制按钮同源），无选区时 JS 侧不动作。
     * <p>让位规则：焦点在文本控件/终端时放行；输入框有选区且聊天区无选区（输入框的复制保留）、
     * 或最近一次选区交互发生在输入框（{@code chatHasSelection} 被 composer 侧重置）时放行；
     * 其余消费事件并触发聊天页复制。</p>
     */
    private void installChatCopyShortcut(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != KeyCode.C || !e.isShortcutDown()) {
                return;
            }
            Node focus = scene.getFocusOwner();
            Node terminalRoot = terminalView == null ? null : terminalView.getNode();
            if (focus instanceof TextInputControl || within(focus, terminalRoot)) {
                return;
            }
            if (within(focus, inputFieldHost) && inputField.hasSelection() && !chatHasSelection) {
                return;
            }
            e.consume();
            chatView.copySelection();
        });
    }

    /** 焦点节点是否位于指定祖先子树内（含自身）；{@code ancestor} 为 null 时恒为 false。 */
    private static boolean within(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
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
            followWorkspace(currentSessionId, currentAgentCode());
            refreshSessions();
            persistToolbarPrefs(toolbar.currentSessionPrefs());
        }
        String sid = currentSessionId;
        String agent = agentOfSession(sid);
        chatEmpty.set(false);
        chatView.appendMessage(payload.userLive(0, prompt, List.of(), List.of()));
        final long genId = --streamSeq;
        chatView.streamStart(genId);
        chatView.streamDelta(genId, I18n.t("image.generating"));
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
                    historyPager.render(sid);
                } else {
                    chatView.streamDelta(genId, "\n" + md);
                    chatView.streamEnd(genId);
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
     * 统一的流式收发（带规划轮标志）：当 {@code planTurn} 为真且本轮跑完后，
     * 若智能体工作区已生成 {@code plans/PLAN.md}，弹出可编辑面板供用户确认/修改计划。
     */
    private void runStream(Flux<StreamEvent> flux, String sessionId, boolean planTurn) {
        // 发送/重生成都算活跃使用：顺延免登录有效期（已锁定/已过期会话不受影响）
        localAuthService.touch();
        final long streamId = --streamSeq;
        // 本轮起始时刻：收尾时据此在工作区树里标出「本轮新增」的落盘文件
        final long turnStart = System.currentTimeMillis();
        chatEmpty.set(false);
        chatView.streamStart(streamId);
        StringBuilder acc = new StringBuilder();
        enterStreamingUi(sessionId);
        // 本轮的增量先记一份：整页重放把临时气泡清掉后，靠它把思考与工具过程接回同一气泡
        streamingBubbleId = streamId;
        streamingReplay.clear();
        activeStream = flux
                .bufferTimeout(256, FRAME_WINDOW)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        batch -> Platform.runLater(() -> {
                            // 批量是「先投进 FX 队列、后执行」：取消订阅只挡得住之后的批量，
                            // 已在队列里的那一帧仍会在 handleStop 之后落地。streamingBubbleId 由
                            // exitStreamingUi 归零，据此把死轮的尾巴拦掉，不再改写已定格的气泡，
                            // 也不把已落盘之外的增量塞回重放缓冲（切页重挂会把它当成在途内容）。
                            if (streamId != streamingBubbleId) {
                                return;
                            }
                            streamingReplay.record(batch);
                            applyBatchToChat(batch, streamId, acc);
                            statusBar.updateUsageFromBatch(batch);
                        }),
                        err -> {
                            log.warn("流式对话失败", err);
                            Platform.runLater(() -> {
                                // 半截应答已由 ChatService 的 doOnError 落盘，这里只补失败提示并定格临时气泡
                                String note = I18n.t("chat.failed", err.getMessage());
                                chatView.streamDelta(streamId, (acc.length() > 0 ? "\n" : "") + note);
                                chatView.streamEnd(streamId);
                                exitStreamingUi();
                                refreshSessions();
                                refreshWorkspaceAfterTurn(sessionId, turnStart);
                            });
                        },
                        () -> Platform.runLater(() -> {
                            exitStreamingUi();
                            refreshSessions();
                            // 完成：从库重放，令刚落的助手/用户气泡拿到真实主键与操作能力。
                            // 仅当用户还停在这轮所属会话——他可能已切到别的会话或另起新任务，
                            // 那时把旧会话刷到眼前等于抢走他正在看的页面。
                            if (Objects.equals(sessionId, currentSessionId)) {
                                historyPager.render(sessionId);
                            }
                            // 本轮新落的产物就地可见（面板收起时由展开时刷新兜底）
                            refreshWorkspaceAfterTurn(sessionId, turnStart);
                            // 规划轮跑完：展示并让用户确认/编辑计划文件
                            if (planTurn) {
                                reviewPlan(agentOfSession(sessionId));
                            }
                            // 每轮收尾后查一次月度预算，超限当日提醒一次
                            checkMonthlyBudget();
                        })
                );
    }

    /** 每轮对话收尾（完成/失败）：工作区右栏若正跟随该会话，就地重建会话/智能体入口刷新文件树。 */
    private void refreshWorkspaceAfterTurn(String sessionId, long turnStartMillis) {
        if (workspacePanel != null && sessionId != null && sessionId.equals(currentSessionId)) {
            workspacePanel.refreshCurrent(turnStartMillis);
        }
    }

    /**
     * 把一帧窗口内的事件分派给 WebView：顺序与合并规则见 {@link ChatStreamDispatcher}，
     * 段序与落盘分段一致，收尾整页重放时同一轮不会重新排版
     * （{@code acc} 只累加正文，供出错收尾定格）。
     */
    private void applyBatchToChat(List<StreamEvent> batch, long id, StringBuilder acc) {
        ChatStreamDispatcher.dispatch(batch, new ChatStreamDispatcher.Sink() {
            @Override
            public void text(String text) {
                acc.append(text);
                chatView.streamDelta(id, text);
            }

            @Override
            public void thinking(String text) {
                chatView.streamThinking(id, text);
            }

            @Override
            public void toolStart(WebChatTool tool) {
                chatView.streamToolStart(id, tool);
            }

            @Override
            public void toolEnd(WebChatTool tool) {
                chatView.streamToolEnd(id, tool);
            }

            @Override
            public void notice(String text) {
                chatView.streamNotice(id, text);
            }
        });
    }

    /**
     * 重放在途轮：按已记录的事件走与实时完全相同的分派通道，重建同主键临时气泡后增量继续写进这一轮，
     * 收尾时再由正常的整页重放换成带真实主键的正式气泡。
     * <p>由 {@link com.gettyio.gim.agent.ui.chat.ChatHistoryPager} 在整页上屏后回调：{@code renderHistory}
     * 清掉了临时气泡，而重放是池线程构建、异步上屏的，回调点就是唯一可靠的「页面已换新」时刻。
     * 缓冲还空着（刚发起、事件未到）也要重开气泡，否则第一条增量会写进一个不存在的气泡。</p>
     *
     * @param sessionId 刚上屏的会话；与在途会话不符则不动（用户在别看的那页不该被抢）
     */
    private void reattachRunningTurn(String sessionId) {
        if (streamingBubbleId == 0L || sessionId == null || !sessionId.equals(streamingSessionId)) {
            return;
        }
        chatView.streamStart(streamingBubbleId);
        applyBatchToChat(streamingReplay.snapshot(), streamingBubbleId, new StringBuilder());
    }

    /** 整页重放上屏后的接线（由分页器回调）：接回在途轮，再做等这页画完才成立的一次性动作。 */
    private void afterPagePainted(String sessionId) {
        reattachRunningTurn(sessionId);
        long revealId = pendingRevealId;
        pendingRevealId = 0L;
        if (revealId > 0) {
            chatView.reveal(revealId);
        }
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
            com.gettyio.gim.agent.ui.window.dialog.PlanReviewDialog.show(ownerWindow(), planService, agentCode);
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
                .setAll(com.gettyio.gim.agent.ui.chat.render.MarkdownRenderer.render(planService.read(planBannerAgent)));
    }

    /** 横幅「编辑」：弹出可编辑面板写回 PLAN.md，返回后就地刷新预览。 */
    @FXML
    public void handlePlanEdit() {
        if (planBannerAgent == null) {
            return;
        }
        com.gettyio.gim.agent.ui.window.dialog.PlanReviewDialog.show(ownerWindow(), planService, planBannerAgent);
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
        return activeStream != null || compareRunner.isRunning();
    }

    /** 侧栏「删除/分支会话」的前置判定：只有正被本轮改写的那条会话不能动，别的会话照常可管。 */
    @Override
    public boolean generating(String sessionId) {
        String sid = streamingSessionId;
        return activeStream != null && sid != null && sid.equals(sessionId);
    }

    /** 停止生成：对比在途时停对比；否则先打断网关侧的模型/工具循环，再取消订阅（doOnCancel 落盘半截应答）。 */
    private void handleStop() {
        if (compareRunner.isRunning()) {
            compareRunner.stop();
            return;
        }
        String session = streamingSessionId;
        if (session != null) {
            chatService.stop(agentOfSession(session), session);
        }
        Disposable stream = activeStream;
        long bubbleId = streamingBubbleId;
        exitStreamingUi();
        if (stream != null) {
            stream.dispose();
        }
        // 半截应答已由 doOnCancel 落盘，但临时气泡还停在「思考中」的呼吸态——补一次定格，
        // 把已收到的正文/思考/工具收成普通消息。bubbleId 必须在 exitStreamingUi 前取。
        if (bubbleId != 0L) {
            chatView.streamEnd(bubbleId);
        }
    }

    /** 进入流式态：发送按钮原地变「停止」（Segoe Stop 图标、保持可点），记录在途会话。 */
    @Override
    public void enterStreamingUi(String sessionId) {
        streamingSessionId = sessionId;
        Node graphic = sendButton.getGraphic();
        if (graphic instanceof Label icon) {
            icon.setText("\uE71A");
            icon.getStyleClass().add("send-stop");
        }
        sendButton.setTooltip(new Tooltip(I18n.t("chat.stop")));
    }

    /** 退出流式态：恢复发送图标并释放在途句柄与重放记录。 */
    @Override
    public void exitStreamingUi() {
        activeStream = null;
        streamingSessionId = null;
        streamingBubbleId = 0L;
        streamingReplay.clear();
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

    /** 让工作区右栏跟随会话：会话归属按会话列表解析；空态（无会话）跟随当前所选智能体。 */
    private void followWorkspace(String sessionId) {
        followWorkspace(sessionId, sessionId == null ? currentAgentCode() : agentOfSession(sessionId));
    }

    /**
     * 让工作区右栏跟随会话（显式智能体版）：建会话等「归属已知但列表尚未刷新」的入口直接用，
     * 避免依赖会话列表时序；工作区解析走 {@link AgentAdminService#workspaceDir(String)}（与运行时装配同源）。
     */
    private void followWorkspace(String sessionId, String agentCode) {
        if (workspacePanel != null) {
            workspacePanel.followSession(sessionId, agentAdminService.workspaceDir(agentCode));
        }
    }

    private void switchToSession(String sessionId) {
        currentSessionId = sessionId;
        restoreToolbarPrefs(sessionId);
        hidePlanBanner();
        historyPager.render(sessionId);
        // 高亮当前会话行
        sidebar.renderSessions();
        updateHeaderTitle();
        // 工作区右栏跟随会话：按会话归属智能体解析工作区，「当前会话」入口切到其专属目录（智能体产物实际落点）
        followWorkspace(sessionId);
    }

    /** 从会话行恢复工具条模型/审批档：空字段回退全局默认（跟随默认/安全页默认档）。 */
    private void restoreToolbarPrefs(String sessionId) {
        ChatSession s = sessionId == null ? null
                : database.sessionDao().findBySessionId(sessionId).orElse(null);
        toolbar.applySessionPrefs(s == null ? null : s.getModelCode(),
                s == null ? null : s.getApprovalMode());
    }

    /** 工具条选择变更：落当前会话行；无会话（新任务空态）只留内存，首条消息建会话时随写。 */
    private void persistToolbarPrefs(com.gettyio.gim.agent.ui.chat.ToolbarPickers.SessionPrefs prefs) {
        if (currentSessionId == null) {
            return;
        }
        database.sessionDao().updateToolbarPrefs(currentSessionId, prefs.modelCode(), prefs.approvalMode());
    }

    // ===================== 消息气泡操作：复制 / 重生成 / 编辑重发 / 删除 =====================

    /** 复制消息原文到系统剪贴板（悬停操作条与代码块复制共用入口）；空文本忽略。 */
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

    /** 重新生成最后一条回复：忙态/压缩中拒绝；取最后一条用户消息重发（core 侧先删旧助手消息）。 */
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
        historyPager.render(sid);
        runStream(flux, sid, req.isPlanMode());
    }

    /** 编辑重发：弹框取新文本，core 侧截断该条及其后消息后重新作答；取消或空白不动作。 */
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
        historyPager.render(sid);
        runStream(flux, sid, req.isPlanMode());
    }

    /** 删除单条消息（确认后落库删除并重放列表）；忙态或未落盘主键拒绝。 */
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
        historyPager.render(sid);
        refreshSessions();
    }

    /** 分支会话：确认后复制至该条为止的上下文为新会话并跳转；忙态拒绝。 */
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
        chatView.clear();
        chatEmpty.set(true);
        updateHeaderTitle();
        // 无会话：文件树收回到所选智能体的工作区视图（无「当前会话」入口）
        followWorkspace(null);
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
        // 标题栏常驻（归属聊天列）：新任务空态也要让聊天列右上角的终端/工作区按钮可达；
        // 无会话时标题留空，标签仍占位（HBox.hgrow）把按钮顶到最右。
        chatHeader.setVisible(true);
        chatHeader.setManaged(true);
    }

    /** 聊天列右上角终端按钮：在底部展开/收起终端抽屉（收起只摘除节点、进程与历史保留）。 */
    @FXML
    private void handleToggleTerminal() {
        terminalDrawer.toggle();
    }

    /** 聊天列右上角工作区按钮：展开/收起右侧工作区栏（收起仅摘节点、树与阅读状态保留）。 */
    @FXML
    private void handleToggleWorkspace() {
        if (workspacePanel != null) {
            workspacePanel.toggle();
        }
    }

    /**
     * 右侧栏展开/收起时的窗口守护（经 {@link com.gettyio.gim.agent.ui.chat.workspace.WorkspacePanel} 的显隐回调触发）：
     * 展开后聊天区只占分栏的 62%（divider 0.62），窗口过窄时聊天视口会跌破内容列完整阈值
     * （810 = 800 限宽列 + 10 滚动条）造成气泡挤压贴边。故展开时把最小宽度抬到
     * {@link #MIN_WINDOW_WIDTH_WORKSPACE}，不足则扩宽窗口（右缘将越出屏幕时先左移让位）；
     * 收起时恢复基础最小宽度（窗口宽度不回收，避免收起时二次跳变）。
     */
    private void applyWorkspaceStageGuard(boolean opening) {
        Window win = currentWindow();
        if (!(win instanceof javafx.stage.Stage st)) {
            return;
        }
        if (!opening) {
            st.setMinWidth(MIN_WINDOW_WIDTH);
            return;
        }
        double target = MIN_WINDOW_WIDTH_WORKSPACE;
        // 最大化/全屏由系统掌控尺寸，不程序扩窗（大屏最大化下视口本身充足）
        if (!st.isMaximized() && !st.isFullScreen() && st.getWidth() < target) {
            javafx.geometry.Rectangle2D vb = screenBoundsOf(win);
            double nx = Math.max(vb.getMinX(), Math.min(st.getX(), vb.getMaxX() - target));
            if (nx != st.getX()) {
                st.setX(nx);
            }
            st.setWidth(target);
        }
        st.setMinWidth(target);
    }

    /** 窗口所在屏幕的可视区边界（取与窗口相交的屏幕；无匹配回主屏），供扩窗的越屏守护使用。 */
    private static javafx.geometry.Rectangle2D screenBoundsOf(Window win) {
        javafx.stage.Screen hit = javafx.stage.Screen.getScreensForRectangle(
                        win.getX(), win.getY(), Math.max(1, win.getWidth()), Math.max(1, win.getHeight()))
                .stream().findFirst().orElse(null);
        return (hit == null ? javafx.stage.Screen.getPrimary() : hit).getVisualBounds();
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

    /** 自动化「查看会话」：刷新会话列表，切到该会话归属的智能体，再打开会话。 */
    private void openTaskSession(String sessionId) {
        refreshSessions();
        ChatSession target = sessions.stream()
                .filter(s -> sessionId.equals(s.getSessionId()))
                .findFirst().orElse(null);
        if (target == null) {
            return;
        }
        if (target.getAgentCode() != null) {
            selectAgent(target.getAgentCode());
        }
        openSessionById(sessionId);
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
            // 空态无会话可丢：切智能体后右栏也要跟到新智能体的工作区视图
            followWorkspace(null);
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
        sessionExporter.exportImage(sessionId);
    }

    /** 侧栏「导出网页」：话题卡片式独立 HTML（样式与本地图片内嵌，单文件可分享）；渲染在后台线程。 */
    @Override
    public void exportSessionHtml(String sessionId) {
        sessionExporter.exportHtml(sessionId);
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
                // 面板在池线程上调用本检索：多取几倍再按当前智能体过滤，展示条数由面板自己截
                keyword -> chatService.searchContent(keyword, SessionSearchDialog.MESSAGE_FETCH_LIMIT).stream()
                        .filter(h -> allowed.contains(h.sessionId()))
                        .toList(),
                s -> openSessionById(s.getSessionId()),
                this::openMessageHit);
    }

    /** 打开命中的消息：切到其所属会话，滚动与短暂高亮等这页上屏后由 {@link #afterPagePainted} 执行。 */
    private void openMessageHit(com.gettyio.gim.agent.domain.entity.MessageHit hit) {
        if (hit == null) {
            return;
        }
        pendingRevealId = hit.conversationId();
        openSessionById(hit.sessionId());
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

    // ===================== WebView 运行时事件回调（ChatViewEvents） =====================

    @Override
    public void chatReady() {
        // 页面脚本装载完成：ChatWebView 已自行补发就绪前入队的主题与渲染命令，此处无需额外处理。
    }

    @Override
    public void chatContext(long id, double x, double y) {
        Platform.runLater(() -> showChatContextMenu(id, x, y));
    }

    /** JS 回抛：收起消息右键菜单（JCEF OSR 区域点击不触发 FX 弹窗自动隐藏）。 */
    @Override
    public void chatDismissMenu() {
        if (chatContextMenu != null && chatContextMenu.isShowing()) {
            chatContextMenu.hide();
        }
    }

    @Override
    public void chatSelection(boolean hasSelection) {
        chatHasSelection = hasSelection;
    }

    @Override
    public void chatScrollTop() {
        Platform.runLater(() -> historyPager.loadOlder());
    }

    @Override
    public void chatScrollState(boolean atBottom, boolean scrollable) {
        boolean show = scrollable && !atBottom;
        scrollBottomBtn.setVisible(show);
        scrollBottomBtn.setManaged(show);
    }

    @Override
    public void chatTrimmed(long newOldestId) {
        historyPager.onTrimmed(newOldestId);
    }

    /**
     * 打开聊天正文里的链接：http(s) 交系统浏览器；{@code file:} URI / 工作区相对路径 / 本机绝对路径
     * 归一为本地文件后交系统程序打开。
     * <p>此前 {@code file:///} 一律走 {@code Desktop.browse}，产物路径点不开（且 href 早被前端消毒抹掉），
     * 「模型报了路径用户却拿不到文件」的最后一环就断在这里；越出工作区的绝对路径直接拒绝。</p>
     * <p>外链侧由 {@link LocalOpenPolicy#browse} 只放过 http(s)：链接文本同样出自模型，
     * {@code javascript:}／自定义 scheme 交给 {@code Desktop.browse} 之后做什么取决于本机注册了什么处理器。</p>
     */
    @Override
    public void chatLink(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String raw = url.trim();
        java.nio.file.Path local = localArtifactTarget(raw);
        if (local != null) {
            Platform.runLater(() -> openLocalFile(local));
            return;
        }
        LocalOpenPolicy.browse(raw);
    }

    /** 点击本地图片附件：在系统文件管理器中定位所在目录；data URI 无路径忽略。 */
    @Override
    public void chatImage(String path) {
        if (path == null || path.isBlank() || path.startsWith("data:")) {
            return;
        }
        Platform.runLater(() -> revealInFolder(path));
    }

    /**
     * 产物卡动作：{@code preview} 就地阅读、{@code reveal} 定位所在文件夹、其余按 {@code open}
     * 交系统默认程序。路径先归一并校验在工作区内，越界只记日志不打开。
     */
    @Override
    public void chatArtifact(String path, String action) {
        java.nio.file.Path file = localArtifactTarget(path);
        if (file == null) {
            log.warn("拒绝打开工作区外的产物路径: {}", path);
            return;
        }
        Platform.runLater(() -> {
            switch (action == null ? "open" : action) {
                case "preview" -> previewArtifact(file);
                case "reveal" -> revealInFolder(file.toString());
                // 「打开即执行」那类扩展名（脚本/批处理）不交回系统，改为应用内阅读：
                // 产物卡上的 .py 是常见的交付物，点了没反应比点了看到源码更让人怀疑程序坏了
                default -> {
                    if (!LocalOpenPolicy.openFile(file, "产物")) {
                        previewArtifact(file);
                    }
                }
            }
        });
    }

    /**
     * 产物预览去处：网页类（{@code ArtifactPreviewWindow.PREVIEW_LANGS} 白名单）进独立预览窗口——报表按整页排版，
     * 右栏窄列挤不下，且窗口自带「在浏览器打开」便于调试；其余类型仍在右侧栏就地阅读，
     * 无右栏时回落系统默认程序。
     */
    private void previewArtifact(java.nio.file.Path file) {
        if (com.gettyio.gim.agent.ui.chat.workspace.ArtifactPreviewWindow
                .showFile(ownerWindow(), file)) {
            return;
        }
        if (workspacePanel != null) {
            workspacePanel.openFile(file);
        } else {
            openLocalFile(file);
        }
    }

    /**
     * 把 {@code file:} URI、工作区相对路径与本机绝对路径归一为授权目录内的真实文件；
     * 非本地写法（http/mailto 等）、越界或文件不存在时返回 {@code null}。
     * <p>边界判定用 {@link LocalOpenPolicy#allowedPath}（跟随符号链接求规范形）：
     * 早先的 {@code normalize()+startsWith} 只比字符串，工作区里一个指向别处的链接就能把
     * 「越出工作区直接拒绝」变成一句空话。</p>
     */
    private java.nio.file.Path localArtifactTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        java.nio.file.Path workspaceRoot = gimPaths.workspaceDir().toAbsolutePath().normalize();
        java.nio.file.Path candidate;
        try {
            if (s.regionMatches(true, 0, "file:", 0, 5)) {
                candidate = java.nio.file.Paths.get(java.net.URI.create(s.replace(" ", "%20")));
            } else if (java.nio.file.Paths.get(s).isAbsolute()) {
                candidate = java.nio.file.Paths.get(s);
            } else {
                candidate = workspaceRoot.resolve(s);
            }
        } catch (Exception e) {
            return null;
        }
        java.nio.file.Path allowed = LocalOpenPolicy.allowedPath(candidate);
        return allowed != null && java.nio.file.Files.isRegularFile(allowed) ? allowed : null;
    }

    /** 交系统默认程序打开授权目录内的产物文件；越界与「打开即执行」类由策略拒绝。 */
    private void openLocalFile(java.nio.file.Path file) {
        LocalOpenPolicy.openFile(file, "产物");
    }

    @Override
    public void chatCopyCode(String text) {
        onCopy(text);
    }

    @Override
    public void chatPreviewCode(String code, String lang) {
        com.gettyio.gim.agent.ui.chat.workspace.ArtifactPreviewWindow.preview(ownerWindow(), code, lang);
    }

    @Override
    public void chatEmpty(boolean empty) {
        chatEmpty.set(empty);
    }

    /**
     * 在系统文件管理器中定位附件/本地图片所在目录（内联 data URI 无路径，跳过）。
     * <p>越出授权目录时回落附件目录：这里的输入同样来自渲染出来的文本，让它拉起任意位置的文件夹
     * 等于给模型一个「用 Explorer 打开我指定的目录」的原语；回落至少说明动作发生了，不至于点了没反应。</p>
     */
    private void revealInFolder(String path) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get(path).getParent();
            if (dir == null || !LocalOpenPolicy.revealDirectory(dir, "附件目录")) {
                LocalOpenPolicy.revealDirectory(gimPaths.attachmentsDir(), "附件目录");
            }
        } catch (Exception e) {
            log.warn("打开附件目录失败: {}", path, e);
        }
    }

    /**
     * 右键消息的原生菜单（复制/分支/删除）：{@code id} 为落盘主键，未落盘（{@code <=0}，如临时流式/新用户气泡）时分支/删除禁用；
     * 复制不受主键限制（选区优先，无选区读该行原文）。
     * 坐标取 WebView 客户端像素（WebView 撑满 chatHost，二者局部坐标等价），换算到屏幕坐标弹出。
     * <p>菜单复用单一实例：右键换目标先收再弹、条目重灌（动作闭包始终绑定当前消息主键）；
     * JCEF(OSR) 区域点击不触发 FX 弹窗自动隐藏，菜单外的按下/滚动由 JS 经 {@link #chatDismissMenu()} 回抛收起。</p>
     */
    private void showChatContextMenu(long id, double x, double y) {
        if (chatContextMenu == null) {
            chatContextMenu = new ContextMenu();
        }
        chatContextMenu.hide();
        MenuItem copy = new MenuItem(I18n.t("chat.msg.copy"));
        copy.setOnAction(e -> chatView.copyMessage(id));
        MenuItem fork = new MenuItem(I18n.t("chat.msg.fork"));
        fork.setDisable(id <= 0);
        fork.setOnAction(e -> onFork(id));
        MenuItem delete = new MenuItem(I18n.t("chat.msg.delete"));
        delete.setDisable(id <= 0);
        delete.setOnAction(e -> onDelete(id));
        chatContextMenu.getItems().setAll(copy, fork, delete);
        javafx.geometry.Point2D screen = chatHost.localToScreen(x, y);
        if (screen == null) {
            chatContextMenu.show(chatHost, x, y);
        } else {
            chatContextMenu.show(chatHost, screen.getX(), screen.getY());
        }
    }
}
