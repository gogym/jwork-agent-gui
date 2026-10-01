package com.gettyio.gim.agent.ui.controller;

import com.gettyio.gim.agent.core.chat.AttachmentCodec;
import com.gettyio.gim.agent.core.chat.ChatRequest;
import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.ImageAttachment;
import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.core.chat.StoredAttachment;
import com.gettyio.gim.agent.core.chat.StreamEvent;
import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.entity.ModelConfig;
import com.gettyio.gim.agent.domain.entity.ModelProviderConfig;
import com.gettyio.gim.agent.domain.enums.ConversationRole;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.enums.StreamEventType;
import com.gettyio.gim.agent.ui.UiTheme;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UserPrefs;
import com.gettyio.gim.agent.ui.config.AgentAdminService;
import com.gettyio.gim.agent.ui.config.ProviderAdminService;
import com.gettyio.gim.agent.ui.view.ExtensionsView;
import com.gettyio.gim.agent.ui.view.TasksView;
import com.gettyio.gim.agent.ui.window.AgentDialog;
import com.gettyio.gim.agent.ui.window.AppShell;
import com.gettyio.gim.agent.ui.window.GimDialog;
import com.gettyio.gim.agent.ui.window.SettingsWindow;
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
import javafx.scene.control.TextField;
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
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseEvent;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
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
public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    /** 未选择智能体时使用的内置默认智能体编码 */
    private static final String DEFAULT_AGENT = MultiAgentGateway.DEFAULT_AGENT_CODE;
    /** 帧合并窗口：至少每 33ms 刷新一次 */
    private static final Duration FRAME_WINDOW = Duration.ofMillis(33);
    /** 侧栏偏好键：记住上次选择的智能体，新建会话默认归它 */
    private static final String PREF_SIDEBAR_AGENT = "sidebar.agent";
    /** 智能体弹框的横向固定开销：弹框内边距 6×2 + 条目内边距 12×2（见 .context-menu / .menu-item） */
    private static final double AGENT_MENU_CHROME = 36;
    /** 卡片还没参与布局时的兜底弹框宽度：侧栏固定 250 扣掉左右内边距各 10 */
    private static final double AGENT_MENU_FALLBACK_WIDTH = 230;
    /** 弹框条目里的头像列：头像 26 + 头像与文字间距 8 */
    private static final double AGENT_ITEM_AVATAR_WIDTH = 34;
    /** 弹框条目里的图标列：Segoe 图标 15 + 图标与文字间距 8，向上取整留点余量 */
    private static final double AGENT_ITEM_ICON_WIDTH = 26;
    /** 单张附件图片的软上限：超过则不加入待发列表（8MB） */
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

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
    private VBox messagesContainer;
    @FXML
    private VBox welcomePane;
    @FXML
    private TextField inputField;
    @FXML
    private Button sendButton;
    @FXML
    private Button themeMenuButton;
    @FXML
    private VBox sessionsBox;
    @FXML
    private HBox sessionsHeader;
    @FXML
    private Button sessionsMenuButton;
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
    private TextField sessionSearchField;
    @FXML
    private Label navChatLabel;
    @FXML
    private Label navTasksLabel;
    @FXML
    private Label navSkillsLabel;
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
    @FXML
    private HBox chatHeader;
    @FXML
    private Label sessionTitleLabel;
    @FXML
    private FlowPane attachChipsBox;
    @FXML
    private Button attachButton;
    @FXML
    private Button modelButton;
    @FXML
    private Button approvalButton;

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
    private AgentAdminService agentAdminService;

    @Autowired
    private GimPaths gimPaths;

    @Autowired
    private ProviderAdminService providerAdminService;

    private final ObservableList<ChatSession> sessions = FXCollections.observableArrayList();
    private String currentSessionId;
    private boolean settingsOpen;
    /** 「会话」区域是否收起 */
    private boolean sessionsCollapsed;
    /** 会话搜索框是否展开：默认收起，从「⋯」菜单点「搜索」才显示 */
    private boolean searchOpen;
    /** 顶部卡片选中的智能体：新建会话归属它，它的会话构成侧栏列表，选中即写回偏好 */
    private String selectedAgentCode = DEFAULT_AGENT;
    /** 待发送的图片附件（与 attachChipsBox 芯片一一对应） */
    private final List<ImageAttachment> pendingImages = new ArrayList<>();
    /** 当前审批档位（工具调用权限模式）；由审批按钮弹菜单选择，仅影响下一条要发的消息 */
    private PermissionMode approvalMode = PermissionMode.DEFAULT;
    /** 审批选择菜单：复用单一实例（与齿轮菜单同策略），避免每次 new 导致旧弹出窗阴影/落点累积漂移 */
    private ContextMenu approvalMenu;
    /** 审批菜单最近一次关闭的时间戳（毫秒）：用于把「再点按钮」识别为关闭手势，避免自动收起后又被立即重开 */
    private long approvalMenuHiddenAt;
    /** 当前选中的模型（工具条模型菜单选择）；null 或跟随默认项表示不覆盖 */
    private ModelOption selectedModel;
    /** 模型选择菜单：复用单一实例，同审批菜单 */
    private ContextMenu modelMenu;
    /** 模型菜单最近一次关闭的时间戳（毫秒）：同审批菜单的开关切换 */
    private long modelMenuHiddenAt;

    @FXML
    private void initialize() {
        scrollPane.setFitToWidth(true);
        applyRoundedClip();
        // 无消息时展示居中欢迎页，有消息后自动隐藏
        welcomePane.visibleProperty().bind(Bindings.isEmpty(messagesContainer.getChildren()));
        welcomePane.managedProperty().bind(welcomePane.visibleProperty());

        // 侧栏导航切换主区页面（对话 / 定时任务 / 技能）
        mainPages.getChildren().setAll(chatPage);
        pageGroup.selectedToggleProperty().addListener((obs, o, t) -> {
            if (t == navTasks) {
                mainPages.getChildren().setAll(tasksView.getNode());
                tasksView.reload();
            } else if (t == navSkills) {
                mainPages.getChildren().setAll(extensionsView.getNode());
                extensionsView.reload();
            } else {
                mainPages.getChildren().setAll(chatPage);
            }
        });

        // 侧栏会话列表手动渲染（VBox），点击行即切换会话
        selectedAgentCode = restoreSelectedAgent();
        renderAgentSwitcher();
        agentSwitcher.setOnMouseClicked(e -> showAgentMenu());
        sessionSearchField.textProperty().addListener((obs, old, now) -> renderSessions());
        // 搜索框不常显：展开走会话组的「⋯」菜单，按 Esc 收起
        applySearchVisibility();
        sessionSearchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                searchOpen = false;
                applySearchVisibility();
            }
        });
        refreshSessions();
        List<ChatSession> scoped = sessionsOfAgent();
        if (scoped.isEmpty()) {
            // 该智能体还没有会话：留在欢迎页空态，发送首条消息时才建会话（不在库里堆空行）
            showBlankConversation();
        } else {
            switchToSession(scoped.get(0).getSessionId());
        }
        // 「会话」标题行点击：整块区域展开/收起
        sessionsHeader.setOnMouseClicked(e -> toggleSessionsSection());

        applyI18n();
        buildThemeMenu();
        // 输入框工具条：附件芯片行仅在有待发图时占位；模型与审批下拉填充可选项
        attachChipsBox.visibleProperty().bind(Bindings.isNotEmpty(attachChipsBox.getChildren()));
        attachChipsBox.managedProperty().bind(attachChipsBox.visibleProperty());
        renderModelButton();
        renderApprovalButton();
        // 语言切换：静态文案、智能体卡片与列表都需重刷（展示名与占位文案都是本地化的）
        I18n.onChange(lang -> {
            applyI18n();
            buildThemeMenu();
            renderAgentSwitcher();
            renderSessions();
            renderModelButton();
            renderApprovalButton();
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

    /** 依据当前语言刷新主窗口静态文案（FXML 中的文本节点）。 */
    private void applyI18n() {
        navChatLabel.setText(I18n.t("nav.newTask"));
        navTasksLabel.setText(I18n.t("nav.tasks"));
        navSkillsLabel.setText(I18n.t("nav.skills"));
        sectionSessions.setText(I18n.t("section.sessions"));
        sessionSearchField.setPromptText(I18n.t("sidebar.search.prompt"));
        userNameLabel.setText(I18n.t("user.local"));
        welcomeTitle.setText(I18n.t("welcome.title"));
        welcomeSubtitle.setText(I18n.t("welcome.subtitle"));
        privacyHint.setText(I18n.t("input.privacy"));
        inputField.setPromptText(I18n.t("input.prompt"));
    }

    /** 底部齿轮：弹出设置菜单（设置 / 外观▶ / 语言▶ / 关于），对标 Qoder 交互 */
    private void buildThemeMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem settings = new MenuItem(I18n.t("menu.settings"), UiTheme.icon("\uE713"));
        settings.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.CONTROL_DOWN));
        settings.setOnAction(e -> handleOpenSettings());

        Menu appearance = new Menu(I18n.t("menu.appearance"), UiTheme.icon("\uE790"));

        Menu modeMenu = new Menu(I18n.t("menu.mode"), UiTheme.icon("\uE706"));
        ToggleGroup modeGroup = new ToggleGroup();
        RadioMenuItem lightItem = new RadioMenuItem(I18n.t("menu.mode.light"));
        RadioMenuItem darkItem = new RadioMenuItem(I18n.t("menu.mode.dark"));
        lightItem.setToggleGroup(modeGroup);
        darkItem.setToggleGroup(modeGroup);
        (UiTheme.mode() == UiTheme.Mode.LIGHT ? lightItem : darkItem).setSelected(true);
        lightItem.setOnAction(e -> UiTheme.setMode(UiTheme.Mode.LIGHT));
        darkItem.setOnAction(e -> UiTheme.setMode(UiTheme.Mode.DARK));
        modeMenu.getItems().addAll(lightItem, darkItem);

        Menu fontMenu = new Menu(I18n.t("menu.font"), UiTheme.icon("\uE8D2"));
        ToggleGroup fontGroup = new ToggleGroup();
        RadioMenuItem smallItem = new RadioMenuItem(I18n.t("menu.font.small"));
        RadioMenuItem mediumItem = new RadioMenuItem(I18n.t("menu.font.medium"));
        RadioMenuItem largeItem = new RadioMenuItem(I18n.t("menu.font.large"));
        smallItem.setToggleGroup(fontGroup);
        mediumItem.setToggleGroup(fontGroup);
        largeItem.setToggleGroup(fontGroup);
        switch (UiTheme.fontSize()) {
            case SMALL -> smallItem.setSelected(true);
            case LARGE -> largeItem.setSelected(true);
            default -> mediumItem.setSelected(true);
        }
        smallItem.setOnAction(e -> UiTheme.setFontSize(UiTheme.FontSize.SMALL));
        mediumItem.setOnAction(e -> UiTheme.setFontSize(UiTheme.FontSize.MEDIUM));
        largeItem.setOnAction(e -> UiTheme.setFontSize(UiTheme.FontSize.LARGE));
        fontMenu.getItems().addAll(smallItem, mediumItem, largeItem);

        appearance.getItems().addAll(modeMenu, fontMenu);

        Menu langMenu = new Menu(I18n.t("menu.language"), UiTheme.icon("\uF2B7"));
        ToggleGroup langGroup = new ToggleGroup();
        for (I18n.Language lang : I18n.Language.values()) {
            RadioMenuItem item = new RadioMenuItem(lang.nativeName());
            item.setToggleGroup(langGroup);
            if (lang == I18n.current()) {
                item.setSelected(true);
            }
            item.setOnAction(e -> I18n.setLanguage(lang));
            langMenu.getItems().add(item);
        }

        MenuItem about = new MenuItem(I18n.t("menu.about"), UiTheme.icon("\uE946"));
        about.setOnAction(e -> showAbout());

        menu.getItems().addAll(settings, new SeparatorMenuItem(),
                appearance, langMenu, new SeparatorMenuItem(), about);
        // 左键/右键统一弹出菜单，固定锚定在按钮左上角；超出屏幕时 JavaFX 自动调整
        themeMenuButton.setOnAction(e -> showThemeMenu(menu));
        themeMenuButton.setOnContextMenuRequested(e -> {
            e.consume();
            showThemeMenu(menu);
        });

        // 全局快捷键 Ctrl+,（菜单未展开时也生效）
        Platform.runLater(() -> {
            Scene scene = currentScene();
            if (scene != null) {
                scene.getAccelerators().put(
                        new KeyCodeCombination(KeyCode.COMMA, KeyCombination.CONTROL_DOWN),
                        this::handleOpenSettings);
            }
        });
    }

    /** 弹出设置菜单：整体落在齿轮按钮左上角（菜单右上角对齐按钮右上角） */
    private void showThemeMenu(ContextMenu menu) {
        if (menu.isShowing()) {
            return;
        }
        Bounds b = themeMenuButton.localToScreen(themeMenuButton.getBoundsInLocal());
        if (b == null) {
            return;
        }
        // 先临时弹出以量取真实尺寸，再把菜单右上角精确对齐按钮右上角（左上角视图）
        menu.show(themeMenuButton, b.getMaxX(), b.getMinY());
        Parent skin = (Parent) menu.getSkin().getNode();
        skin.applyCss();
        skin.layout();
        double w = skin.getBoundsInLocal().getWidth();
        double h = skin.getBoundsInLocal().getHeight();
        if (w > 0) {
            menu.setX(b.getMaxX() - w);
        }
        if (h > 0) {
            menu.setY(b.getMinY() - h);
        }
    }

    /**
     * 获取当前场景：齿轮按钮常驻侧栏，即使切到技能/定时任务页（chatPage 已脱离场景树）仍可取到 Scene。
     */
    private Scene currentScene() {
        if (themeMenuButton != null && themeMenuButton.getScene() != null) {
            return themeMenuButton.getScene();
        }
        return chatPage == null ? null : chatPage.getScene();
    }

    /** 当前主窗口：作为对话框 owner（窗口模态 + 居中定位）。 */
    private Window currentWindow() {
        Scene scene = currentScene();
        return scene == null ? null : scene.getWindow();
    }

    private void showAbout() {
        GimDialog.message(currentWindow(), I18n.t("about.title"), I18n.t("about.header"),
                I18n.t("about.body", gimPaths.root()));
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
        renderSessions();
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
            renderAgentSwitcher();
            refreshSessions();
            dropForeignSession();
            // 模型列表可能在设置页被增删改，重刷工具条模型按钮
            renderModelButton();
        });
    }

    @FXML
    public void handleSend() {
        String text = inputField.getText();
        boolean hasText = text != null && !text.isBlank();
        boolean hasImages = !pendingImages.isEmpty();
        if (!hasText && !hasImages) {
            return;
        }
        if (currentSessionId == null) {
            // 首条消息才建会话，归属当前选中的智能体；先让侧栏看到这一行，不等回复跑完
            currentSessionId = chatService.createSession(currentAgentCode());
            refreshSessions();
        }
        ModelOption model = selectedModel;
        List<ImageAttachment> images = List.copyOf(pendingImages);
        String body = hasText ? text : "";

        inputField.clear();
        pendingImages.clear();
        attachChipsBox.getChildren().clear();
        sendButton.setDisable(true);
        appendUserBubble(body, thumbsFromBase64(images));
        Label assistantLabel = appendBubble("", false);
        StringBuilder acc = new StringBuilder();
        String sessionId = currentSessionId;
        String agentCode = agentOfSession(sessionId);

        ChatRequest req = ChatRequest.builder()
                .agentCode(agentCode)
                .sessionId(sessionId)
                .text(body)
                .providerCode(model == null ? null : model.providerCode())
                .modelCode(model == null ? null : model.modelCode())
                .images(images)
                .permissionMode(approvalMode)
                .build();

        chatService.chat(req)
                .bufferTimeout(256, FRAME_WINDOW)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        batch -> Platform.runLater(() -> applyBatch(batch, assistantLabel, acc)),
                        err -> {
                            log.warn("流式对话失败", err);
                            Platform.runLater(() -> {
                                assistantLabel.setText(I18n.t("chat.failed", err.getMessage()));
                                sendButton.setDisable(false);
                                refreshSessions();
                            });
                        },
                        () -> Platform.runLater(() -> {
                            sendButton.setDisable(false);
                            refreshSessions();
                        })
                );
    }

    /** 把一帧窗口内的事件合并刷入助手气泡 */
    private void applyBatch(List<StreamEvent> batch, Label assistantLabel, StringBuilder acc) {
        StringBuilder frame = new StringBuilder();
        for (StreamEvent e : batch) {
            if (e.getType() == StreamEventType.TEXT_DELTA && e.getText() != null) {
                frame.append(e.getText());
            } else if (e.getType() == StreamEventType.TOOL_START && e.getToolName() != null) {
                frame.append("\n").append(I18n.t("chat.toolCall", e.getToolName()));
            } else if (e.getType() == StreamEventType.CONFIRM_REQUEST && e.getToolName() != null) {
                frame.append("\n").append(I18n.t("chat.toolConfirm", e.getToolName()));
            }
        }
        if (frame.length() > 0) {
            acc.append(frame);
            assistantLabel.setText(acc.toString());
            scrollPane.layout();
            scrollPane.setVvalue(1.0);
        }
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
        messagesContainer.getChildren().clear();
        List<ChatConversation> history = chatService.history(sessionId);
        for (ChatConversation c : history) {
            boolean self = ConversationRole.USER.getCode().equalsIgnoreCase(c.getRole());
            String content = c.getContent() == null ? "" : c.getContent();
            if (self) {
                appendUserBubble(content, thumbsFromStored(AttachmentCodec.read(c.getMetadata())));
            } else {
                appendBubble(content, false);
            }
        }
        // 高亮当前会话行
        renderSessions();
        updateHeaderTitle();
    }

    /** 回到新任务空态：不选中任何会话，主区只留居中欢迎页 */
    private void showBlankConversation() {
        currentSessionId = null;
        messagesContainer.getChildren().clear();
        updateHeaderTitle();
    }

    /** 从库重载会话，再刷新侧栏渲染 */
    private void refreshSessions() {
        sessions.setAll(chatService.listSessions());
        renderSessions();
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
        boolean show = current != null && !title.isBlank();
        sessionTitleLabel.setText(title);
        chatHeader.setVisible(show);
        chatHeader.setManaged(show);
    }

    /**
     * 渲染侧栏会话列表：只显示当前选中智能体的会话（顶部卡片决定这一份数据），命中搜索词的才显示。
     * <p>手动重建 VBox 子节点（会话数量级为几十，无需虚拟化）。</p>
     */
    private void renderSessions() {
        if (sessionsBox == null) {
            return;
        }
        sessionsBox.getChildren().clear();
        String needle = sessionSearchField.getText() == null
                ? "" : sessionSearchField.getText().trim().toLowerCase();
        List<ChatSession> scoped = sessionsOfAgent();
        int shown = 0;
        for (ChatSession s : scoped) {
            if (needle.isEmpty() || (s.getTitle() != null
                    && s.getTitle().toLowerCase().contains(needle))) {
                sessionsBox.getChildren().add(buildSessionRow(s));
                shown++;
            }
        }
        sessionsCount.setText(String.valueOf(scoped.size()));
        if (shown == 0) {
            Label empty = new Label(I18n.t(needle.isEmpty() ? "sessions.empty.none" : "sessions.empty.search"));
            empty.getStyleClass().add("sessions-empty");
            sessionsBox.getChildren().add(empty);
        }
    }

    /**
     * 当前选中智能体的会话（{@code sessions} 已由 DAO 按最近更新排序）。
     * <p>agent_code 为空的脏数据按默认智能体处理，与 AgentAdminService.effectiveCode 一致，
     * 不至于在哪个智能体下都看不到它。</p>
     */
    private List<ChatSession> sessionsOfAgent() {
        String code = currentAgentCode();
        return sessions.stream()
                .filter(s -> code.equals(agentAdminService.effectiveCode(s.getAgentCode())))
                .toList();
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
    private String currentAgentCode() {
        if (selectedAgentCode == null || agentAdminService.find(selectedAgentCode).isEmpty()) {
            selectedAgentCode = DEFAULT_AGENT;
        }
        return selectedAgentCode;
    }

    /** 顶部卡片：选中智能体的头像/名称/描述；定义已被删除时 currentAgentCode 已回退到默认智能体 */
    private void renderAgentSwitcher() {
        if (agentSwitcher == null) {
            return;
        }
        AgentDefinition def = agentAdminService.find(currentAgentCode()).orElse(null);
        String name = def == null ? DEFAULT_AGENT : agentLabel(def);
        agentAvatar.setText(avatarText(name));
        agentNameLabel.setText(name);
        agentDescLabel.setText(agentDesc(def));
    }

    /**
     * 卡片点击：弹出智能体菜单（切换到某个智能体 / 配置当前 / 新建）。
     * <p>菜单只负责选人与进出表单，真正的会话过滤发生在 renderSessions。</p>
     */
    private void showAgentMenu() {
        ContextMenu menu = new ContextMenu();
        // 弹框宽度钉成卡片宽度。宽度写在菜单的 style 上：ContextMenuSkin 把菜单的
        // styleProperty 绑到弹框根节点，根节点是 Region，会采纳 -fx-*-width；
        // 而 setPrefWidth/setMaxWidth 走的是 PopupControl 的同名属性，弹框尺寸计算不读它
        double menuWidth = agentSwitcher.getWidth() > 0
                ? agentSwitcher.getWidth()
                : AGENT_MENU_FALLBACK_WIDTH;
        menu.setStyle(String.format(Locale.ROOT,
                "-fx-min-width: %.0fpx; -fx-pref-width: %.0fpx; -fx-max-width: %.0fpx;",
                menuWidth, menuWidth, menuWidth));
        double contentWidth = menuWidth - AGENT_MENU_CHROME;

        String current = currentAgentCode();
        for (AgentDefinition a : agentAdminService.list()) {
            menu.getItems().add(agentMenuItem(a, a.getAgentCode().equals(current), contentWidth));
        }
        menu.getItems().add(new SeparatorMenuItem());
        agentAdminService.find(current).ifPresent(def -> {
            MenuItem configure = commandMenuItem(I18n.t("sidebar.agent.configure"), "\uE70F", contentWidth);
            configure.setOnAction(e -> AgentDialog.edit(currentWindow(), agentAdminService, def)
                    .ifPresent(saved -> adoptAgent(saved.getAgentCode())));
            menu.getItems().add(configure);
        });
        MenuItem create = commandMenuItem(I18n.t("sidebar.agent.new"), "\uE710", contentWidth);
        create.setOnAction(e -> AgentDialog.edit(currentWindow(), agentAdminService, null)
                .ifPresent(saved -> adoptAgent(saved.getAgentCode())));
        menu.getItems().add(create);
        menu.show(agentSwitcher, Side.BOTTOM, 0, 0);
    }

    /**
     * 菜单条目：小头像 + 名称/描述两行。
     * <p>当前项靠主色加粗区分，不打勾：MenuItem 只有一个 graphic 槽位，勾选图标会顶掉头像。</p>
     */
    private MenuItem agentMenuItem(AgentDefinition a, boolean current, double contentWidth) {
        Label avatar = new Label(avatarText(agentLabel(a)));
        avatar.getStyleClass().add("agent-item-avatar");
        double textWidth = contentWidth - AGENT_ITEM_AVATAR_WIDTH;
        HBox graphic = itemContent(contentWidth, avatar, new VBox(1,
                cappedLabel(agentLabel(a), textWidth, "agent-item-name"),
                cappedLabel(agentDesc(a), textWidth, "agent-item-desc")));
        graphic.setAccessibleText(agentLabel(a));
        if (current) {
            graphic.getStyleClass().add("agent-item-current");
        }
        MenuItem item = new MenuItem();
        item.setGraphic(graphic);
        item.setOnAction(e -> selectAgent(a.getAgentCode()));
        return item;
    }

    /** 菜单底部的命令条目：图标 + 文案，与智能体条目共用同一套列宽 */
    private MenuItem commandMenuItem(String text, String glyph, double contentWidth) {
        HBox graphic = itemContent(contentWidth, UiTheme.icon(glyph),
                cappedLabel(text, contentWidth - AGENT_ITEM_ICON_WIDTH, "agent-item-name"));
        graphic.setAccessibleText(text);
        MenuItem item = new MenuItem();
        item.setGraphic(graphic);
        return item;
    }

    /**
     * 条目内容容器，宽度钉死。
     * <p>JavaFX 按列算条目宽度：图形列与文字列是相加关系，各自取全菜单最大值。
     * 所以文字一律画进图形里、条目自身不留文本，否则两列叠加会把弹框撑宽。</p>
     */
    private static HBox itemContent(double width, Node... children) {
        HBox box = new HBox(8, children);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinWidth(width);
        box.setPrefWidth(width);
        box.setMaxWidth(width);
        return box;
    }

    /** 限宽 + 省略号：超长的名称/描述就地截断，不把弹框顶开 */
    private static Label cappedLabel(String text, double maxWidth, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setMaxWidth(maxWidth);
        label.setEllipsisString("\u2026");
        return label;
    }

    /** 描述为空时给一句兜底文案，不让卡片出现空行 */
    private String agentDesc(AgentDefinition def) {
        if (def == null || def.getDescription() == null || def.getDescription().isBlank()) {
            return I18n.t("sidebar.agent.noDesc");
        }
        return def.getDescription();
    }

    /** 头像字：取名称首字符（中文即首字），空名称回退默认智能体编码的首字符 */
    private static String avatarText(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT_AGENT.substring(0, 1).toUpperCase(Locale.ROOT);
        }
        return name.trim().substring(0, 1).toUpperCase(Locale.ROOT);
    }

    /**
     * 切换智能体：写回偏好，重画卡片，会话列表换成它的会话。
     * <p>不趁手去换页面：在拓展/定时任务页改选智能体不应该把用户拉回对话页。</p>
     */
    private void selectAgent(String agentCode) {
        if (agentCode == null || agentCode.equals(selectedAgentCode)) {
            return;
        }
        selectedAgentCode = agentCode;
        UserPrefs.put(PREF_SIDEBAR_AGENT, agentCode);
        renderAgentSwitcher();
        refreshSessions();
        dropForeignSession();
    }

    /** 当前打开的会话若已不属于选中的智能体，就回到新任务空态（否则列表与主区不一致） */
    private void dropForeignSession() {
        if (currentSessionId == null) {
            return;
        }
        boolean belongs = sessionsOfAgent().stream()
                .anyMatch(s -> currentSessionId.equals(s.getSessionId()));
        if (!belongs) {
            showBlankConversation();
        }
    }

    /** 新建/编辑后就接管该智能体：重画卡片与会话列表，不弄丢还开着的会话 */
    private void adoptAgent(String agentCode) {
        selectedAgentCode = agentCode;
        UserPrefs.put(PREF_SIDEBAR_AGENT, agentCode);
        renderAgentSwitcher();
        refreshSessions();
        dropForeignSession();
    }

    /** 「会话」整块区域收起/展开（搜索框与列表联动，收起时不留空白） */
    private void toggleSessionsSection() {
        sessionsCollapsed = !sessionsCollapsed;
        applySearchVisibility();
        sessionsBox.setVisible(!sessionsCollapsed);
        sessionsBox.setManaged(!sessionsCollapsed);
        sessionsSectionCaret.setRotate(sessionsCollapsed ? -90 : 0);
    }

    /**
     * 搜索框显隐：既看「⋯」菜单的开关，也受「会话」区收起状态约束。
     * <p>收起时一并清掉关键词，否则列表被一个看不见的条件过滤着，没人知道为什么少了几行。</p>
     */
    private void applySearchVisibility() {
        boolean shown = searchOpen && !sessionsCollapsed;
        sessionSearchField.setVisible(shown);
        sessionSearchField.setManaged(shown);
        if (!shown) {
            sessionSearchField.setText("");
        }
    }

    /** 会话组右侧「⋯」：弹出菜单，目前只有「搜索」 */
    @FXML
    public void handleSessionsMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem search = new MenuItem(I18n.t("sidebar.search.menu"), UiTheme.icon("\uE721"));
        search.setOnAction(e -> {
            searchOpen = true;
            applySearchVisibility();
            Platform.runLater(sessionSearchField::requestFocus);
        });
        menu.getItems().add(search);
        menu.show(sessionsMenuButton, Side.BOTTOM, 0, 0);
    }

    /** 吃掉冒泡的点击：按钮在「会话」标题行里，不拦住顺手会把整块区域收起 */
    @FXML
    private void consumeClick(MouseEvent e) {
        e.consume();
    }

    /**
     * 会话行：标题 + 「⋯」更多按钮（悬停显示）。
     * <p>列表已按顶部选中的智能体过滤，行内不再重复标一个智能体标签。</p>
     */
    private HBox buildSessionRow(ChatSession s) {
        Label dot = new Label("\u2022");
        dot.getStyleClass().add("ws-session-dot");
        Label title = new Label(s.getTitle());
        title.getStyleClass().add("ws-session-name");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setEllipsisString("\u2026");
        HBox.setHgrow(title, Priority.ALWAYS);

        Button menuBtn = new Button();
        menuBtn.setGraphic(UiTheme.icon("\uE712"));
        menuBtn.getStyleClass().addAll("ws-mini-btn", "ws-session-menu");
        ContextMenu menu = sessionContextMenu(s);
        menuBtn.setOnAction(e -> menu.show(menuBtn, Side.RIGHT, 0, 0));

        HBox row = new HBox(6, dot, title, menuBtn);
        row.getStyleClass().add("ws-session-row");
        if (s.getSessionId().equals(currentSessionId)) {
            row.getStyleClass().add("ws-session-row-selected");
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOnMouseClicked(e -> openSession(s));
        return row;
    }

    /** 智能体卡片/菜单显示名：无名时回退编码 */
    private static String agentLabel(AgentDefinition a) {
        return a.getAgentName() == null || a.getAgentName().isBlank() ? a.getAgentCode() : a.getAgentName();
    }

    private void openSession(ChatSession s) {
        if (navChat != null) {
            navChat.setSelected(true);
        }
        switchToSession(s.getSessionId());
    }

    /** 会话行「⋯」菜单：重命名 + 删除（换智能体在顶部卡片上做，会话不再跟着智能体转移） */
    private ContextMenu sessionContextMenu(ChatSession s) {
        ContextMenu menu = new ContextMenu();
        MenuItem rename = new MenuItem(I18n.t("session.rename.menu"), UiTheme.icon("\uE70F"));
        rename.setOnAction(e -> renameSession(s));
        MenuItem del = new MenuItem(I18n.t("common.delete"), UiTheme.icon("\uE74D"));
        del.setOnAction(e -> confirmDeleteSession(s));
        menu.getItems().addAll(rename, del);
        return menu;
    }

    /** 重命名会话：弹框预填当前标题；名字没改就不写库，也不重画列表 */
    private void renameSession(ChatSession s) {
        GimDialog.promptText(currentWindow(), I18n.t("session.rename.title"),
                        I18n.t("session.rename.label"), s.getTitle(), ChatService.TITLE_MAX_LEN)
                .filter(t -> !t.equals(s.getTitle()))
                .ifPresent(t -> {
                    chatService.renameSession(s.getSessionId(), t);
                    refreshSessions();
                });
    }

    private void confirmDeleteSession(ChatSession s) {
        if (GimDialog.confirm(currentWindow(), I18n.t("common.delete"),
                I18n.t("session.deleteConfirm", s.getTitle()))) {
            chatService.deleteSession(s.getSessionId());
            if (s.getSessionId().equals(currentSessionId)) {
                showBlankConversation();
            }
            refreshSessions();
            if (currentSessionId == null) {
                List<ChatSession> scoped = sessionsOfAgent();
                if (!scoped.isEmpty()) {
                    switchToSession(scoped.get(0).getSessionId());
                }
            }
        }
    }

    private Label appendBubble(String content, boolean self) {
        Label label = new Label(content);
        label.setWrapText(true);
        label.getStyleClass().add(self ? "bubble-user" : "bubble-assistant");
        label.setMaxWidth(680);

        HBox row = new HBox(label);
        row.getStyleClass().add("bubble-row");
        if (self) {
            row.setAlignment(Pos.TOP_RIGHT);
        }
        messagesContainer.getChildren().add(row);
        scrollPane.layout();
        scrollPane.setVvalue(1.0);
        return label;
    }

    // ===================== 输入框工具条：附件 / 模型 / 审批 =====================

    /** 回形针按钮：多选图片文件，读成 Base64 加入待发列表并渲染缩略图芯片。 */
    @FXML
    public void handleAttach() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("input.attach"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.t("attach.filter"), "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"));
        List<File> files = chooser.showOpenMultipleDialog(currentWindow());
        if (files == null || files.isEmpty()) {
            return;
        }
        for (File f : files) {
            addPendingImage(f);
        }
        inputField.requestFocus();
    }

    private void addPendingImage(File file) {
        if (file.length() > MAX_IMAGE_BYTES) {
            GimDialog.message(currentWindow(), I18n.t("input.attach"), I18n.t("input.attach"),
                    I18n.t("attach.tooLarge", file.getName()));
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            ImageAttachment att = new ImageAttachment(file.getName(), mimeOf(file.getName()),
                    Base64.getEncoder().encodeToString(bytes));
            pendingImages.add(att);
            attachChipsBox.getChildren().add(buildChip(att, bytes));
        } catch (IOException e) {
            log.warn("读取图片失败: {}", file, e);
        }
    }

    /** 待发附件芯片：小缩略图 + 右上角移除标记；点标记即从待发列表与芯片行同时移除。 */
    private HBox buildChip(ImageAttachment att, byte[] bytes) {
        ImageView iv = thumbView(bytes);
        iv.setFitWidth(48);
        iv.setFitHeight(48);
        Label remove = new Label("\u00D7");
        remove.getStyleClass().add("attach-remove");
        HBox chip = new HBox(2, iv, remove);
        chip.getStyleClass().add("attach-chip");
        chip.setAlignment(Pos.CENTER);
        remove.setOnMouseClicked(e -> {
            pendingImages.remove(att);
            attachChipsBox.getChildren().remove(chip);
        });
        return chip;
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        if (n.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    /** 模型菜单可选项：首项「跟随默认」（不覆盖），其余为已启用且连通测试可用的模型；文案随语言解析。 */
    private List<ModelOption> modelOptions() {
        List<ModelOption> out = new ArrayList<>();
        out.add(new ModelOption(null, null, I18n.t("model.followDefault"),
                "\uEC92", I18n.t("model.followDefault.desc"), false));
        Map<String, String> providerNames = new LinkedHashMap<>();
        for (ModelProviderConfig p : providerAdminService.listProviders()) {
            providerNames.put(p.getProviderCode(), p.getProviderName());
        }
        for (ModelConfig m : providerAdminService.listModels()) {
            if (!EnabledStatus.isEnabled(m.getEnabled())) {
                continue;
            }
            // 只列可用模型：未测试/不可用的先去设置里测通连接再来选，避免选了打不通的模型
            if (!ProviderAdminService.MODEL_STATUS_VALID.equalsIgnoreCase(m.getStatus())) {
                continue;
            }
            if (m.getProviderCode() == null || m.getProviderCode().isBlank()) {
                continue;
            }
            String label = m.getModelName() == null || m.getModelName().isBlank()
                    ? m.getModelCode() : m.getModelName();
            String provider = providerNames.get(m.getProviderCode());
            String desc = provider == null || provider.isBlank() ? m.getProviderCode() : provider;
            out.add(new ModelOption(m.getProviderCode(), m.getModelCode(), label, "\uE99A", desc,
                    Integer.valueOf(1).equals(m.getSupportsVision())));
        }
        return out;
    }

    /** 模型按钮闭合态：图标 + 模型名 + 小箭头。 */
    private void renderModelButton() {
        if (modelButton == null) {
            return;
        }
        List<ModelOption> options = modelOptions();
        String keepCode = selectedModel == null ? null : selectedModel.modelCode();
        selectedModel = options.stream()
                .filter(o -> keepCode == null ? o.followDefault() : keepCode.equals(o.modelCode()))
                .findFirst().orElse(options.get(0));
        Label icon = UiTheme.icon(selectedModel.icon());
        icon.getStyleClass().add("approval-icon");
        Label title = new Label(selectedModel.label());
        title.getStyleClass().add("approval-title-compact");
        title.setMaxWidth(180);
        title.setEllipsisString("\u2026");
        Label caret = UiTheme.icon("\uE70D");
        caret.getStyleClass().add("approval-caret");
        HBox box = selectedModel.vision()
                ? new HBox(6, icon, title, visionMark(), caret)
                : new HBox(6, icon, title, caret);
        box.setAlignment(Pos.CENTER_LEFT);
        modelButton.setGraphic(box);
        modelButton.setText("");
    }

    /** 点模型按钮：向上弹出模型选择卡（图标 + 名称/提供商两行，当前项右侧带勾，底部「模型管理」入口）。
     * 复用单实例 + 开关切换，与审批菜单同款。 */
    @FXML
    public void showModelMenu() {
        if (modelMenu == null) {
            modelMenu = new ContextMenu();
            modelMenu.getStyleClass().add("rich-menu");
            modelMenu.setOnHiding(e -> modelMenuHiddenAt = System.currentTimeMillis());
        }
        if (modelMenu.isShowing()) {
            modelMenu.hide();
            return;
        }
        if (System.currentTimeMillis() - modelMenuHiddenAt < 250) {
            return;
        }
        List<ModelOption> options = modelOptions();
        modelMenu.getItems().setAll(
                options.stream().map(opt -> modelMenuItem(opt, opt.equals(selectedModel))).toList());
        modelMenu.getItems().add(new SeparatorMenuItem());
        // 「模型管理」也画进 graphic（MenuItem 不设 text），避免 text 列拉高 maxLabelWidth 把上方富行的勾挤离右缘
        Label manageIcon = UiTheme.icon("\uE713");
        manageIcon.getStyleClass().add("approval-icon");
        Label manageText = new Label(I18n.t("model.manage"));
        HBox manageBox = new HBox(10, manageIcon, manageText);
        manageBox.setAlignment(Pos.CENTER_LEFT);
        manageBox.setMinWidth(200);
        manageBox.setPrefWidth(200);
        manageBox.setMaxWidth(200);
        MenuItem manage = new MenuItem();
        manage.setGraphic(manageBox);
        manage.getStyleClass().add("model-manage");
        manage.setOnAction(e -> handleOpenSettings(I18n.t("settings.menu.models")));
        modelMenu.getItems().add(manage);
        showMenuAboveWithinWindow(modelMenu, modelButton);
    }

    /** 模型菜单项：图标 + 名称/提供商两行，支持多模态的名称右侧加图片小标，当前选中右侧带勾。 */
    private MenuItem modelMenuItem(ModelOption opt, boolean checked) {
        Label icon = UiTheme.icon(opt.icon());
        icon.getStyleClass().add("approval-icon");
        Label title = new Label(opt.label());
        title.getStyleClass().add("approval-title");
        Label desc = new Label(opt.desc());
        desc.getStyleClass().add("approval-desc");
        // 说明保持单行（不换行）；超长时由 StackPane 布局压缩截断，不挤压右侧勾选
        return twoLineMenuItem(icon, title, opt.vision() ? visionMark() : null, desc, checked, 200, () -> {
            selectedModel = opt;
            renderModelButton();
        });
    }

    /** 多模态小图标：E8B9 图片字形（蓝色），紧跟模型名称右侧，悬停提示「支持」。 */
    private Label visionMark() {
        Label mark = UiTheme.icon("\uE8B9");
        mark.getStyleClass().add("model-vision");
        mark.setMinWidth(Region.USE_PREF_SIZE);
        mark.setTooltip(new Tooltip(I18n.t("settings.model.vision.supported")));
        return mark;
    }

    /** 审批三档：询问(DEFAULT)/自动(BYPASS)/完全访问(DONT_ASK)；文案随语言解析。 */
    private List<ApprovalOption> approvalOptions() {
        return List.of(
                new ApprovalOption(PermissionMode.DEFAULT, I18n.t("approval.ask"),
                        "\uEA18", I18n.t("approval.ask.desc"), false),
                new ApprovalOption(PermissionMode.BYPASS, I18n.t("approval.auto"),
                        "\uE946", I18n.t("approval.auto.desc"), false),
                new ApprovalOption(PermissionMode.DONT_ASK, I18n.t("approval.dontAsk"),
                        "\uE7BA", I18n.t("approval.dontAsk.desc"), true));
    }

    /** 审批按钮闭合态：图标 + 标题 + 小箭头；危险档图标与标题标红。 */
    private void renderApprovalButton() {
        if (approvalButton == null) {
            return;
        }
        ApprovalOption opt = approvalOptions().stream()
                .filter(o -> o.mode() == approvalMode).findFirst()
                .orElseGet(() -> approvalOptions().get(0));
        Label icon = UiTheme.icon(opt.icon());
        icon.getStyleClass().add("approval-icon");
        Label title = new Label(opt.label());
        title.getStyleClass().add("approval-title-compact");
        if (opt.danger()) {
            icon.getStyleClass().add("approval-icon-danger");
            title.getStyleClass().add("approval-title-danger");
        }
        Label caret = UiTheme.icon("\uE70D");
        caret.getStyleClass().add("approval-caret");
        HBox box = new HBox(6, icon, title, caret);
        box.setAlignment(Pos.CENTER_LEFT);
        approvalButton.setGraphic(box);
        approvalButton.setText("");
    }

    /** 点审批按钮：向上弹出右键菜单式的选择卡（图标 + 标题/说明，当前档右侧带勾）。
     * 复用单一 {@link #approvalMenu} 实例并在已弹出时直接返回，防止反复点击累积多个弹出窗（阴影叠加、位置逐次上移）。 */
    @FXML
    public void showApprovalMenu() {
        if (approvalMenu == null) {
            approvalMenu = new ContextMenu();
            approvalMenu.getStyleClass().add("rich-menu");
            // 无论自动还是主动 hide，都记下关闭时刻
            approvalMenu.setOnHiding(e -> approvalMenuHiddenAt = System.currentTimeMillis());
        }
        if (approvalMenu.isShowing()) {
            approvalMenu.hide();   // 已弹出再点按钮：收起，做成开关切换
            return;
        }
        // 弹窗在本次按钮点击前已被系统自动收起（isShowing 已为 false）：视为「关闭」手势，不要立刻重开
        if (System.currentTimeMillis() - approvalMenuHiddenAt < 250) {
            return;
        }
        // 每次重灌选项：保证「当前档打勾」与语言随点随新
        approvalMenu.getItems().setAll(
                approvalOptions().stream()
                        .map(opt -> approvalMenuItem(opt, opt.mode() == approvalMode))
                        .toList());
        showMenuAboveWithinWindow(approvalMenu, approvalButton);
    }

    /**
     * 让菜单在锚点上方弹出，并夹取到主窗口可视范围内：先按 {@link Side#TOP} 弹出量取真实尺寸，
     * 再重定位——水平不越右/左边界，上方空间不够则翻到下方。与 {@link #showThemeMenu} 同思路。
     */
    private void showMenuAboveWithinWindow(ContextMenu menu, Node anchor) {
        menu.show(anchor, Side.TOP, 0, 0);
        Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        if (b == null) {
            return;
        }
        Parent skin = (Parent) menu.getSkin().getNode();
        skin.applyCss();
        skin.layout();
        double w = skin.getBoundsInLocal().getWidth();
        double h = skin.getBoundsInLocal().getHeight();
        double margin = 8;
        double x = b.getMinX();
        double y = b.getMinY() - h; // 贴合按钮顶边：复用实例消除累积漂移后，基准 0 即贴合（与 showThemeMenu 一致）
        Window win = anchor.getScene() == null ? null : anchor.getScene().getWindow();
        if (win != null) {
            double winLeft = win.getX() + margin;
            double winRight = win.getX() + win.getWidth() - margin;
            double winTop = win.getY() + margin;
            if (x + w > winRight) {
                x = Math.max(winLeft, winRight - w);
            }
            if (x < winLeft) {
                x = winLeft;
            }
            if (y < winTop) {
                y = b.getMaxY() + 4;
            }
        }
        menu.setX(x);
        menu.setY(y);
    }

    /** 两行富菜单项通用布局（无标题尾随节点）。 */
    private MenuItem twoLineMenuItem(Label icon, Label title, Label desc, boolean checked, double width,
                                     Runnable onPick) {
        return twoLineMenuItem(icon, title, null, desc, checked, width, onPick);
    }

    /** 两行富菜单项通用布局：图标 + 标题/说明占满左侧，勾选独立叠层固定右对齐，与文字不共享宽度。
     * {@code titleExtra} 非空时紧跟标题右侧同行（如多模态小标），超长时先截标题、尾随不缩。 */
    private MenuItem twoLineMenuItem(Label icon, Label title, Node titleExtra, Label desc, boolean checked, double width,
                                     Runnable onPick) {
        // 标题/说明钉最大宽度并启用省略号：超长先截断，不把行撑宽、不顶勾
        title.setMaxWidth(width - 74);
        title.setEllipsisString("\u2026");
        desc.setMaxWidth(width - 74);
        desc.setEllipsisString("\u2026");
        Node firstLine = title;
        if (titleExtra != null) {
            // 尾随图标不参与压缩：允许标题压到 0 宽先截断，保证小标始终紧跟名称且不顶勾
            title.setMinWidth(0);
            HBox titleRow = new HBox(6, title, titleExtra);
            titleRow.setAlignment(Pos.CENTER_LEFT);
            titleRow.setMaxWidth(width - 74);
            firstLine = titleRow;
        }
        VBox col = new VBox(2, firstLine, desc);
        col.setAlignment(Pos.CENTER_LEFT);
        col.setPadding(new Insets(0, 26, 0, 0)); // 右侧预留勾选位
        Label check = new Label("\u2713");
        check.getStyleClass().add("approval-check");
        check.setVisible(checked);
        check.setManaged(checked);
        StackPane textZone = new StackPane(col, check);
        StackPane.setAlignment(col, Pos.CENTER_LEFT);    // 文字钉左，撑满后不被居中右移
        StackPane.setAlignment(check, Pos.CENTER_RIGHT); // 勾选贴行右缘
        HBox.setHgrow(textZone, Priority.ALWAYS);        // textZone 撑满整行，把勾推到右缘（行已钉宽，不会被长内容撑开）
        HBox row = new HBox(10, icon, textZone);
        row.setAlignment(Pos.CENTER_LEFT);
        // min=pref=max 钉死宽度：所有行等宽，勾才能统一贴到菜单右缘（仅设 prefWidth 会被长内容撑开）
        row.setMinWidth(width);
        row.setPrefWidth(width);
        row.setMaxWidth(width);
        row.getStyleClass().add("approval-menu");
        MenuItem item = new MenuItem();
        item.setGraphic(row);
        item.setOnAction(e -> onPick.run());
        return item;
    }

    /** 审批菜单项：图标 + 标题/说明两行，勾选固定右侧独立对齐；危险项标红。 */
    private MenuItem approvalMenuItem(ApprovalOption opt, boolean checked) {
        Label icon = UiTheme.icon(opt.icon());
        icon.getStyleClass().add("approval-icon");
        Label title = new Label(opt.label());
        title.getStyleClass().add("approval-title");
        Label desc = new Label(opt.desc());
        desc.getStyleClass().add("approval-desc");
        // 说明保持单行（不换行）；勾选由 twoLineMenuItem 固定在右侧，文字超长先截断不顶勾
        if (opt.danger()) {
            icon.getStyleClass().add("approval-icon-danger");
            title.getStyleClass().add("approval-title-danger");
        }
        return twoLineMenuItem(icon, title, desc, checked, 360, () -> {
            approvalMode = opt.mode();
            renderApprovalButton();
        });
    }

    /** 用户气泡：文本 + 图片缩略图（若有）；无文本时只渲染图。 */
    private void appendUserBubble(String text, List<ImageView> thumbs) {
        VBox col = new VBox(6);
        if (text != null && !text.isBlank()) {
            Label label = new Label(text);
            label.setWrapText(true);
            label.getStyleClass().add("bubble-user");
            label.setMaxWidth(680);
            col.getChildren().add(label);
        }
        if (thumbs != null && !thumbs.isEmpty()) {
            HBox imageRow = new HBox(6);
            imageRow.getStyleClass().add("bubble-images");
            imageRow.getChildren().addAll(thumbs);
            col.getChildren().add(imageRow);
        }
        HBox row = new HBox(col);
        row.getStyleClass().add("bubble-row");
        row.setAlignment(Pos.TOP_RIGHT);
        messagesContainer.getChildren().add(row);
        scrollPane.layout();
        scrollPane.setVvalue(1.0);
    }

    private List<ImageView> thumbsFromBase64(List<ImageAttachment> images) {
        List<ImageView> out = new ArrayList<>();
        for (ImageAttachment img : images) {
            if (img == null || img.base64Data() == null) {
                continue;
            }
            try {
                out.add(thumbView(Base64.getDecoder().decode(img.base64Data())));
            } catch (IllegalArgumentException e) {
                log.warn("附件 Base64 解码失败，跳过缩略图: {}", img.fileName());
            }
        }
        return out;
    }

    private List<ImageView> thumbsFromStored(List<StoredAttachment> atts) {
        List<ImageView> out = new ArrayList<>();
        for (StoredAttachment a : atts) {
            ImageView v = thumbFromPath(a.path());
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    private static ImageView thumbView(byte[] bytes) {
        ImageView iv = new ImageView(new Image(new ByteArrayInputStream(bytes)));
        iv.setFitWidth(140);
        iv.setFitHeight(140);
        iv.setPreserveRatio(true);
        iv.getStyleClass().add("attach-thumb");
        return iv;
    }

    private ImageView thumbFromPath(String rel) {
        if (rel == null || rel.isBlank()) {
            return null;
        }
        try {
            Path p = gimPaths.attachmentsDir().resolve(rel);
            if (!Files.exists(p)) {
                return null;
            }
            return thumbView(Files.readAllBytes(p));
        } catch (IOException | RuntimeException e) {
            log.warn("读取附件缩略图失败: {}", rel, e);
            return null;
        }
    }

    /** 模型菜单条目：providerCode/modelCode 同时为空表示「跟随默认」（不覆盖）；携带图标、提供商说明与多模态标记供菜单富渲染。 */
    private record ModelOption(String providerCode, String modelCode, String label, String icon, String desc,
                               boolean vision) {
        boolean followDefault() {
            return providerCode == null;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** 审批下拉条目：映射到 {@link PermissionMode}，携带图标、说明与危险标记（供菜单项富渲染）。 */
    private record ApprovalOption(PermissionMode mode, String label, String icon, String desc, boolean danger) {
        @Override
        public String toString() {
            return label;
        }
    }
}
