package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import com.gettyio.gim.agent.ui.config.AgentAdminService;
import com.gettyio.gim.agent.ui.window.AgentDialog;
import com.gettyio.gim.agent.ui.window.GimDialog;
import com.gettyio.gim.agent.ui.window.SessionSearchDialog;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;

/**
 * 侧栏：顶部智能体切换卡 + 下方会话列表的渲染与交互（行菜单重命名/删除、折叠、点击打开）。
 * <p>由 {@code MainController} 在 FXML 注入后构造并委托；本类只做侧栏视图，会话数据、当前会话/智能体
 * 与一切编排（切页、切换会话、重载列表、回到空态）都通过 {@link SessionSidebarHost} 读回宿主，行为与外观零变化。</p>
 */
public class SessionSidebar {

    /** 未选择智能体时使用的内置默认智能体编码 */
    private static final String DEFAULT_AGENT = MultiAgentGateway.DEFAULT_AGENT_CODE;
    /** 智能体弹框的横向固定开销：弹框内边距 6×2 + 条目内边距 12×2（见 .context-menu / .menu-item） */
    private static final double AGENT_MENU_CHROME = 36;
    /** 卡片还没参与布局时的兜底弹框宽度：侧栏固定 250 扣掉左右内边距各 10 */
    private static final double AGENT_MENU_FALLBACK_WIDTH = 230;
    /** 弹框条目里的头像列：头像 26 + 头像与文字间距 8 */
    private static final double AGENT_ITEM_AVATAR_WIDTH = 34;
    /** 弹框条目里的图标列：Segoe 图标 15 + 图标与文字间距 8，向上取整留点余量 */
    private static final double AGENT_ITEM_ICON_WIDTH = 26;

    private final VBox sessionsBox;
    private final Label sessionsCount;
    private final HBox agentSwitcher;
    private final Label agentAvatar;
    private final Label agentNameLabel;
    private final Label agentDescLabel;
    private final Label sessionsSectionCaret;
    private final AgentAdminService agentAdminService;
    private final ChatService chatService;
    private final SessionSidebarHost host;

    /** 「会话」区域是否收起 */
    private boolean sessionsCollapsed;

    public SessionSidebar(VBox sessionsBox, Label sessionsCount, HBox agentSwitcher,
                          Label agentAvatar, Label agentNameLabel, Label agentDescLabel,
                          Label sessionsSectionCaret, AgentAdminService agentAdminService,
                          ChatService chatService, SessionSidebarHost host) {
        this.sessionsBox = sessionsBox;
        this.sessionsCount = sessionsCount;
        this.agentSwitcher = agentSwitcher;
        this.agentAvatar = agentAvatar;
        this.agentNameLabel = agentNameLabel;
        this.agentDescLabel = agentDescLabel;
        this.sessionsSectionCaret = sessionsSectionCaret;
        this.agentAdminService = agentAdminService;
        this.chatService = chatService;
        this.host = host;
    }

    // ===================== 会话列表渲染 =====================

    /**
     * 渲染侧栏会话列表：只显示当前选中智能体的会话（顶部卡片决定这一份数据）。
     * <p>关键词过滤已交给搜索命令面板（{@link SessionSearchDialog}），列表本身不再过滤。</p>
     * <p>手动重建 VBox 子节点（会话数量级为几十，无需虚拟化）。</p>
     */
    public void renderSessions() {
        if (sessionsBox == null) {
            return;
        }
        sessionsBox.getChildren().clear();
        List<ChatSession> scoped = sessionsOfAgent();
        for (ChatSession s : scoped) {
            sessionsBox.getChildren().add(buildSessionRow(s));
        }
        sessionsCount.setText(String.valueOf(scoped.size()));
        if (scoped.isEmpty()) {
            Label empty = new Label(I18n.t("sessions.empty.none"));
            empty.getStyleClass().add("sessions-empty");
            sessionsBox.getChildren().add(empty);
        }
    }

