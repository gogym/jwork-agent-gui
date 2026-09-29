package com.gettyio.gim.agent.ui.controller;

import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.StreamEvent;
import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.entity.WorkspaceGroup;
import com.gettyio.gim.agent.domain.enums.ConversationRole;
import com.gettyio.gim.agent.domain.enums.StreamEventType;
import com.gettyio.gim.agent.ui.UiTheme;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.config.WorkspaceAdminService;
import com.gettyio.gim.agent.ui.view.ExtensionsView;
import com.gettyio.gim.agent.ui.view.TasksView;
import com.gettyio.gim.agent.ui.window.AppShell;
import com.gettyio.gim.agent.ui.window.GimDialog;
import com.gettyio.gim.agent.ui.window.SettingsWindow;
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
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * 主窗口控制器：侧栏导航驱动页面切换（对话/定时任务/技能），
 * 对话页为会话列表切换 + 流式渲染（帧合并 ≥33ms）。
 */
@Component
public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    private static final String DEFAULT_AGENT = "gim-agent";
    /** 帧合并窗口：至少每 33ms 刷新一次 */
    private static final Duration FRAME_WINDOW = Duration.ofMillis(33);
    /** 虚拟默认分组「未分组会话」的固定 ID（真实分组为 UUID，不会冲突） */
    private static final String UNGROUPED_ID = "__ungrouped__";
    /** 「最近任务」最多展示的会话条数 */
    private static final int RECENT_LIMIT = 10;

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
    private VBox recentBox;
    @FXML
    private Button addWorkspaceButton;
    @FXML
    private VBox workspaceBox;
    @FXML
    private HBox workspaceHeader;
    @FXML
    private Label workspaceSectionCaret;
    @FXML
    private HBox recentHeader;
    @FXML
    private Label sectionRecentCaret;
    @FXML
    private Label navChatLabel;
    @FXML
    private Label navTasksLabel;
    @FXML
    private Label navSkillsLabel;
    @FXML
    private Label sectionWorkspace;
    @FXML
    private Label sectionRecent;
    @FXML
    private Label userNameLabel;
    @FXML
    private Label welcomeTitle;
    @FXML
    private Label welcomeSubtitle;
    @FXML
    private Label privacyHint;

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
    private WorkspaceAdminService workspaceAdminService;

    @Autowired
    private GimPaths gimPaths;

    private final ObservableList<ChatSession> sessions = FXCollections.observableArrayList();
    /** 手风琴：当前展开的工作区分组（同一时刻至多一个） */
    private String expandedWorkspaceId;
    private String currentSessionId;
    private boolean settingsOpen;
    /** 「工作区」整块区域是否收起 */
    private boolean workspacesCollapsed;
    /** 「最近任务」区域是否收起 */
    private boolean recentCollapsed;

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

        // 会话列表由 refreshRecentSessions() 手动渲染（VBox），点击行即切换会话
        refreshSessions();
        if (sessions.isEmpty()) {
            handleNewSession();
        } else {
            switchToSession(sessions.get(0).getSessionId());
        }
        refreshWorkspaces();
        // 「工作区」标题行点击：整块区域展开/收起（「+」按钮会自消费点击，不受影响）
        workspaceHeader.setOnMouseClicked(e -> toggleWorkspaceSection());
        recentHeader.setOnMouseClicked(e -> toggleRecentSection());

        applyI18n();
        buildThemeMenu();
        // 语言切换：即时刷新静态文案并重建齿轮菜单
        I18n.onChange(lang -> {
            applyI18n();
            buildThemeMenu();
            refreshWorkspaces();
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
        sectionWorkspace.setText(I18n.t("section.workspace"));
        sectionRecent.setText(I18n.t("section.recent"));
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

    @FXML
    public void handleNewSession() {
        // 新会话未指定分组：展开「未分组会话」，让新行立即可见
        expandedWorkspaceId = UNGROUPED_ID;
        if (navChat != null) {
            navChat.setSelected(true);
        }
        String id = chatService.createSession(DEFAULT_AGENT);
        refreshSessions();
        switchToSession(id);
        Platform.runLater(() -> messagesContainer.getChildren().clear());
    }

    @FXML
    public void handleOpenSettings() {
        if (settingsOpen) {
            return;
        }
        Scene scene = currentScene();
        if (scene == null) {
            return;
        }
        settingsOpen = true;
        settingsWindow.showIn(appShell, scene, () -> {
            appShell.showApp();
            UiTheme.apply(scene);
            settingsOpen = false;
        });
    }

    @FXML
    public void handleSend() {
        String text = inputField.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        if (currentSessionId == null) {
            currentSessionId = chatService.createSession(DEFAULT_AGENT);
        }
        inputField.clear();
        sendButton.setDisable(true);
        appendBubble(text, true);
        Label assistantLabel = appendBubble("", false);
        StringBuilder acc = new StringBuilder();
        String sessionId = currentSessionId;

        chatService.chat(sessionId, DEFAULT_AGENT, text)
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

    private void switchToSession(String sessionId) {
        currentSessionId = sessionId;
        messagesContainer.getChildren().clear();
        List<ChatConversation> history = chatService.history(sessionId);
        for (ChatConversation c : history) {
            boolean self = ConversationRole.USER.getCode().equalsIgnoreCase(c.getRole());
            appendBubble(c.getContent() == null ? "" : c.getContent(), self);
        }
        // 高亮当前会话行（最近任务 + 展开的分组内）
        refreshRecentSessions();
        refreshWorkspaces();
    }

    /** 重建「最近任务」列表：全部会话按最近更新排序取前 10 条（不区分是否已分组）。 */
    private void refreshRecentSessions() {
        if (recentBox == null) {
            return;
        }
        recentBox.getChildren().clear();
        for (ChatSession s : sessions) {
            if (recentBox.getChildren().size() >= RECENT_LIMIT) {
                break;
            }
            recentBox.getChildren().add(buildSessionRow(s, false));
        }
    }

    private void refreshSessions() {
        List<ChatSession> list = chatService.listSessions();
        sessions.setAll(list);
        refreshRecentSessions();
        refreshWorkspaces();
    }

    // ===================== 工作区分组 =====================

    /** 工作区头部「+」：新建分组 */
    @FXML
    public void handleAddWorkspace() {
        openWorkspaceDialog(null);
    }

    /** 打开新建/编辑分组对话框（existing 为 null 表示新建） */
    private void openWorkspaceDialog(WorkspaceGroup existing) {
        GimDialog.Shell shell = GimDialog.shell(currentWindow(),
                I18n.t(existing == null ? "workspace.new.title" : "workspace.edit.title"));

        TextField nameField = new TextField(existing == null ? "" : existing.getName());
        nameField.setPromptText(I18n.t("workspace.name.prompt"));

        TextField folderField = new TextField(
                existing == null || existing.getFolderPath() == null ? "" : existing.getFolderPath());
        folderField.setEditable(false);
        folderField.setPromptText(I18n.t("workspace.folder.prompt"));
        folderField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(folderField, Priority.ALWAYS);
        Button browse = new Button(I18n.t("workspace.folder.browse"));
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            if (!folderField.getText().isEmpty()) {
                File dir = new File(folderField.getText());
                if (dir.isDirectory()) {
                    chooser.setInitialDirectory(dir);
                }
            }
            File sel = chooser.showDialog(shell.stage());
            if (sel != null) {
                folderField.setText(sel.getAbsolutePath());
            }
        });
        HBox folderBox = new HBox(8, folderField, browse);

        final String[] glyphs = {"\uE8B7", "\uE943", "\uE8A5", "\uEB9F", "\uE7C1", "\uE8D6"};
        final String[] icon = {existing != null && existing.getIcon() != null ? existing.getIcon() : glyphs[0]};
        ToggleGroup iconGroup = new ToggleGroup();
        HBox iconBox = new HBox(4);
        for (String g : glyphs) {
            ToggleButton tb = new ToggleButton();
            tb.setGraphic(UiTheme.icon(g));
            tb.setToggleGroup(iconGroup);
            tb.setUserData(g);
            tb.getStyleClass().add("ws-icon-opt");
            if (g.equals(icon[0])) {
                tb.setSelected(true);
            }
            iconBox.getChildren().add(tb);
        }
        iconGroup.selectedToggleProperty().addListener((o, a, n) -> {
            if (n != null) {
                icon[0] = (String) n.getUserData();
            }
        });

        final String[] palette = {"#2f9e5f", "#3b82f6", "#f59e0b", "#ef4444", "#8b5cf6", "#14b8a6", "#64748b"};
        final String[] color = {existing != null && existing.getColor() != null ? existing.getColor() : palette[0]};
        HBox colorBox = new HBox(6);
        for (String c : palette) {
            Button sw = new Button();
            sw.getStyleClass().add("ws-swatch");
            sw.setStyle("-fx-background-color: " + c + ";");
            sw.setOnAction(e -> color[0] = c);
            colorBox.getChildren().add(sw);
        }

        VBox form = new VBox(12);
        form.setMinWidth(360);
        form.getChildren().addAll(
                fieldRow(I18n.t("workspace.name"), nameField),
                fieldRow(I18n.t("workspace.folder"), folderBox),
                fieldRow(I18n.t("workspace.icon"), iconBox),
                fieldRow(I18n.t("workspace.color"), colorBox));

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        Button save = new Button(I18n.t("common.save"));
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (name.isEmpty()) {
                return;
            }
            String folder = folderField.getText().trim();
            if (existing == null) {
                WorkspaceGroup w = workspaceAdminService.create(
                        name, folder.isEmpty() ? null : folder, icon[0], color[0]);
                expandedWorkspaceId = w.getWorkspaceId();
            } else {
                workspaceAdminService.update(
                        existing.getWorkspaceId(), name, folder.isEmpty() ? null : folder, icon[0], color[0]);
            }
            refreshWorkspaces();
            shell.stage().close();
        });
        HBox btns = new HBox(8, cancel, save);
        btns.setAlignment(Pos.CENTER_RIGHT);

        shell.body().getChildren().addAll(form, btns);
        shell.showAndWait();
    }

    /** 对话框字段行：小标题 + 控件 */
    private static VBox fieldRow(String caption, Node control) {
        Label cap = new Label(caption);
        cap.getStyleClass().add("form-label");
        return new VBox(4, cap, control);
    }

    /** 重建侧栏工作区分组列表：置顶虚拟默认分组「未分组会话」，其后为用户分组（含展开的会话）。 */
    private void refreshWorkspaces() {
        if (workspaceBox == null) {
            return;
        }
        workspaceBox.getChildren().clear();
        workspaceBox.getChildren().add(buildUngroupedRow());
        if (UNGROUPED_ID.equals(expandedWorkspaceId)) {
            for (ChatSession s : chatService.listSessions()) {
                if (s.getWorkspaceId() == null || s.getWorkspaceId().isEmpty()) {
                    workspaceBox.getChildren().add(buildSessionRow(s, true));
                }
            }
        }
        List<WorkspaceGroup> groups = workspaceAdminService.list();
        for (WorkspaceGroup g : groups) {
            workspaceBox.getChildren().add(buildGroupRow(g));
            if (g.getWorkspaceId().equals(expandedWorkspaceId)) {
                for (ChatSession s : chatService.listSessions(g.getWorkspaceId())) {
                    workspaceBox.getChildren().add(buildSessionRow(s, true));
                }
            }
        }
    }

    /** 虚拟默认分组行「未分组会话」：收纳所有未指定分组的会话，无重命名/删除菜单。 */
    private HBox buildUngroupedRow() {
        boolean expanded = UNGROUPED_ID.equals(expandedWorkspaceId);
        Label caret = new Label("\u25BC");
        caret.getStyleClass().add("ws-caret");
        caret.setRotate(expanded ? 0 : -90);
        Label icon = UiTheme.icon("\uE8B7");
        icon.setStyle("-fx-text-fill: #64748b;");
        Label name = new Label(I18n.t("workspace.ungrouped"));
        name.getStyleClass().add("ws-name");
        name.setMaxWidth(110);
        name.setEllipsisString("\u2026");

        Button addBtn = new Button();
        addBtn.setGraphic(UiTheme.icon("\uE710"));
        addBtn.getStyleClass().add("ws-mini-btn");
        addBtn.setOnAction(e -> handleNewSession());

        HBox row = new HBox(6, caret, icon, name, growSpacer(), addBtn);
        row.getStyleClass().add("ws-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOnMouseClicked(e -> toggleWorkspace(UNGROUPED_ID));
        return row;
    }

    private HBox buildGroupRow(WorkspaceGroup g) {
        boolean expanded = g.getWorkspaceId().equals(expandedWorkspaceId);
        // 小实心三角（QQ 好友列表风格）：同一▼字形，收起时逆时针旋转 90° 成 ▶，保证两种状态大小一致
        Label caret = new Label("\u25BC");
        caret.getStyleClass().add("ws-caret");
        caret.setRotate(expanded ? 0 : -90);
        Label icon = UiTheme.icon(g.getIcon() == null ? "\uE8B7" : g.getIcon());
        if (g.getColor() != null) {
            icon.setStyle("-fx-text-fill: " + g.getColor() + ";");
        }
        Label name = new Label(g.getName());
        name.getStyleClass().add("ws-name");
        name.setMaxWidth(110);
        name.setEllipsisString("\u2026");

        Button addBtn = new Button();
        addBtn.setGraphic(UiTheme.icon("\uE710"));
        addBtn.getStyleClass().add("ws-mini-btn");
        addBtn.setOnAction(e -> handleNewSessionIn(g));

        Button menuBtn = new Button();
        menuBtn.setGraphic(UiTheme.icon("\uE712"));
        menuBtn.getStyleClass().add("ws-mini-btn");
        ContextMenu menu = new ContextMenu();
        MenuItem rename = new MenuItem(I18n.t("workspace.menu.edit"));
        rename.setOnAction(e -> openWorkspaceDialog(g));
        MenuItem open = new MenuItem(I18n.t("workspace.menu.openFolder"));
        open.setDisable(g.getFolderPath() == null || g.getFolderPath().isEmpty());
        open.setOnAction(e -> openFolder(g.getFolderPath()));
        MenuItem remove = new MenuItem(I18n.t("workspace.menu.delete"));
        remove.setOnAction(e -> confirmDeleteWorkspace(g));
        menu.getItems().addAll(rename, open, new SeparatorMenuItem(), remove);
        menuBtn.setOnAction(e -> menu.show(menuBtn, Side.RIGHT, 0, 0));

        HBox row = new HBox(6, caret, icon, name, growSpacer(), addBtn, menuBtn);
        row.getStyleClass().add("ws-row");
        row.setAlignment(Pos.CENTER_LEFT);
        if (g.getFolderPath() != null) {
            Tooltip.install(row, new Tooltip(g.getFolderPath()));
        }
        // 整行点击展开/收起（按钮会自行消费点击事件，不触发折叠）
        row.setOnMouseClicked(e -> toggleWorkspace(g.getWorkspaceId()));
        return row;
    }

    /** 会话行（最近任务与分组内共用）：标题 + 「⋯」更多按钮（悬停显示）。 */
    private HBox buildSessionRow(ChatSession s, boolean indent) {
        Label dot = new Label("\u2022");
        dot.getStyleClass().add("ws-session-dot");
        Label title = new Label(s.getTitle());
        title.getStyleClass().add("ws-session-name");
        title.setMaxWidth(indent ? 120 : 140);
        title.setEllipsisString("\u2026");

        Button menuBtn = new Button();
        menuBtn.setGraphic(UiTheme.icon("\uE712"));
        menuBtn.getStyleClass().addAll("ws-mini-btn", "ws-session-menu");
        ContextMenu menu = sessionContextMenu(s);
        menuBtn.setOnAction(e -> menu.show(menuBtn, Side.RIGHT, 0, 0));

        HBox row = new HBox(6, dot, title, growSpacer(), menuBtn);
        row.getStyleClass().add("ws-session-row");
        if (indent) {
            row.getStyleClass().add("ws-session-indent");
        }
        if (s.getSessionId().equals(currentSessionId)) {
            row.getStyleClass().add("ws-session-row-selected");
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOnMouseClicked(e -> openSession(s));
        return row;
    }

    private static Region growSpacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private void toggleWorkspace(String workspaceId) {
        // 手风琴：再次点击当前展开项则收起，否则切换到该分组（自动收起其他）
        expandedWorkspaceId = workspaceId.equals(expandedWorkspaceId) ? null : workspaceId;
        refreshWorkspaces();
    }

    /** 「工作区」整块区域收起/展开（标题与三角箭头联动；收起时不留空白，展开时自然下推） */
    private void toggleWorkspaceSection() {
        workspacesCollapsed = !workspacesCollapsed;
        workspaceBox.setVisible(!workspacesCollapsed);
        workspaceBox.setManaged(!workspacesCollapsed);
        workspaceSectionCaret.setRotate(workspacesCollapsed ? -90 : 0);
    }

    /** 「最近任务」区域收起/展开 */
    private void toggleRecentSection() {
        recentCollapsed = !recentCollapsed;
        recentBox.setVisible(!recentCollapsed);
        recentBox.setManaged(!recentCollapsed);
        sectionRecentCaret.setRotate(recentCollapsed ? -90 : 0);
    }

    private void handleNewSessionIn(WorkspaceGroup g) {
        expandedWorkspaceId = g.getWorkspaceId();
        if (navChat != null) {
            navChat.setSelected(true);
        }
        String id = chatService.createSession(DEFAULT_AGENT, g.getWorkspaceId());
        refreshSessions();
        switchToSession(id);
        Platform.runLater(() -> messagesContainer.getChildren().clear());
    }

    private void openSession(ChatSession s) {
        if (navChat != null) {
            navChat.setSelected(true);
        }
        switchToSession(s.getSessionId());
    }

    private void confirmDeleteWorkspace(WorkspaceGroup g) {
        if (GimDialog.confirm(currentWindow(), I18n.t("workspace.menu.delete"),
                I18n.t("workspace.deleteConfirm", g.getName()))) {
            workspaceAdminService.delete(g.getWorkspaceId());
            // 其下会话已在库中解绑为未分组：展开「未分组会话」，让去向立即可见
            expandedWorkspaceId = UNGROUPED_ID;
            refreshSessions();
        }
    }

    /** 会话行「⋯」菜单：移动到分组 / 删除 */
    private ContextMenu sessionContextMenu(ChatSession s) {
        ContextMenu menu = new ContextMenu();

        Menu moveTo = new Menu(I18n.t("session.moveTo"), UiTheme.icon("\uE8B7"));
        boolean grouped = s.getWorkspaceId() != null && !s.getWorkspaceId().isEmpty();

        // 虚拟默认分组「未分组会话」：已未分组时置灰
        MenuItem ungrouped = new MenuItem(I18n.t("workspace.ungrouped"));
        ungrouped.setDisable(!grouped);
        ungrouped.setOnAction(e -> moveSessionTo(s, null));
        moveTo.getItems().add(ungrouped);

        List<WorkspaceGroup> groups = workspaceAdminService.list();
        if (!groups.isEmpty()) {
            moveTo.getItems().add(new SeparatorMenuItem());
        }
        for (WorkspaceGroup g : groups) {
            MenuItem item = new MenuItem(g.getName());
            // 已在当前分组内的项置灰
            item.setDisable(g.getWorkspaceId().equals(s.getWorkspaceId()));
            item.setOnAction(e -> moveSessionTo(s, g.getWorkspaceId()));
            moveTo.getItems().add(item);
        }
        // 已未分组且无其他分组可移时，子菜单整体禁用
        moveTo.setDisable(!grouped && groups.isEmpty());

        MenuItem del = new MenuItem(I18n.t("common.delete"), UiTheme.icon("\uE74D"));
        del.setOnAction(e -> confirmDeleteSession(s));
        menu.getItems().addAll(moveTo, new SeparatorMenuItem(), del);
        return menu;
    }

    /** 将会话移动到指定分组（workspaceId 为 null 表示移入「未分组会话」） */
    private void moveSessionTo(ChatSession s, String workspaceId) {
        chatService.moveSession(s.getSessionId(), workspaceId);
        // 展开目标容器，让移动结果立即可见
        expandedWorkspaceId = workspaceId == null ? UNGROUPED_ID : workspaceId;
        refreshSessions();
    }

    private void confirmDeleteSession(ChatSession s) {
        if (GimDialog.confirm(currentWindow(), I18n.t("common.delete"),
                I18n.t("session.deleteConfirm", s.getTitle()))) {
            chatService.deleteSession(s.getSessionId());
            if (s.getSessionId().equals(currentSessionId)) {
                currentSessionId = null;
                messagesContainer.getChildren().clear();
            }
            refreshSessions();
            if (currentSessionId == null && !sessions.isEmpty()) {
                switchToSession(sessions.get(0).getSessionId());
            }
        }
    }

    /** 用系统文件管理器打开分组绑定的文件夹 */
    private void openFolder(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        try {
            File dir = new File(path);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir);
            } else {
                new ProcessBuilder("explorer.exe", path).start();
            }
        } catch (IOException | RuntimeException e) {
            log.warn("打开文件夹失败: {}", path, e);
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
}
