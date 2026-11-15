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

package com.gettyio.gim.agent.ui.market;

import com.gettyio.gim.agent.domain.entity.SkillPackage;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.config.AgentAdminService;
import com.gettyio.gim.agent.ui.window.dialog.GimDialog;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Window;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 技能市场对话框：三列网格 + 游标分页「加载更多」+ 富信息卡片 + 一键安装/更新。
 * <p>体验与 {@link McpMarketDialog} 对齐；索引来自 {@link MarketService#fetchSkillPage}
 * （直连 GitHub 仓库检索，后台线程拉取、UI 线程渲染）。安装走
 * 「下载仓库默认分支 zipball → {@link MarketService#installSkill} → SkillService.importFromMarket」，
 * 完成后回调刷新技能页。</p>
 *
 * @author gogym
 */
public class SkillsMarketDialog {

    private static final double CONTENT_WIDTH = 900;
    private static final double CONTENT_HEIGHT = 620;
    private static final int GRID_COLUMNS = 3;
    private static final double GRID_GAP = 14;

    private final Window owner;
    private final MarketService marketService;
    private final AgentAdminService agentAdminService;
    /** 安装成功后通知（刷新技能页卡片与智能体运行时） */
    private final Runnable onChanged;

    private final TextField searchField = new TextField();
    private final Button refreshButton = new Button();
    private final Label statusLabel = new Label();
    private final GridPane grid = new GridPane();
    private final ScrollPane scroller = new ScrollPane();
    private final StackPane content = new StackPane();
    private final Button loadMore = new Button();

    private List<MarketService.MarketSkill> loaded = new ArrayList<>();
    /** 下一页游标（页码字符串），空表示已到底 */
    private String nextCursor = "";
    /** 正在加载下一页 */
    private boolean loadingMore = false;

    public SkillsMarketDialog(Window owner, MarketService marketService,
                              AgentAdminService agentAdminService, Runnable onChanged) {
        this.owner = owner;
        this.marketService = marketService;
        this.agentAdminService = agentAdminService;
        this.onChanged = onChanged;
    }

    /** 打开对话框并异步加载首页。 */
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
        // 输入即时本地过滤已加载结果；回车向 GitHub 发起带关键词的新检索
        searchField.textProperty().addListener((obs, o, n) -> render());
        searchField.setOnAction(e -> reload());

        HBox toolbar = new HBox(8, searchField, refreshButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        grid.setHgap(GRID_GAP);
        grid.setVgap(GRID_GAP);
        grid.setPadding(new Insets(2, 2, 8, 2));
        for (int c = 0; c < GRID_COLUMNS; c++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(100.0 / GRID_COLUMNS);
            col.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(col);
        }
        scroller.setContent(grid);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        content.setPrefSize(CONTENT_WIDTH, CONTENT_HEIGHT);
        content.setMinSize(CONTENT_WIDTH, CONTENT_HEIGHT);
        content.getChildren().setAll(scroller);

        loadMore.getStyleClass().add("link-btn");
        loadMore.setVisible(false);
        loadMore.setManaged(false);
        loadMore.setOnAction(e -> loadPage(true));
        HBox moreRow = new HBox(loadMore);
        moreRow.setAlignment(Pos.CENTER);
        moreRow.setPadding(new Insets(8, 0, 0, 0));

        statusLabel.getStyleClass().add("status-label");
        statusLabel.setPadding(new Insets(6, 0, 0, 0));

        shell.body().getChildren().addAll(toolbar, content, moreRow, statusLabel);
        shell.show();
        reload();
    }

    // ===================== 加载与渲染 =====================

    /** 重置并拉取第一页（秒出首屏）。 */
    private void reload() {
        loaded = new ArrayList<>();
        nextCursor = "";
        loadingMore = false;
        setContentMessage(Placeholder.LOADING, I18n.t("market.loading"), null);
        updateLoadMore();
        loadPage(false);
    }

    /**
     * 后台按当前搜索词拉取一页 GitHub 索引，回 UI 线程合并渲染。
     * {@code append} 为真时按 skillCode 去重追加（懒加载），否则替换（首屏/重试）。
     */
    private void loadPage(boolean append) {
        if (append) {
            loadingMore = true;
            updateLoadMore();
        }
        String kw = searchField.getText() == null ? "" : searchField.getText().trim();
        String cursor = nextCursor;
        Thread t = new Thread(() -> {
            try {
                MarketService.SkillPage page = marketService.fetchSkillPage(kw, cursor);
                Platform.runLater(() -> {
                    if (append) {
                        Set<String> seen = new LinkedHashSet<>();
                        for (MarketService.MarketSkill s : loaded) {
                            seen.add(s.skillCode());
                        }
                        for (MarketService.MarketSkill s : page.items()) {
                            if (seen.add(s.skillCode())) {
                                loaded.add(s);
                            }
                        }
                    } else {
                        loaded = new ArrayList<>(page.items());
                    }
                    nextCursor = page.nextCursor();
                    loadingMore = false;
                    statusLabel.setText(I18n.t("market.count", loaded.size()));
                    render();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    loadingMore = false;
                    if (append) {
                        statusLabel.setText(I18n.t("market.error", message(e)));
                        updateLoadMore();
                    } else {
                        statusLabel.setText("");
                        setContentMessage(Placeholder.ERROR, I18n.t("market.state.failed"),
                                I18n.t("market.state.error.desc"));
                    }
                });
            }
        }, "jwork-market-skills");
        t.setDaemon(true);
        t.start();
    }

    /** 「加载更多」按钮：仅当有下一页游标时可见，加载中禁用并改文案。 */
    private void updateLoadMore() {
        boolean hasMore = !nextCursor.isBlank();
        loadMore.setVisible(hasMore);
        loadMore.setManaged(hasMore);
        loadMore.setDisable(loadingMore);
        loadMore.setText(I18n.t(loadingMore ? "market.loading" : "market.loadMore"));
    }

    /** 按搜索词过滤已加载索引并重绘卡片。 */
    private void render() {
        updateLoadMore();
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
            if (loaded.isEmpty()) {
                setContentMessage(Placeholder.EMPTY, I18n.t("market.empty"), I18n.t("market.state.empty.desc"));
            } else {
                setContentMessage(Placeholder.EMPTY, I18n.t("market.emptyMatch"), I18n.t("market.state.nomatch.desc"));
            }
            return;
        }
        grid.getChildren().clear();
        for (int i = 0; i < visible.size(); i++) {
            grid.add(card(visible.get(i)), i % GRID_COLUMNS, i / GRID_COLUMNS);
        }
        content.getChildren().setAll(scroller);
    }

    // ===================== 卡片 =====================

    private Node card(MarketService.MarketSkill s) {
        VBox card = new VBox(8);
        card.getStyleClass().add("provider-card");
        card.setMaxWidth(Double.MAX_VALUE);
        card.setPrefHeight(212);

        // 名称（最多两行截断）+ 版本徽章 + star 徽章（右侧固定）
        Label name = new Label(s.skillName().isBlank() ? s.skillCode() : s.skillName());
        name.getStyleClass().add("provider-card-title");
        name.setMinWidth(0);
        name.setWrapText(true);
        name.setMaxHeight(44);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox titleRow = new HBox(8, name);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        // 关闭纵向拉伸，徽章才不被拉高、垂直居中才能生效
        titleRow.setFillHeight(false);
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        boolean spaced = false;
        if (!s.version().isBlank()) {
            titleRow.getChildren().add(titleSpacer);
            spaced = true;
            Label ver = new Label("v" + oneLine(s.version()));
            ver.getStyleClass().addAll("badge", "badge-gray");
            ver.setMinWidth(Region.USE_PREF_SIZE);
            ver.setMaxWidth(Region.USE_PREF_SIZE);
            titleRow.getChildren().add(ver);
        }
        if (s.downloadCount() > 0) {
            if (!spaced) {
                titleRow.getChildren().add(titleSpacer);
            }
            Label star = new Label(I18n.t("market.stars", String.valueOf(s.downloadCount())));
            star.getStyleClass().addAll("badge", "badge-gray");
            star.setMinWidth(Region.USE_PREF_SIZE);
            star.setMaxWidth(Region.USE_PREF_SIZE);
            titleRow.getChildren().add(star);
        }

        Label desc = new Label(s.description().isBlank() ? I18n.t("skills.noDesc") : oneLine(s.description()));
        desc.getStyleClass().add("card-desc");
        desc.setWrapText(true);
        desc.setMaxHeight(40);
        desc.setTextOverrun(OverrunStyle.ELLIPSIS);
        card.getChildren().addAll(titleRow, desc);

        // 分类标签：仓库 topic，最多 3 个
        if (!s.tags().isEmpty()) {
            HBox tagRow = new HBox(6);
            tagRow.setAlignment(Pos.CENTER_LEFT);
            for (String tag : s.tags()) {
                Label t = new Label(tag);
                t.getStyleClass().addAll("badge", "badge-gray");
                tagRow.getChildren().add(t);
            }
            card.getChildren().add(tagRow);
        }

        Region sp = new Region();
        VBox.setVgrow(sp, Priority.ALWAYS);
        card.getChildren().add(sp);

        // 作者（发布者）单行摘要
        if (!s.author().isBlank()) {
            Label metaLine = new Label(I18n.t("market.author", s.author()));
            metaLine.getStyleClass().add("card-meta");
            metaLine.setMaxWidth(Double.MAX_VALUE);
            metaLine.setTextOverrun(OverrunStyle.ELLIPSIS);
            card.getChildren().add(metaLine);
        }

        // 底部信息行：更新时间（左）+ 源码链接（右）
        boolean hasMeta = !s.updatedAt().isBlank();
        boolean hasLink = !s.homepage().isBlank();
        if (hasMeta || hasLink) {
            HBox metaRow = new HBox(8);
            metaRow.setAlignment(Pos.CENTER_LEFT);
            if (hasMeta) {
                Label updated = new Label(I18n.t("market.updatedAt", s.updatedAt()));
                updated.getStyleClass().add("card-meta");
                metaRow.getChildren().add(updated);
            }
            Region msp = new Region();
            HBox.setHgrow(msp, Priority.ALWAYS);
            metaRow.getChildren().add(msp);
            if (hasLink) {
                metaRow.getChildren().add(linkButton(I18n.t("market.repo"), s.homepage()));
            }
            card.getChildren().add(metaRow);
        }

        // 操作行：安装/更新/已安装（右对齐）
        Button action = buildActionButton(s);
        action.setMinWidth(Region.USE_PREF_SIZE);
        HBox foot = new HBox(8);
        foot.setAlignment(Pos.CENTER_LEFT);
        Region fsp = new Region();
        HBox.setHgrow(fsp, Priority.ALWAYS);
        foot.getChildren().addAll(fsp, action);
        card.getChildren().add(foot);
        return card;
    }

    /** 文字链接按钮：点击用系统浏览器打开给定 URL。 */
    private Button linkButton(String text, String url) {
        Button b = new Button(text);
        b.getStyleClass().add("link-btn");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setOnAction(e -> openUrl(url));
        return b;
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

    private void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            statusLabel.setText(I18n.t("market.requires.openFail", e.getMessage()));
        }
    }

    // ===================== 小工具 =====================

    /** 内容区占位态种类，决定图标与配色。 */
    private enum Placeholder { LOADING, EMPTY, ERROR }

    /**
     * 内容区占位（加载/空/错误）：圆形图标徽章 + 标题 + 弱化副标题，整体垂直居中；
     * {@link Placeholder#ERROR} 额外附「重试」按钮。
     */
    private void setContentMessage(Placeholder kind, String title, String desc) {
        Node anchor;
        if (kind == Placeholder.LOADING) {
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.setPrefSize(46, 46);
            spinner.getStyleClass().add("market-state-spinner");
            anchor = spinner;
        } else {
            Label glyph = new Label(kind == Placeholder.ERROR ? "\uE711" : "\uE71B");
            glyph.getStyleClass().add("market-state-glyph");
            if (kind == Placeholder.ERROR) {
                glyph.getStyleClass().add("market-state-glyph-error");
            }
            anchor = glyph;
        }

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("market-state-title");
        titleLabel.setWrapText(true);
        titleLabel.setTextAlignment(TextAlignment.CENTER);
        titleLabel.setMaxWidth(420);

        VBox text = new VBox(6, titleLabel);
        text.setAlignment(Pos.CENTER);
        if (desc != null && !desc.isBlank()) {
            Label descLabel = new Label(desc);
            descLabel.getStyleClass().add("market-state-desc");
            descLabel.setWrapText(true);
            descLabel.setTextAlignment(TextAlignment.CENTER);
            descLabel.setMaxWidth(420);
            text.getChildren().add(descLabel);
        }

        VBox box = new VBox(16, anchor, text);
        box.getStyleClass().add("market-state");
        box.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        box.setAlignment(Pos.CENTER);
        box.setFillWidth(false);
        if (kind == Placeholder.ERROR) {
            Button retry = new Button(I18n.t("market.retry"));
            Label retryIcon = new Label("\uE72C");
            retryIcon.getStyleClass().add("icon");
            retry.setGraphic(retryIcon);
            retry.getStyleClass().add("btn-primary");
            retry.setOnAction(e -> reload());
            box.getChildren().add(retry);
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
