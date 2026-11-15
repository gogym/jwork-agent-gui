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

import com.gettyio.gim.agent.ui.i18n.I18n;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MCP 市场对话框：配置模板一键添加 + 工具清单预览 + 依赖检测引导。
 * <p>索引来自 {@link MarketService#fetchMcp}；条目本质是预填配置（命令/参数/env），
 * 「添加」即登记到 mcp_servers 表（{@link MarketService#addMcp}），运行时由智能体声明后建连。
 * 条目声明的 {@code requires} 会做本地 PATH 探测，缺失时给出获取指引并二次确认。</p>
 *
 * @author gogym
 */
public class McpMarketDialog {

    private static final double CONTENT_WIDTH = 900;
    private static final double CONTENT_HEIGHT = 620;
    private static final int GRID_COLUMNS = 3;
    private static final double GRID_GAP = 14;

    private final Window owner;
    private final MarketService marketService;
    /** 添加成功后通知（MCP 页刷新；智能体运行时失效由 McpAdminService.save 内部完成） */
    private final Runnable onChanged;

    private final TextField searchField = new TextField();
    private final Button refreshButton = new Button();
    private final Label statusLabel = new Label();
    private final GridPane grid = new GridPane();
    private final ScrollPane scroller = new ScrollPane();
    private final StackPane content = new StackPane();
    private final Button loadMore = new Button();

    private List<MarketService.MarketMcp> loaded = new ArrayList<>();
    /** 依赖名 → 本机是否就绪（全量索引聚合后一次探测） */
    private Map<String, Boolean> depStatus = Map.of();
    /** 下一页游标，空表示已到底 */
    private String nextCursor = "";
    /** 正在加载下一页 */
    private boolean loadingMore = false;

    public McpMarketDialog(Window owner, MarketService marketService, Runnable onChanged) {
        this.owner = owner;
        this.marketService = marketService;
        this.onChanged = onChanged;
    }

    /** 打开对话框并异步加载索引。 */
    public void show() {
        GimDialog.Shell shell = GimDialog.shell(owner, I18n.t("market.mcp.title"));

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
        searchField.textProperty().addListener((obs, o, n) -> render());

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
        depStatus = Map.of();
        nextCursor = "";
        loadingMore = false;
        setContentMessage(Placeholder.LOADING, I18n.t("market.loading"), null);
        updateLoadMore();
        loadPage(false);
    }

    /**
     * 后台拉取一页 MCP 目录并做依赖探测，回 UI 线程合并渲染。
     * {@code append} 为真时按 serverCode 去重追加（懒加载），否则替换（首屏/重试）。
     */
    private void loadPage(boolean append) {
        if (append) {
            loadingMore = true;
            updateLoadMore();
        }
        String cursor = nextCursor;
        Thread t = new Thread(() -> {
            try {
                MarketService.McpPage page = marketService.fetchMcpPage("", cursor);
                Set<String> requires = new LinkedHashSet<>();
                for (MarketService.MarketMcp m : page.items()) {
                    requires.addAll(m.requires());
                }
                Map<String, Boolean> deps = marketService.probeDependencies(new ArrayList<>(requires));
                Platform.runLater(() -> {
                    if (append) {
                        Set<String> seen = new LinkedHashSet<>();
                        for (MarketService.MarketMcp m : loaded) {
                            seen.add(m.serverCode());
                        }
                        for (MarketService.MarketMcp m : page.items()) {
                            if (seen.add(m.serverCode())) {
                                loaded.add(m);
                            }
                        }
                        Map<String, Boolean> merged = new LinkedHashMap<>(depStatus);
                        merged.putAll(deps);
                        depStatus = merged;
                    } else {
                        loaded = new ArrayList<>(page.items());
                        depStatus = deps;
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
        }, "jwork-market-mcp");
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
        List<MarketService.MarketMcp> visible = loaded.stream()
                .filter(m -> kw.isEmpty()
                        || contains(m.serverName(), kw)
                        || contains(m.serverCode(), kw)
                        || contains(m.description(), kw)
                        || m.tags().stream().anyMatch(tag -> contains(tag, kw)))
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

    private Node card(MarketService.MarketMcp m) {
        VBox card = new VBox(8);
        card.getStyleClass().add("provider-card");
        card.setMaxWidth(Double.MAX_VALUE);
        card.setPrefHeight(212);

        // 名称（最多两行截断）+ 官方徽章 + 版本徽章
        Label name = new Label(m.serverName().isBlank() ? m.serverCode() : m.serverName());
        name.getStyleClass().add("provider-card-title");
        name.setMinWidth(0);
        name.setWrapText(true);
        name.setMaxHeight(44);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox titleRow = new HBox(8, name);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        // 关闭纵向拉伸，徽章才不被拉高、垂直居中才能生效
        titleRow.setFillHeight(false);
        // 名称随空间收缩换行，徽章锁定首选宽度不被挤压
        if (m.official()) {
            Label official = new Label(I18n.t("market.official"));
            official.getStyleClass().addAll("badge", "badge-blue");
            official.setMinWidth(Region.USE_PREF_SIZE);
            official.setMaxWidth(Region.USE_PREF_SIZE);
            titleRow.getChildren().add(official);
        }
        if (!m.version().isBlank()) {
            // 弹性占位把版本号推到标题行最右侧固定
            Region titleSpacer = new Region();
            HBox.setHgrow(titleSpacer, Priority.ALWAYS);
            titleRow.getChildren().add(titleSpacer);
            Label ver = new Label("v" + oneLine(m.version()));
            ver.getStyleClass().addAll("badge", "badge-gray");
            ver.setMinWidth(Region.USE_PREF_SIZE);
            ver.setMaxWidth(Region.USE_PREF_SIZE);
            titleRow.getChildren().add(ver);
        }

        Label desc = new Label(m.description().isBlank() ? I18n.t("skills.noDesc") : oneLine(m.description()));
        desc.getStyleClass().add("card-desc");
        desc.setWrapText(true);
        desc.setMaxHeight(40);
        desc.setTextOverrun(OverrunStyle.ELLIPSIS);

        card.getChildren().addAll(titleRow, desc);

        // 分类标签：发布者补充的 categories，最多 3 个
        if (!m.tags().isEmpty()) {
            HBox tagRow = new HBox(6);
            tagRow.setAlignment(Pos.CENTER_LEFT);
            for (String tag : m.tags()) {
                Label t = new Label(tag);
                t.getStyleClass().addAll("badge", "badge-gray");
                tagRow.getChildren().add(t);
            }
            card.getChildren().add(tagRow);
        }

        Region sp = new Region();
        VBox.setVgrow(sp, Priority.ALWAYS);
        card.getChildren().add(sp);

        // 配置摘要：传输类型 + 命令 / 地址
        String config = I18n.t("market.transport") + "：" + m.transportType();
        if (!m.command().isBlank()) {
            config += " · " + oneLine(m.command() + " " + String.join(" ", m.args()));
        } else if (!m.baseUrl().isBlank()) {
            config += " · " + m.baseUrl();
        }
        Label configLabel = new Label(config);
        configLabel.getStyleClass().add("card-meta");
        configLabel.setMaxWidth(Double.MAX_VALUE);
        configLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        card.getChildren().add(configLabel);

        // 依赖状态：就绪绿徽章、缺失红徽章 + 「获取」引导
        if (!m.requires().isEmpty()) {
            HBox deps = new HBox(6);
            deps.setAlignment(Pos.CENTER_LEFT);
            String missingHome = "";
            for (String dep : m.requires()) {
                boolean ok = Boolean.TRUE.equals(depStatus.get(dep.toLowerCase(Locale.ROOT)));
                Label badge = new Label(dep);
                badge.getStyleClass().addAll("badge", ok ? "badge-green" : "badge-red");
                deps.getChildren().add(badge);
                if (!ok && missingHome.isBlank()) {
                    missingHome = MarketService.dependencyHomepage(dep);
                }
            }
            if (!missingHome.isBlank()) {
                final String home = missingHome;
                Button get = new Button(I18n.t("market.requires.get"));
                get.getStyleClass().add("link-btn");
                get.setOnAction(e -> openUrl(home));
                deps.getChildren().add(get);
            }
            card.getChildren().add(deps);
        }

        // 底部信息行：更新时间（左）+ 源码/官网链接（右）
        boolean hasMeta = !m.updatedAt().isBlank();
        boolean hasLinks = !m.repoUrl().isBlank() || !m.homepage().isBlank();
        if (hasMeta || hasLinks) {
            HBox metaRow = new HBox(8);
            metaRow.setAlignment(Pos.CENTER_LEFT);
            if (hasMeta) {
                Label updated = new Label(I18n.t("market.updatedAt", m.updatedAt()));
                updated.getStyleClass().add("card-meta");
                metaRow.getChildren().add(updated);
            }
            Region msp = new Region();
            HBox.setHgrow(msp, Priority.ALWAYS);
            metaRow.getChildren().add(msp);
            if (!m.repoUrl().isBlank()) {
                metaRow.getChildren().add(linkButton(I18n.t("market.repo"), m.repoUrl()));
            }
            if (!m.homepage().isBlank()) {
                metaRow.getChildren().add(linkButton(I18n.t("market.website"), m.homepage()));
            }
            card.getChildren().add(metaRow);
        }

        // 操作行：工具数（左）+ 添加按钮（右）
        Button action = buildActionButton(m);
        action.setMinWidth(Region.USE_PREF_SIZE);
        HBox foot = new HBox(8);
        foot.setAlignment(Pos.CENTER_LEFT);
        if (!m.tools().isEmpty()) {
            Button tools = new Button(I18n.t("market.tools.count", m.tools().size()));
            tools.getStyleClass().add("link-btn");
            tools.setOnAction(e -> showTools(m));
            foot.getChildren().add(tools);
        }
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

    /** 操作按钮：已添加→禁用徽章；否则「添加」。 */
    private Button buildActionButton(MarketService.MarketMcp m) {
        if (marketService.localMcp(m.serverCode()) != null) {
            Label added = new Label(I18n.t("market.added"));
            added.getStyleClass().addAll("badge", "badge-green");
            Button placeholder = new Button();
            placeholder.setGraphic(added);
            placeholder.getStyleClass().add("link-btn");
            placeholder.setDisable(true);
            return placeholder;
        }
        Button action = new Button(I18n.t("market.add"));
        action.getStyleClass().add("btn-primary");
        action.setMinWidth(Region.USE_PREF_SIZE);
        action.setOnAction(e -> handleAdd(m));
        return action;
    }

    // ===================== 操作 =====================

    /** 添加：依赖缺失时二次确认（可先去获取依赖），确认后登记并刷新。 */
    private void handleAdd(MarketService.MarketMcp m) {
        List<String> missing = m.requires().stream()
                .filter(dep -> !Boolean.TRUE.equals(depStatus.get(dep.toLowerCase(Locale.ROOT))))
                .toList();
        if (!missing.isEmpty()) {
            String first = missing.get(0);
            String homepage = MarketService.dependencyHomepage(first);
            boolean goOn;
            if (homepage.isBlank()) {
                goOn = GimDialog.confirm(owner, I18n.t("market.mcp.title"),
                        I18n.t("market.requires.needConfirm", String.join("、", missing)));
            } else {
                int choice = GimDialog.choose(owner, I18n.t("market.mcp.title"), null,
                        I18n.t("market.requires.needConfirm", String.join("、", missing)),
                        I18n.t("market.requires.get"), I18n.t("market.requires.addAnyway"), I18n.t("common.cancel"));
                if (choice == 0) {
                    openUrl(homepage);
                    return;
                }
                goOn = choice == 1;
            }
            if (!goOn) {
                return;
            }
        }
        try {
            marketService.addMcp(m);
            statusLabel.setText(I18n.t("market.add.ok", m.serverName().isBlank() ? m.serverCode() : m.serverName()));
            if (onChanged != null) {
                onChanged.run();
            }
            render();
        } catch (Exception e) {
            statusLabel.setText(I18n.t("market.add.fail", message(e)));
        }
    }

    /** 工具清单预览对话框。 */
    private void showTools(MarketService.MarketMcp m) {
        GimDialog.Shell shell = GimDialog.shell(owner, I18n.t("market.tools.title"));
        VBox box = new VBox(8);
        box.setPrefWidth(480);
        for (MarketService.MarketMcpTool t : m.tools()) {
            Label name = new Label(t.name());
            name.getStyleClass().add("provider-card-title");
            box.getChildren().add(name);
            if (!t.description().isBlank()) {
                Label d = new Label(oneLine(t.description()));
                d.getStyleClass().add("card-desc");
                d.setWrapText(true);
                box.getChildren().add(d);
            }
        }
        ScrollPane scroller = new ScrollPane(box);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setPrefHeight(360);
        Button close = new Button(I18n.t("common.close"));
        close.setOnAction(e -> shell.stage().close());
        HBox btns = new HBox(8, close);
        btns.setAlignment(Pos.CENTER_RIGHT);
        shell.body().getChildren().addAll(scroller, btns);
        shell.show();
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
     * {@link Placeholder#ERROR} 额外附「重试」按钮。图标字形均为项目已用的安全码位。
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
