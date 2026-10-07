package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.entity.SessionGroup;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import com.gettyio.gim.agent.ui.UserPrefs;
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

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** 「最新」区常驻显示当前智能体最近更新的会话条数 */
    private static final int LATEST_LIMIT = 10;
    /** 折叠态持久化偏好键（逗号分隔的区块标识：latest / ungrouped / group:<id>） */
    private static final String PREF_COLLAPSED_GROUPS = "sidebar.collapsed.groups";
    /** Fluent 图标：打开的文件夹（移动到分组）/ 新建加号 / 更多 */
    private static final String ICON_FOLDER = "\uE8B7";
    private static final String ICON_ADD = "\uE710";
    private static final String ICON_MORE = "\uE712";
    private static final String ICON_RENAME = "\uE70F";
    private static final String ICON_DELETE = "\uE74D";

    private final VBox sessionsBox;
    private final HBox sessionsHeader;
    private final HBox recentHeader;
    private final VBox recentBox;
    private final Label recentSectionCaret;
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

    /** 「最近会话」顶层区是否收起（与「会话」平级，行为对齐 sessionsCollapsed） */
    private boolean recentCollapsed;

    /** 处于收起态的分区块标识（ungrouped / group:<id>），启动时从偏好加载 */
    private final Set<String> collapsedKeys = new HashSet<>();

    public SessionSidebar(VBox sessionsBox, HBox sessionsHeader, HBox recentHeader, VBox recentBox, Label recentSectionCaret,
                          Label sessionsCount, HBox agentSwitcher,
                          Label agentAvatar, Label agentNameLabel, Label agentDescLabel,
                          Label sessionsSectionCaret, AgentAdminService agentAdminService,
                          ChatService chatService, SessionSidebarHost host) {
        this.sessionsBox = sessionsBox;
        this.sessionsHeader = sessionsHeader;
        this.recentHeader = recentHeader;
        this.recentBox = recentBox;
        this.recentSectionCaret = recentSectionCaret;
        this.sessionsCount = sessionsCount;
        this.agentSwitcher = agentSwitcher;
        this.agentAvatar = agentAvatar;
        this.agentNameLabel = agentNameLabel;
        this.agentDescLabel = agentDescLabel;
        this.sessionsSectionCaret = sessionsSectionCaret;
        this.agentAdminService = agentAdminService;
        this.chatService = chatService;
        this.host = host;
        loadCollapsedKeys();
        installSessionsHeaderMenu();
    }

    /** 从偏好加载折叠的分区块集合（逗号分隔，容忍空白）。 */
    private void loadCollapsedKeys() {
        String raw = UserPrefs.get(PREF_COLLAPSED_GROUPS, "");
        for (String token : raw.split(",")) {
            String k = token.trim();
            if (!k.isEmpty()) {
                collapsedKeys.add(k);
            }
        }
    }

    /** 把当前折叠集合写回偏好。 */
    private void persistCollapsedKeys() {
        UserPrefs.put(PREF_COLLAPSED_GROUPS, String.join(",", collapsedKeys));
    }

    // ===================== 会话列表渲染 =====================

    /**
     * 渲染侧栏会话区：仍只看当前选中智能体的那一份数据。
     * <p>先刷新与「会话」平级的独立顶层区「最近会话」（{@link #renderRecent()}）；本区则拆为
     * 「分组 / 未分组」。一条会话都没分组时，未分组不带表头直接平铺，与旧版观感一致。
     * 关键词过滤仍交给搜索命令面板（{@link SessionSearchDialog}）。</p>
     */
    public void renderSessions() {
        if (sessionsBox == null) {
            return;
        }
        renderRecent();
        sessionsBox.getChildren().clear();
        List<ChatSession> scoped = sessionsOfAgent();
        sessionsCount.setText(String.valueOf(scoped.size()));
        if (scoped.isEmpty()) {
            Label empty = new Label(I18n.t("sessions.empty.none"));
            empty.getStyleClass().add("sessions-empty");
            sessionsBox.getChildren().add(empty);
            return;
        }

        List<SessionGroup> groups = chatService.listGroups(host.currentAgentCode());

        // 分组区：逐组渲染组头（带重命名/删除菜单）+ 组内会话
        for (SessionGroup g : groups) {
            List<ChatSession> inGroup = scoped.stream()
                    .filter(s -> g.getGroupId().equals(s.getGroupId()))
                    .collect(Collectors.toList());
            renderSection("group:" + g.getGroupId(), g.getName(), inGroup, true, groupHeaderMenu(g));
        }

        // 未分组区：没有任何分组时不带表头直接平铺（保持旧观感）；有分组时作为独立可折叠区置于所有分组之下
        List<ChatSession> ungrouped = scoped.stream()
                .filter(s -> s.getGroupId() == null)
                .collect(Collectors.toList());
        if (groups.isEmpty()) {
            for (ChatSession s : ungrouped) {
                sessionsBox.getChildren().add(buildSessionRow(s, false, true));
            }
        } else {
            renderSection("ungrouped", I18n.t("group.ungrouped"), ungrouped, false, null);
        }
    }

    /**
     * 渲染独立顶层区「最近会话」：当前智能体最近更新前 {@value #LATEST_LIMIT} 条（平铺，无表头/缩进）。
     * <p>无会话时表头与列表一起隐藏，不留一个空的「最近会话」标题；收起态只藏列表、留表头。</p>
     */
    private void renderRecent() {
        if (recentBox == null) {
            return;
        }
        recentBox.getChildren().clear();
        List<ChatSession> scoped = sessionsOfAgent();
        List<ChatSession> recent = scoped.subList(0, Math.min(LATEST_LIMIT, scoped.size()));
        boolean hasAny = !recent.isEmpty();
        if (recentHeader != null) {
            recentHeader.setVisible(hasAny);
            recentHeader.setManaged(hasAny);
        }
        recentBox.setVisible(hasAny && !recentCollapsed);
        recentBox.setManaged(hasAny && !recentCollapsed);
        if (recentSectionCaret != null) {
            recentSectionCaret.setRotate(recentCollapsed ? -90 : 0);
        }
        if (!hasAny || recentCollapsed) {
            return;
        }
        for (ChatSession s : recent) {
            // 「最近会话」只是快捷入口，行菜单不带分组项（改分组去下方「会话」区操作）
            recentBox.getChildren().add(buildSessionRow(s, false, false));
        }
    }

    /** 「最近会话」表头点击：整区列表展开/收起（不影响平级的「会话」区）。 */
    public void toggleRecentSection() {
        recentCollapsed = !recentCollapsed;
        renderRecent();
    }

    /**
     * 渲染一个可折叠的分区块：表头（caret + 标题 + 可选「⋯」菜单）+ 展开时的会话行。
     *
     * @param key   折叠态标识（ungrouped / group:&lt;id&gt;）
     * @param indent 组内会话是否左缩进
     * @param menu  表头「⋯」菜单，可为 null（未分组无菜单）
     */
    private void renderSection(String key, String title, List<ChatSession> rows,
                               boolean indent, ContextMenu menu) {
        sessionsBox.getChildren().add(buildSectionHeader(key, title, menu));
        if (collapsedKeys.contains(key)) {
            return;
        }
        if (rows.isEmpty()) {
            Label empty = new Label(I18n.t("sessions.empty.none"));
            empty.getStyleClass().add("sessions-empty");
            sessionsBox.getChildren().add(empty);
            return;
        }
        for (ChatSession s : rows) {
            sessionsBox.getChildren().add(buildSessionRow(s, indent, true));
        }
    }

    /** 分区块表头：可点折叠的 caret+标题，末尾可选「⋯」菜单按钮。 */
    private HBox buildSectionHeader(String key, String title, ContextMenu menu) {
        boolean collapsed = collapsedKeys.contains(key);
        Label caret = new Label("\u25BC");
        caret.getStyleClass().add("ws-caret");
        caret.setRotate(collapsed ? -90 : 0);
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("session-subheader-title");
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        titleLabel.setEllipsisString("\u2026");
        HBox.setHgrow(titleLabel, Priority.ALWAYS);

        // caret 与标题构成可点区域负责折叠，「⋯」按钮独立，避免点菜单时误触折叠
        HBox clickZone = new HBox(6, caret, titleLabel);
        clickZone.setAlignment(Pos.CENTER_LEFT);
        clickZone.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(clickZone, Priority.ALWAYS);
        clickZone.setOnMouseClicked(e -> toggleCollapse(key));

        HBox header = new HBox(6, clickZone);
        header.getStyleClass().add("session-subheader");
        header.setAlignment(Pos.CENTER_LEFT);
        if (menu != null) {
            Button menuBtn = new Button();
            menuBtn.setGraphic(UiTheme.icon(ICON_MORE));
            menuBtn.getStyleClass().add("ws-mini-btn");
            menuBtn.setOnAction(e -> menu.show(menuBtn, Side.BOTTOM, 0, 0));
            header.getChildren().add(menuBtn);
        }
        return header;
    }

    /** 分组的「⋯」菜单：重命名 + 删除（删除只让组内会话退回未分组，不删会话）。 */
    private ContextMenu groupHeaderMenu(SessionGroup g) {
        ContextMenu menu = new ContextMenu();
        MenuItem rename = new MenuItem(I18n.t("group.rename"), UiTheme.icon(ICON_RENAME));
        rename.setOnAction(e -> renameGroup(g));
        MenuItem del = new MenuItem(I18n.t("group.delete"), UiTheme.icon(ICON_DELETE));
        del.setOnAction(e -> confirmDeleteGroup(g));
        menu.getItems().addAll(rename, del);
        return menu;
    }

    /**
     * 在「会话」表头右侧挂一个「⋯」按钮，点击弹出含「新建分组」的菜单。
     * <p>与分组区表头的「⋯」同用 {@code ws-mini-btn} 样式，观感一致；复用单一 {@link ContextMenu}
     * 实例避免反复点击累积阴影/漂移，每次弹出前按当前语言重建条目文案。</p>
     */
    private void installSessionsHeaderMenu() {
        if (sessionsHeader == null) {
            return;
        }
        ContextMenu menu = new ContextMenu();
        Button menuBtn = new Button();
        menuBtn.setGraphic(UiTheme.icon(ICON_MORE));
        menuBtn.getStyleClass().add("ws-mini-btn");
        menuBtn.setFocusTraversable(false);
        menuBtn.setOnAction(e -> {
            if (menu.isShowing()) {
                return;
            }
            MenuItem newGroup = new MenuItem(I18n.t("group.new"), UiTheme.icon(ICON_ADD));
            newGroup.setOnAction(ev -> createGroupDialog(null));
            menu.getItems().setAll(newGroup);
            menu.show(menuBtn, Side.BOTTOM, 0, 0);
        });
        sessionsHeader.getChildren().add(menuBtn);
    }

    /** 切换某分区块的折叠态并持久化、重画。 */
    private void toggleCollapse(String key) {
        if (collapsedKeys.contains(key)) {
            collapsedKeys.remove(key);
        } else {
            collapsedKeys.add(key);
        }
        persistCollapsedKeys();
        renderSessions();
    }

    /** 重命名分组：弹框预填当前名，改了就写库并重画。 */
    private void renameGroup(SessionGroup g) {
        GimDialog.promptText(host.currentWindow(), I18n.t("group.rename"),
                        I18n.t("group.name.label"), g.getName(), ChatService.GROUP_NAME_MAX_LEN)
                .filter(t -> !t.equals(g.getName()))
                .ifPresent(t -> {
                    chatService.renameGroup(g.getGroupId(), t);
                    host.refreshSessions();
                });
    }

    /** 删除分组确认：明确告知组内会话退回未分组、不会被删。 */
    private void confirmDeleteGroup(SessionGroup g) {
        if (GimDialog.confirm(host.currentWindow(), I18n.t("group.delete"),
                I18n.t("group.deleteConfirm", g.getName()))) {
            chatService.deleteGroup(g.getGroupId());
            host.refreshSessions();
        }
    }

    /**
     * 新建分组；{@code moveToSessionId} 非空时建完即把该会话移入（供「新建分组并移入」）。
     * <p>空名回退到 i18n 默认「新分组」，避免落到 core 层的硬编码兜底。</p>
     */
    private void createGroupDialog(String moveToSessionId) {
        GimDialog.promptText(host.currentWindow(), I18n.t("group.new"),
                        I18n.t("group.name.label"), "", ChatService.GROUP_NAME_MAX_LEN)
                .ifPresent(name -> {
                    String effective = name.isBlank() ? I18n.t("group.defaultName") : name;
                    String groupId = chatService.createGroup(host.currentAgentCode(), effective);
                    if (moveToSessionId != null) {
                        chatService.moveSessionToGroup(moveToSessionId, groupId);
                    }
                    host.refreshSessions();
                });
    }

    // ===================== 会话行与行菜单 =====================

    /**
     * 会话行：标题 + 「⋯」更多按钮（悬停显示）。
     * <p>列表已按顶部选中的智能体过滤，行内不再重复标智能体标签；{@code indent} 控制组内会话左缩进，
     * {@code groupMenu} 决定是否在「⋯」菜单里给出移动/新建分组等分组项。</p>
     */
    private HBox buildSessionRow(ChatSession s, boolean indent, boolean groupMenu) {
        Label dot = new Label("\u2022");
        dot.getStyleClass().add("ws-session-dot");
        Label title = new Label(s.getTitle());
        title.getStyleClass().add("ws-session-name");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setEllipsisString("\u2026");
        HBox.setHgrow(title, Priority.ALWAYS);

        Button menuBtn = new Button();
        menuBtn.setGraphic(UiTheme.icon(ICON_MORE));
        menuBtn.getStyleClass().addAll("ws-mini-btn", "ws-session-menu");
        ContextMenu menu = sessionContextMenu(s, groupMenu);
        menuBtn.setOnAction(e -> menu.show(menuBtn, Side.RIGHT, 0, 0));

        HBox row = new HBox(6, dot, title, menuBtn);
        row.getStyleClass().add("ws-session-row");
        if (indent) {
            row.getStyleClass().add("session-group-row");
        }
        if (s.getSessionId().equals(host.currentSessionId())) {
            row.getStyleClass().add("ws-session-row-selected");
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOnMouseClicked(e -> host.openSessionById(s.getSessionId()));
        return row;
    }

    /**
     * 会话行「⋯」菜单：重命名 + 删除；{@code groupMenu} 为真时再插入分组相关项
     * （移动到分组（平铺各分组/移出/新建并移入））。
     * <p>本 JavaFX 版本无 {@code MenuMenuItem} 子菜单类型，故把移动选项直接平铺进菜单。</p>
     */
    private ContextMenu sessionContextMenu(ChatSession s, boolean groupMenu) {
        ContextMenu menu = new ContextMenu();
        MenuItem rename = new MenuItem(I18n.t("session.rename.menu"), UiTheme.icon(ICON_RENAME));
        rename.setOnAction(e -> renameSession(s));
        menu.getItems().add(rename);

        if (groupMenu) {
            // 移动到分组：逐组一个条目（当前所在组置灰），再给「移出分组」（仅已在组内时）与「新建分组并移入」
            String currentGroupId = s.getGroupId();
            List<SessionGroup> groups = chatService.listGroups(host.currentAgentCode());
            if (!groups.isEmpty() || currentGroupId != null) {
                menu.getItems().add(new SeparatorMenuItem());
                for (SessionGroup g : groups) {
                    MenuItem item = new MenuItem(I18n.t("group.moveTo") + " · " + g.getName(), UiTheme.icon(ICON_FOLDER));
                    item.setDisable(g.getGroupId().equals(currentGroupId));
                    item.setOnAction(e -> {
                        chatService.moveSessionToGroup(s.getSessionId(), g.getGroupId());
                        host.refreshSessions();
                    });
                    menu.getItems().add(item);
                }
                if (currentGroupId != null) {
                    MenuItem removeFrom = new MenuItem(I18n.t("group.removeFrom"), UiTheme.icon(ICON_FOLDER));
                    removeFrom.setOnAction(e -> {
                        chatService.moveSessionToGroup(s.getSessionId(), null);
                        host.refreshSessions();
                    });
                    menu.getItems().add(removeFrom);
                }
            }
            MenuItem newAndMove = new MenuItem(I18n.t("group.newAndMove"), UiTheme.icon(ICON_ADD));
            newAndMove.setOnAction(e -> createGroupDialog(s.getSessionId()));
            menu.getItems().add(newAndMove);
        }

        MenuItem del = new MenuItem(I18n.t("common.delete"), UiTheme.icon(ICON_DELETE));
        del.setOnAction(e -> confirmDeleteSession(s));
        menu.getItems().add(new SeparatorMenuItem());
        menu.getItems().add(del);
        return menu;
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
