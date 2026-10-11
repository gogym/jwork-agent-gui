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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
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

    private static final double CONTENT_WIDTH = 620;
    private static final double CONTENT_HEIGHT = 430;

    private final Window owner;
    private final MarketService marketService;
    /** 添加成功后通知（MCP 页刷新；智能体运行时失效由 McpAdminService.save 内部完成） */
    private final Runnable onChanged;

    private final TextField searchField = new TextField();
    private final Button refreshButton = new Button();
    private final Label statusLabel = new Label();
    private final VBox list = new VBox(10);
    private final ScrollPane scroller = new ScrollPane();
    private final StackPane content = new StackPane();

    private List<MarketService.MarketMcp> loaded = new ArrayList<>();
    /** 依赖名 → 本机是否就绪（全量索引聚合后一次探测） */
    private Map<String, Boolean> depStatus = Map.of();

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

    /** 后台拉取索引并聚合依赖探测，随后渲染。 */
    private void reload() {
        statusLabel.setText(I18n.t("market.loading"));
        setContentMessage(I18n.t("market.loading"), false);
        Thread t = new Thread(() -> {
            try {
                List<MarketService.MarketMcp> items = marketService.fetchMcp("");
                Set<String> requires = new LinkedHashSet<>();
                for (MarketService.MarketMcp m : items) {
                    requires.addAll(m.requires());
                }
                Map<String, Boolean> deps = marketService.probeDependencies(new ArrayList<>(requires));
                Platform.runLater(() -> {
                    loaded = items;
                    depStatus = deps;
                    statusLabel.setText(I18n.t("market.count", items.size()));
                    render();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("");
                    setContentMessage(I18n.t("market.error", message(e)), true);
                });
            }
        }, "jwork-market-mcp");
        t.setDaemon(true);
        t.start();
    }

    /** 按搜索词过滤已加载索引并重绘卡片。 */
    private void render() {
        String kw = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<MarketService.MarketMcp> visible = loaded.stream()
                .filter(m -> kw.isEmpty()
                        || contains(m.serverName(), kw)
                        || contains(m.serverCode(), kw)
                        || contains(m.description(), kw)
                        || m.tags().stream().anyMatch(tag -> contains(tag, kw)))
                .toList();
        if (visible.isEmpty()) {
            setContentMessage(loaded.isEmpty() ? I18n.t("market.empty") : I18n.t("market.emptyMatch"), false);
            return;
        }
        list.getChildren().clear();
        for (MarketService.MarketMcp m : visible) {
            list.getChildren().add(card(m));
        }
        content.getChildren().setAll(scroller);
    }

    // ===================== 卡片 =====================

    private Node card(MarketService.MarketMcp m) {
        VBox card = new VBox(8);
        card.getStyleClass().add("provider-card");
        card.setMaxWidth(Double.MAX_VALUE);

        Label name = new Label(m.serverName().isBlank() ? m.serverCode() : m.serverName());
        name.getStyleClass().add("provider-card-title");
        name.setMinWidth(0);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox titleRow = new HBox(8, name);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        if (m.official()) {
            Label official = new Label(I18n.t("market.official"));
            official.getStyleClass().addAll("badge", "badge-blue");
            titleRow.getChildren().add(official);
        }
        for (String tag : m.tags()) {
            Label tagLabel = new Label(tag);
            tagLabel.getStyleClass().addAll("badge", "badge-gray");
            titleRow.getChildren().add(tagLabel);
        }

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button action = buildActionButton(m);
        HBox head = new HBox(titleRow, sp, action);
        head.setAlignment(Pos.CENTER_LEFT);

        Label desc = new Label(m.description().isBlank() ? I18n.t("skills.noDesc") : oneLine(m.description()));
        desc.getStyleClass().add("card-desc");
        desc.setWrapText(true);
        desc.setMaxHeight(40);
        desc.setTextOverrun(OverrunStyle.ELLIPSIS);

        card.getChildren().addAll(head, desc);

        // 配置摘要：传输类型 + 命令 / 地址
        String config = I18n.t("market.transport") + "：" + m.transportType();
        if (!m.command().isBlank()) {
            config += " · " + I18n.t("market.command") + "：" + oneLine(m.command() + " " + String.join(" ", m.args()));
        } else if (!m.baseUrl().isBlank()) {
            config += " · " + I18n.t("market.command") + "：" + m.baseUrl();
        }
        Label configLabel = new Label(config);
        configLabel.getStyleClass().add("card-meta");
        configLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        card.getChildren().add(configLabel);

        // 依赖状态行：就绪绿色徽章；缺失红色徽章 + 「获取」引导链接
        if (!m.requires().isEmpty()) {
            HBox deps = new HBox(6);
            deps.setAlignment(Pos.CENTER_LEFT);
            Label depCaption = new Label(I18n.t("market.requires") + "：");
            depCaption.getStyleClass().add("card-meta");
            deps.getChildren().add(depCaption);
            for (String dep : m.requires()) {
                boolean ok = Boolean.TRUE.equals(depStatus.get(dep.toLowerCase(Locale.ROOT)));
                Label badge = new Label(dep);
                badge.getStyleClass().addAll("badge", ok ? "badge-green" : "badge-red");
                deps.getChildren().add(badge);
                if (!ok) {
                    String homepage = MarketService.dependencyHomepage(dep);
                    if (!homepage.isBlank()) {
                        Button get = new Button(I18n.t("market.requires.get"));
                        get.getStyleClass().add("link-btn");
                        get.setOnAction(e -> openUrl(homepage));
                        deps.getChildren().add(get);
                    }
                }
            }
            card.getChildren().add(deps);
        }

        // 工具清单预览
        if (!m.tools().isEmpty()) {
            Button tools = new Button(I18n.t("market.tools.count", m.tools().size()));
            tools.getStyleClass().add("link-btn");
            tools.setOnAction(e -> showTools(m));
            HBox toolRow = new HBox(tools);
            toolRow.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().add(toolRow);
        }
        return card;
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

    /** 内容区占位（加载/空/错误）；{@code error} 为真时附「重试」按钮。 */
    private void setContentMessage(String message, boolean error) {
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
        if (error) {
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
