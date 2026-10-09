package com.gettyio.gim.agent.ui.market;

import com.gettyio.gim.agent.domain.entity.SkillPackage;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.tools.skill.SkillService;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.config.AgentAdminService;
import com.gettyio.gim.agent.ui.update.UpdateChecker;
import com.gettyio.gim.agent.ui.window.GimDialog;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 技能市场对话框：搜索 + 市场条目卡片列表 + 一键安装/更新。
 * <p>索引来自 {@link MarketService#fetchSkills}（后台线程拉取，UI 线程渲染）；
 * 安装走「下载 → 哈希校验 → SkillService.importFromMarket」，完成后回调刷新技能页。</p>
 */
public class SkillsMarketDialog {

    private static final double CONTENT_WIDTH = 620;
    private static final double CONTENT_HEIGHT = 430;

    private final Window owner;
    private final MarketService marketService;
    private final SkillService skillService;
    private final AgentAdminService agentAdminService;
    /** 安装成功后通知（刷新技能页卡片与智能体运行时） */
    private final Runnable onChanged;

    private final TextField searchField = new TextField();
    private final Button refreshButton = new Button();
    private final Label statusLabel = new Label();
    private final VBox list = new VBox(10);
    private final ScrollPane scroller = new ScrollPane();
    private final StackPane content = new StackPane();

    private List<MarketService.MarketSkill> loaded = new ArrayList<>();

    public SkillsMarketDialog(Window owner, MarketService marketService, SkillService skillService,
                              AgentAdminService agentAdminService, Runnable onChanged) {
        this.owner = owner;
        this.marketService = marketService;
        this.skillService = skillService;
        this.agentAdminService = agentAdminService;
        this.onChanged = onChanged;
    }

    /** 打开对话框并异步加载索引。 */
    public void show() {
        GimDialog.Shell shell = GimDialog.shell(owner, I18n.t("market.skills.title"));

        refreshButton.getStyleClass().add("icon-btn");
        Label refreshIcon = new Label("\uE72C");
        refreshIcon.getStyleClass().add("icon");
        refreshButton.setGraphic(refreshIcon);
        refreshButton.setOnAction(e -> reload());
        refreshButton.setMinWidth(Region.USE_PREF_SIZE);

        searchField.getStyleClass().add("search-field");
        searchField.setPromptText(I18n.t("market.search.placeholder"));
        searchField.setMinWidth(240);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        // 本地过滤已加载索引，不打服务端；Enter 亦即时刷新当前过滤
        searchField.textProperty().addListener((obs, o, n) -> render());

        HBox toolbar = new HBox(8, searchField, refreshButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        list.setFillWidth(true);
        scroller.setContent(list);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        content.setPrefSize(CONTENT_WIDTH, CONTENT_HEIGHT);
        content.setMinSize(CONTENT_WIDTH, CONTENT_HEIGHT);
        content.getChildren().setAll(scroller);

        statusLabel.getStyleClass().add("status-label");
        statusLabel.setPadding(new Insets(6, 0, 0, 0));
        statusLabel.setText(I18n.t("market.loading"));

        shell.body().getChildren().addAll(toolbar, content, statusLabel);
        shell.show();
        reload();
    }

    // ===================== 加载与渲染 =====================

    /** 后台拉取全量索引。 */
    private void reload() {
        statusLabel.setText(I18n.t("market.loading"));
        setContentMessage(I18n.t("market.loading"), null);
        Thread t = new Thread(() -> {
            try {
                List<MarketService.MarketSkill> items = marketService.fetchSkills("");
                Platform.runLater(() -> {
                    loaded = items;
                    statusLabel.setText(I18n.t("market.count", items.size()));
                    render();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("");
                    setContentMessage(I18n.t("market.error", message(e)), true);
                });
            }
        }, "jwork-market-skills");
        t.setDaemon(true);
        t.start();
    }

    /** 按搜索词过滤已加载索引并重绘卡片。 */
    private void render() {
        String kw = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<MarketService.MarketSkill> visible = loaded.stream()
                .filter(s -> kw.isEmpty()
                        || contains(s.skillName(), kw)
                        || contains(s.skillCode(), kw)
                        || contains(s.description(), kw)
                        || contains(s.author(), kw)
                        || s.tags().stream().anyMatch(tag -> contains(tag, kw)))
                .toList();
        if (visible.isEmpty()) {
            setContentMessage(loaded.isEmpty() ? I18n.t("market.empty") : I18n.t("market.emptyMatch"), null);
            return;
        }
        list.getChildren().clear();
        for (MarketService.MarketSkill s : visible) {
            list.getChildren().add(card(s));
        }
        content.getChildren().setAll(scroller);
    }

    // ===================== 卡片 =====================

    private Node card(MarketService.MarketSkill s) {
        VBox card = new VBox(8);
        card.getStyleClass().add("provider-card");
        card.setMaxWidth(Double.MAX_VALUE);

        Label name = new Label(s.skillName().isBlank() ? s.skillCode() : s.skillName());
        name.getStyleClass().add("provider-card-title");
        name.setMinWidth(0);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox titleRow = new HBox(8, name);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        if (!s.version().isBlank()) {
            Label ver = new Label("v" + UpdateChecker.normalize(s.version()));
            ver.getStyleClass().addAll("badge", "badge-gray");
            titleRow.getChildren().add(ver);
        }
        for (String tag : s.tags()) {
            Label tagLabel = new Label(tag);
            tagLabel.getStyleClass().addAll("badge", "badge-gray");
            titleRow.getChildren().add(tagLabel);
        }

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button action = buildActionButton(s);
        HBox head = new HBox(titleRow, sp, action);
        head.setAlignment(Pos.CENTER_LEFT);

        Label desc = new Label(s.description().isBlank() ? I18n.t("skills.noDesc") : oneLine(s.description()));
        desc.getStyleClass().add("card-desc");
        desc.setWrapText(true);
        desc.setMaxHeight(40);
        desc.setTextOverrun(OverrunStyle.ELLIPSIS);

        StringBuilder meta = new StringBuilder();
        if (!s.author().isBlank()) {
            meta.append(I18n.t("market.author", s.author()));
        }
        if (s.downloadCount() > 0) {
            if (meta.length() > 0) {
                meta.append(" · ");
            }
            meta.append(I18n.t("market.downloadCount", String.valueOf(s.downloadCount())));
        }
        if (!s.updatedAt().isBlank()) {
            if (meta.length() > 0) {
                meta.append(" · ");
            }
            meta.append(I18n.t("market.updatedAt", s.updatedAt()));
        }
        Label metaLabel = new Label(meta.toString());
        metaLabel.getStyleClass().add("card-meta");

        card.getChildren().addAll(head, desc);
        if (meta.length() > 0) {
            card.getChildren().add(metaLabel);
        }
        return card;
    }

    /** 操作按钮：未安装→安装；有新版本→更新；否则显示已安装徽章。 */
    private Button buildActionButton(MarketService.MarketSkill s) {
        SkillPackage local = marketService.localInstalled(s.skillCode());
        if (local != null && !MarketService.hasNewerVersion(local.getVersion(), s.version())) {
            Label installed = new Label(I18n.t("market.installedVersion",
                    local.getVersion() == null || local.getVersion().isBlank() ? "" : local.getVersion()));
            installed.getStyleClass().addAll("badge", "badge-green");
            Button placeholder = new Button();
            placeholder.setGraphic(installed);
            placeholder.getStyleClass().add("link-btn");
            placeholder.setDisable(true);
            return placeholder;
        }
        boolean update = local != null;
        Button action = new Button(I18n.t(update ? "market.update" : "market.install"));
        action.getStyleClass().add(update ? "link-btn" : "btn-primary");
        action.setMinWidth(Region.USE_PREF_SIZE);
        action.setOnAction(e -> install(s, action));
        return action;
    }

    /** 后台下载并安装；按钮内联显示进度，失败落到状态栏。 */
    private void install(MarketService.MarketSkill s, Button action) {
        action.setDisable(true);
        Thread t = new Thread(() -> {
            try {
                SkillPackage pkg = marketService.installSkill(s, ratio ->
                        Platform.runLater(() -> action.setText(I18n.t("market.installing",
                                (int) Math.round(ratio * 100)))));
                agentAdminService.onSkillCatalogChanged(pkg.getSkillCode());
                Platform.runLater(() -> {
                    statusLabel.setText(I18n.t("market.install.ok", pkg.getSkillName()));
                    if (onChanged != null) {
                        onChanged.run();
                    }
                    render();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    action.setDisable(false);
                    render();
                    statusLabel.setText(I18n.t("market.install.fail", message(e)));
                });
            }
        }, "jwork-market-install");
        t.setDaemon(true);
        t.start();
    }

    // ===================== 小工具 =====================

    /** 内容区占位（加载/空/错误）；{@code error} 非空时附「重试」按钮。 */
    private void setContentMessage(String message, Boolean error) {
        VBox box = new VBox(10);
        box.getStyleClass().add("empty-state");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setAlignment(Pos.CENTER);
        Label t = new Label(message);
        t.getStyleClass().add("empty-title");
        t.setWrapText(true);
        t.setTextAlignment(TextAlignment.CENTER);
        t.setMaxWidth(460);
        box.getChildren().add(t);
        if (Boolean.TRUE.equals(error)) {
            Button retry = new Button(I18n.t("market.retry"));
            retry.getStyleClass().add("btn-primary");
            retry.setOnAction(e -> reload());
            HBox row = new HBox(retry);
            row.setAlignment(Pos.CENTER);
            box.getChildren().add(row);
        }
        content.getChildren().setAll(box);
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? I18n.t("common.error.unknown") : m;
    }

    private static boolean contains(String s, String kw) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(kw);
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
