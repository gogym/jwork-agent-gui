package com.gettyio.gim.agent.ui.controller;

import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.StreamEvent;
import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.enums.ConversationRole;
import com.gettyio.gim.agent.domain.enums.StreamEventType;
import com.gettyio.gim.agent.ui.UiTheme;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.view.SkillsView;
import com.gettyio.gim.agent.ui.view.TasksView;
import com.gettyio.gim.agent.ui.window.AppShell;
import com.gettyio.gim.agent.ui.window.SettingsWindow;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

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
    private ListView<ChatSession> sessionListView;
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
    private SkillsView skillsView;

    @Autowired
    private GimPaths gimPaths;

    private final ObservableList<ChatSession> sessions = FXCollections.observableArrayList();
    private String currentSessionId;
    private boolean refreshingSessions;
    private boolean settingsOpen;

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
                mainPages.getChildren().setAll(skillsView.getNode());
                skillsView.reload();
            } else {
                mainPages.getChildren().setAll(chatPage);
            }
        });

        sessionListView.setItems(sessions);
        sessionListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ChatSession item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getTitle());
            }
        });
        sessionListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldSel, newSel) -> {
                    if (newSel != null && !refreshingSessions) {
                        switchToSession(newSel.getSessionId());
                    }
                });

        refreshSessions();
        if (sessions.isEmpty()) {
            handleNewSession();
        } else {
            sessionListView.getSelectionModel().selectFirst();
        }

        applyI18n();
        buildThemeMenu();
        // 语言切换：即时刷新静态文案并重建齿轮菜单
        I18n.onChange(lang -> {
            applyI18n();
            buildThemeMenu();
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
        themeMenuButton.setContextMenu(menu);
        // 左键点击即时弹出菜单（setContextMenu 仅响应右键/菜单键）；
        // 齿轮位于侧栏底部，从按钮右上角弹出，超出屏幕时 JavaFX 自动上翻
        themeMenuButton.setOnAction(e -> {
            if (!menu.isShowing()) {
                Bounds b = themeMenuButton.localToScreen(themeMenuButton.getBoundsInLocal());
                if (b != null) {
                    menu.show(themeMenuButton, b.getMaxX(), b.getMinY());
                }
            }
        });

        // 全局快捷键 Ctrl+,（菜单未展开时也生效）
        Platform.runLater(() -> {
            Scene scene = chatPage.getScene();
            if (scene != null) {
                scene.getAccelerators().put(
                        new KeyCodeCombination(KeyCode.COMMA, KeyCombination.CONTROL_DOWN),
                        this::handleOpenSettings);
            }
        });
    }

    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION,
                I18n.t("about.body", gimPaths.root()),
                ButtonType.OK);
        alert.setTitle(I18n.t("about.title"));
        alert.setHeaderText(I18n.t("about.header"));
        Scene scene = chatPage.getScene();
        if (scene != null) {
            alert.initOwner(scene.getWindow());
        }
        alert.showAndWait();
    }

    @FXML
    public void handleNewSession() {
        if (navChat != null) {
            navChat.setSelected(true);
        }
        String id = chatService.createSession(DEFAULT_AGENT);
        refreshSessions();
        selectSessionById(id);
        currentSessionId = id;
        Platform.runLater(() -> messagesContainer.getChildren().clear());
    }

    @FXML
    public void handleOpenSettings() {
        if (settingsOpen) {
            return;
        }
        Scene scene = chatPage.getScene();
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
    }

    private void refreshSessions() {
        refreshingSessions = true;
        ChatSession keep = currentSessionId == null ? null
                : sessions.stream().filter(s -> s.getSessionId().equals(currentSessionId)).findFirst().orElse(null);
        List<ChatSession> list = chatService.listSessions();
        sessions.setAll(list);
        if (keep != null) {
            selectSessionById(keep.getSessionId());
        }
        refreshingSessions = false;
    }

    private void selectSessionById(String sessionId) {
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).getSessionId().equals(sessionId)) {
                sessionListView.getSelectionModel().select(i);
                return;
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
}
