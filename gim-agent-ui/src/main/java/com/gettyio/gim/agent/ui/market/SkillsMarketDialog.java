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
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.config.AgentAdminService;
import com.gettyio.gim.agent.ui.update.UpdateChecker;
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
 *
 * @author gogym
 */
public class SkillsMarketDialog {

    private static final double CONTENT_WIDTH = 620;
    private static final double CONTENT_HEIGHT = 430;

    private final Window owner;
    private final MarketService marketService;
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

    public SkillsMarketDialog(Window owner, MarketService marketService,
                              AgentAdminService agentAdminService, Runnable onChanged) {
        this.owner = owner;
        this.marketService = marketService;
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

        shell.body().getChildren().addAll(toolbar, content, statusLabel);
        shell.show();
        reload();
    }

    // ===================== 加载与渲染 =====================

    /** 后台拉取全量索引。 */
    private void reload() {
        setContentMessage(Placeholder.LOADING, I18n.t("market.loading"), null);
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
                    setContentMessage(Placeholder.ERROR, I18n.t("market.state.failed"),
                            I18n.t("market.state.error.desc"));
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
            if (loaded.isEmpty()) {
                setContentMessage(Placeholder.EMPTY, I18n.t("market.empty"), I18n.t("market.state.empty.desc"));
            } else {
                setContentMessage(Placeholder.EMPTY, I18n.t("market.emptyMatch"), I18n.t("market.state.nomatch.desc"));
            }
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