    /**
     * 当前选中智能体的会话（{@code sessions} 已由 DAO 按最近更新排序）。
     * <p>agent_code 为空的脏数据按默认智能体处理，与 AgentAdminService.effectiveCode 一致，
     * 不至于在哪个智能体下都看不到它。</p>
     */
    public List<ChatSession> sessionsOfAgent() {
        String code = host.currentAgentCode();
        return host.sessions().stream()
                .filter(s -> code.equals(agentAdminService.effectiveCode(s.getAgentCode())))
                .toList();
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
        if (s.getSessionId().equals(host.currentSessionId())) {
            row.getStyleClass().add("ws-session-row-selected");
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOnMouseClicked(e -> host.openSessionById(s.getSessionId()));
        return row;
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
        GimDialog.promptText(host.currentWindow(), I18n.t("session.rename.title"),
                        I18n.t("session.rename.label"), s.getTitle(), ChatService.TITLE_MAX_LEN)
                .filter(t -> !t.equals(s.getTitle()))
                .ifPresent(t -> {
                    chatService.renameSession(s.getSessionId(), t);
                    host.refreshSessions();
                });
    }

    private void confirmDeleteSession(ChatSession s) {
        if (GimDialog.confirm(host.currentWindow(), I18n.t("common.delete"),
                I18n.t("session.deleteConfirm", s.getTitle()))) {
            chatService.deleteSession(s.getSessionId());
            if (s.getSessionId().equals(host.currentSessionId())) {
                host.showBlankConversation();
            }
            host.refreshSessions();
            if (host.currentSessionId() == null) {
                List<ChatSession> scoped = sessionsOfAgent();
                if (!scoped.isEmpty()) {
                    host.openSessionById(scoped.get(0).getSessionId());
                }
            }
        }
    }

    /** 「会话」整块区域收起/展开（仅控列表；搜索已改为命令面板，不受折叠影响） */
    public void toggleSessionsSection() {
        sessionsCollapsed = !sessionsCollapsed;
        sessionsBox.setVisible(!sessionsCollapsed);
        sessionsBox.setManaged(!sessionsCollapsed);
        sessionsSectionCaret.setRotate(sessionsCollapsed ? -90 : 0);
    }

    // ===================== 侧栏顶部：智能体切换卡 =====================

    /** 顶部卡片：选中智能体的头像/名称/描述；定义已被删除时 currentAgentCode 已回退到默认智能体 */
    public void renderAgentSwitcher() {
        if (agentSwitcher == null) {
            return;
        }
        AgentDefinition def = agentAdminService.find(host.currentAgentCode()).orElse(null);
        String name = def == null ? DEFAULT_AGENT : agentLabel(def);
        agentAvatar.setText(avatarText(name));
        agentNameLabel.setText(name);
        agentDescLabel.setText(agentDesc(def));
    }

    /**
     * 卡片点击：弹出智能体菜单（切换到某个智能体 / 配置当前 / 新建）。
     * <p>菜单只负责选人与进出表单，真正的会话过滤发生在 renderSessions。</p>
     */
    public void showAgentMenu() {
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

        String current = host.currentAgentCode();
        for (AgentDefinition a : agentAdminService.list()) {
            menu.getItems().add(agentMenuItem(a, a.getAgentCode().equals(current), contentWidth));
        }
        menu.getItems().add(new SeparatorMenuItem());
        agentAdminService.find(current).ifPresent(def -> {
            MenuItem configure = commandMenuItem(I18n.t("sidebar.agent.configure"), "\uE70F", contentWidth);
            configure.setOnAction(e -> AgentDialog.edit(host.currentWindow(), agentAdminService, def)
                    .ifPresent(saved -> host.adoptAgent(saved.getAgentCode())));
            menu.getItems().add(configure);
        });
        MenuItem create = commandMenuItem(I18n.t("sidebar.agent.new"), "\uE710", contentWidth);
        create.setOnAction(e -> AgentDialog.edit(host.currentWindow(), agentAdminService, null)
                .ifPresent(saved -> host.adoptAgent(saved.getAgentCode())));
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
        item.setOnAction(e -> host.selectAgent(a.getAgentCode()));
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

    /** 智能体卡片/菜单显示名：无名时回退编码 */
    private static String agentLabel(AgentDefinition a) {
        return a.getAgentName() == null || a.getAgentName().isBlank() ? a.getAgentCode() : a.getAgentName();
    }
}
