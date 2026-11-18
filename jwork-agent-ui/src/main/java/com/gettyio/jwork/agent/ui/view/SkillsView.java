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

package com.gettyio.jwork.agent.ui.view;

import com.gettyio.jwork.agent.domain.entity.SkillPackage;
import com.gettyio.jwork.agent.domain.enums.EnabledStatus;
import com.gettyio.jwork.agent.tools.skill.SkillService;
import com.gettyio.jwork.agent.ui.i18n.I18n;
import com.gettyio.jwork.agent.ui.chat.MenuPopup;
import com.gettyio.jwork.agent.ui.config.AgentAdminService;
import com.gettyio.jwork.agent.ui.market.MarketService;
import com.gettyio.jwork.agent.ui.market.SkillsMarketDialog;
import com.gettyio.jwork.agent.ui.util.UiStyles;
import com.gettyio.jwork.agent.ui.window.dialog.JworkDialog;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * 技能整页（对标 Qoder「发现 Skills」页）：页头 + 搜索 + 技能卡片网格 + 空状态。
 * <p>支持从目录安装、启/禁用、删除与 SKILL.md 详情预览，直连 {@link SkillService}。
 * 全部文案走 {@link I18n}，语言切换时刷新页头并重绘卡片。</p>
 *
 * @author gogym
 */
public class SkillsView {

    private final SkillService skillService;
    /** 技能目录变化后失效引用该技能的智能体运行时（技能仓库在装配时扫描目录） */
    private final AgentAdminService agentAdminService;
    /** 在线市场：搜索/安装远端技能（来源标记为 MARKETPLACE） */
    private final MarketService marketService;

    private final VBox root = new VBox();
    private final TilePane grid = new TilePane();
    private final StackPane body = new StackPane();
    private ScrollPane scroller;
    private final TextField searchField = new TextField();
    private Label titleLabel;
    private Label descLabel;
    private Button importButton;
    private ContextMenu importMenu;
    private long importMenuHiddenAt;
    private Label importLabel;
    private MenuItem importFolderItem;
    private MenuItem importFileItem;
    private Button refreshButton;
    private Button marketButton;

    public SkillsView(SkillService skillService, AgentAdminService agentAdminService, MarketService marketService) {
        this.skillService = skillService;
        this.agentAdminService = agentAdminService;
        this.marketService = marketService;
        buildUi();
        I18n.onChange(lang -> {
            applyI18n();
            reload();
        });
    }

    public VBox getNode() {
        return root;
    }

    /** 控制页头一级标题显隐（拓展页 Tab 内隐藏，避免与标签名重复）。 */
    public void showHeaderTitle(boolean visible) {
        if (titleLabel != null) {
            titleLabel.setVisible(visible);
            titleLabel.setManaged(visible);
        }
    }

    private void buildUi() {
        root.getStyleClass().add("content-page");

        titleLabel = new Label();
        titleLabel.getStyleClass().add("page-h1");
        descLabel = new Label();
        descLabel.getStyleClass().add("page-desc");
        // 窄窗口下描述不换行，压缩到极限后省略号隐藏，始终保住右侧操作区
        descLabel.setMinWidth(0);
        descLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        refreshButton = iconButton("\uE72C", this::reload);
        refreshButton.setMinWidth(Region.USE_PREF_SIZE);
        searchField.getStyleClass().add("search-field");
        searchField.setMinWidth(140);
        searchField.textProperty().addListener((obs, o, n) -> reload());
        importButton = new Button();
        importButton.getStyleClass().addAll(UiStyles.BTN_PRIMARY, "skill-import-btn");
        importButton.setMinWidth(Region.USE_PREF_SIZE);
        // 文字与下拉箭头都做成自绘子节点放进 graphic：控件自身的 -fx-text-fill 会被
        // .btn-primary/-jwork-bg 与全局 .label 规则拉扯、颜色难稳定夺回；改为真实 Label 后其
        // -fx-text-fill 可预测生效，与实心三角 caret 同用 #ffffff，颜色天然一致
        importLabel = new Label();
        importLabel.getStyleClass().add("skill-import-text");
        Region caret = new Region();
        caret.getStyleClass().add("skill-import-caret");
        HBox importContent = new HBox(6, importLabel, caret);
        importContent.setAlignment(Pos.CENTER);
        importButton.setGraphic(importContent);
        importButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        // 用 Button + ContextMenu 取代 MenuButton：MenuButton 内置弹层只夹取到屏幕，会超出主窗体；
        // 改由 MenuPopup.showBelow 把弹层夹到窗口内（与聊天工具条菜单一致）
        importMenu = new ContextMenu();
        importMenu.setOnHiding(e -> importMenuHiddenAt = System.currentTimeMillis());
        importFolderItem = new MenuItem();
        importFolderItem.setOnAction(e -> handleInstall());
        importFileItem = new MenuItem();
        importFileItem.setOnAction(e -> handleImportFile());
        importMenu.getItems().addAll(importFolderItem, importFileItem);
        importButton.setOnAction(e -> showImportMenu());

        // 市场入口：默认中性样式，与右侧主按钮「导入」形成层次
        marketButton = new Button();
        marketButton.setMinWidth(Region.USE_PREF_SIZE);
        Label marketIcon = new Label("\uE719");
        marketIcon.getStyleClass().add("icon");
        marketButton.setGraphic(marketIcon);
        marketButton.setOnAction(e -> openMarket());

        // 右侧控件与描述同一行：行内中心对齐使控件中心线与描述文字中心共线，标题独占上一行
        HBox descRow = new HBox(10, descLabel, spacer, refreshButton, searchField, marketButton, importButton);
        descRow.setAlignment(Pos.CENTER_LEFT);
        VBox headText = new VBox(4, titleLabel, descRow);
        headText.setMaxWidth(Double.MAX_VALUE);

        HBox header = new HBox(headText);
        HBox.setHgrow(headText, Priority.ALWAYS);
        header.getStyleClass().add(UiStyles.PAGE_HEADER);

        grid.getStyleClass().add("card-grid");
        grid.setHgap(14);
        grid.setVgap(14);
        grid.setPrefColumns(2);
        grid.setTileAlignment(Pos.TOP_LEFT);

        scroller = new ScrollPane(grid);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        body.getChildren().add(scroller);
        VBox.setVgrow(body, Priority.ALWAYS);

        root.getChildren().addAll(header, body);
        applyI18n();
    }

    private void applyI18n() {
        titleLabel.setText(I18n.t("skills.title"));
        descLabel.setText(I18n.t("skills.desc"));
        importLabel.setText(I18n.t("skills.import.menu"));
        importFolderItem.setText(I18n.t("skills.new"));
        importFileItem.setText(I18n.t("skills.importFile"));
        searchField.setPromptText(I18n.t("skills.searchPrompt"));
        refreshButton.getTooltip().setText(I18n.t("common.refresh"));
        marketButton.setText(I18n.t("market.entry"));
    }

    /** 弹出/收起导入菜单：在按钮下方弹出并夹到主窗体内；已开则收起，刚被系统收起的 250ms 内不重开。 */
    private void showImportMenu() {
        if (importMenu.isShowing()) {
            importMenu.hide();
            return;
        }
        if (MenuPopup.inHideDebounce(importMenuHiddenAt)) {
            return;
        }
        MenuPopup.showBelow(importMenu, importButton);
    }

    /** 重新加载技能卡片（切页或语言切换时调用） */
    public void reload() {
        String kw = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<SkillPackage> skills = skillService.list().stream()
                .filter(s -> kw.isEmpty()
                        || contains(s.getSkillName(), kw)
                        || contains(s.getSkillCode(), kw)
                        || contains(s.getDescription(), kw))
                .toList();
        grid.getChildren().clear();
        if (skills.isEmpty()) {
            body.getChildren().setAll(scroller, emptyState(kw.isEmpty()));
            return;
        }
        body.getChildren().setAll(scroller);
        skills.forEach(s -> grid.getChildren().add(card(s)));
    }

    // ---- 卡片 ----

    private Node card(SkillPackage s) {
        VBox card = new VBox(6);
        card.getStyleClass().add("skill-card");
        card.setMinWidth(280);
        card.setMaxWidth(340);

        Label avatar = new Label(initial(s.getSkillName(), s.getSkillCode()));
        avatar.getStyleClass().add("skill-avatar");

        Label name = new Label(s.getSkillName() + "  " + (s.getVersion() == null ? "" : "v" + s.getVersion()));
        name.getStyleClass().add("card-title");
        // 窄卡下长名称允许压缩，最多折两行后省略号，不撑宽瓦片；悬停看全文
        name.setMinWidth(0);
        name.setMaxWidth(Double.MAX_VALUE);
        clampLines(name, 2);
        name.setTooltip(new Tooltip(name.getText()));
        Label code = new Label(s.getSkillCode());
        code.getStyleClass().add("mono");
        code.setMinWidth(0);
        code.setMaxWidth(Double.MAX_VALUE);
        VBox textBox = new VBox(2, name, code);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        Label badge = enabledStatusBadge(s);
        badge.setMinWidth(Region.USE_PREF_SIZE);
        HBox top = new HBox(12, avatar, textBox, badge);
        top.setAlignment(Pos.CENTER_LEFT);

        Label desc = new Label(oneLine(s.getDescription()));
        desc.getStyleClass().add(UiStyles.CARD_DESC);
        clampLines(desc, 2);
        desc.setTooltip(new Tooltip(desc.getText()));

        Label meta = new Label(sourceText(s.getSource()));
        meta.getStyleClass().add(UiStyles.CARD_META);

        boolean enabled = EnabledStatus.isEnabled(s.getEnabled());
        HBox actions = new HBox(4);
        actions.getStyleClass().add("card-actions");
        actions.setMinWidth(Region.USE_PREF_SIZE);
        actions.getChildren().add(linkButton(enabled ? I18n.t("skills.action.disable") : I18n.t("skills.action.enable"), () -> handleSetEnabled(s, !enabled)));
        actions.getChildren().add(linkButton(I18n.t("skills.action.detail"), () -> openDetail(s)));
        Button del = linkButton(I18n.t("skills.action.delete"), () -> confirmDelete(s));
        del.getStyleClass().add(UiStyles.LINK_BTN_DANGER);
        actions.getChildren().add(del);

        // 弹性占位把「来源 + 分隔线 + 操作按钮」整体压到卡片底部，同瓦片高度的卡片按钮行齐平
        Region footSpacer = new Region();
        VBox.setVgrow(footSpacer, Priority.ALWAYS);

        card.getChildren().addAll(top, desc, footSpacer, meta, new Separator(), actions);
        return card;
    }

    /**
     * 文本最多折 maxLines 行、超出截为省略号：wrapText + 按标签实际字体把 maxHeight 钉在 N 行高。
     * 字体/内边距由 CSS 异步套用（字号三档样式表会切），故在皮肤创建与字体变更时各重测一次。
     */
    private static void clampLines(Label label, int maxLines) {
        label.setWrapText(true);
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        Runnable apply = () -> {
            Text probe = new Text("Ag中文");
            probe.setFont(label.getFont());
            double lineHeight = Math.ceil(probe.getBoundsInLocal().getHeight());
            label.setMaxHeight(lineHeight * maxLines
                    + label.getPadding().getTop() + label.getPadding().getBottom());
        };
        label.skinProperty().addListener((obs, old, skin) -> {
            if (skin != null) {
                Platform.runLater(apply);
            }
        });
        label.fontProperty().addListener((obs, old, font) -> Platform.runLater(apply));
    }

    private Label enabledStatusBadge(SkillPackage s) {
        Label b = new Label();
        b.getStyleClass().add("badge");
        if (EnabledStatus.isEnabled(s.getEnabled())) {
            b.setText(I18n.t("skills.badge.enabled"));
            b.getStyleClass().add(UiStyles.BADGE_GREEN);
        } else {
            b.setText(I18n.t("skills.badge.disabled"));
            b.getStyleClass().add(UiStyles.BADGE_GRAY);
        }
        return b;
    }

    private String sourceText(String source) {
        if (source == null) {
            return I18n.t("skills.source.unknown");
        }
        return switch (source.toUpperCase(Locale.ROOT)) {
            case "BUILTIN" -> I18n.t("skills.source.builtin");
            case "INSTALLED" -> I18n.t("skills.source.installed");
            case "MARKETPLACE" -> I18n.t("skills.source.marketplace");
            default -> source;
        };
    }

    private Node emptyState(boolean allDeleted) {
        VBox box = new VBox();
        box.getStyleClass().add(UiStyles.EMPTY_STATE);
        box.setMaxWidth(Double.MAX_VALUE);
        Label icon = new Label("\uE945");
        icon.getStyleClass().add(UiStyles.EMPTY_ICON);
        Label t = new Label(allDeleted ? I18n.t("skills.empty.none") : I18n.t("skills.empty.noMatch"));
        t.getStyleClass().add(UiStyles.EMPTY_TITLE);
        Label d = new Label(allDeleted ? I18n.t("skills.empty.noneDesc") : I18n.t("skills.empty.noMatchDesc"));
        d.getStyleClass().add(UiStyles.EMPTY_DESC);
        d.setWrapText(true);
        d.setMaxWidth(460);
        box.getChildren().addAll(icon, t, d);
        return box;
    }

    // ---- 操作 ----

    /** 打开技能市场对话框（安装成功回调刷新本页卡片）。 */
    private void openMarket() {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        new SkillsMarketDialog(owner, marketService, agentAdminService, this::reload).show();
    }

    private void handleInstall() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.t("skills.install.title"));
        File dir = chooser.showDialog(root.getScene() == null ? null : root.getScene().getWindow());
        if (dir == null) {
            return;
        }
        try {
            SkillPackage s = skillService.install(dir.toPath());
            agentAdminService.onSkillCatalogChanged(s.getSkillCode());
            reload();
            info(I18n.t("skills.install.okTitle"), I18n.t("skills.install.okMsg", s.getSkillName(), s.getSkillCode()));
        } catch (Exception e) {
            error(I18n.t("skills.install.failTitle"), e.getMessage());
        }
    }

    /** 从单个文件导入技能：{@code .zip}（含 SKILL.md）或单个 {@code .md}。 */
    private void handleImportFile() {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("skills.import.fileTitle"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.t("skills.import.fileFilter"), "*.zip", "*.md"));
        File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return;
        }
        try {
            SkillPackage s = skillService.importFromFile(file.toPath());
            agentAdminService.onSkillCatalogChanged(s.getSkillCode());
            reload();
            info(I18n.t("skills.install.okTitle"), I18n.t("skills.install.okMsg", s.getSkillName(), s.getSkillCode()));
        } catch (Exception e) {
            error(I18n.t("skills.install.failTitle"), e.getMessage());
        }
    }

    private void handleSetEnabled(SkillPackage s, boolean enable) {
        try {
            skillService.setEnabled(s.getSkillCode(), enable);
            agentAdminService.onSkillCatalogChanged(s.getSkillCode());
            reload();
        } catch (Exception e) {
            error(I18n.t("skills.op.failTitle"), e.getMessage());
        }
    }

    private void confirmDelete(SkillPackage s) {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        if (!JworkDialog.confirm(owner, I18n.t("skills.action.delete"),
                I18n.t("skills.deleteConfirm", s.getSkillName()))) {
            return;
        }
        try {
            skillService.delete(s.getSkillCode());
            agentAdminService.onSkillCatalogChanged(s.getSkillCode());
            reload();
        } catch (Exception e) {
            error(I18n.t("skills.delete.failTitle"), e.getMessage());
        }
    }

    private void openDetail(SkillPackage s) {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        JworkDialog.Shell shell = JworkDialog.shell(owner, I18n.t("skills.detailTitle", s.getSkillCode()));

        Label head = new Label(s.getSkillName() + "（" + s.getSkillCode() + "）");
        head.getStyleClass().add(UiStyles.DIALOG_HEADER);
        TextArea content = new TextArea(skillService.readContent(s.getSkillCode()));
        content.setEditable(false);
        content.setWrapText(true);
        content.setPrefWidth(600);
        content.setPrefHeight(420);
        content.setStyle("-fx-font-family: \"Cascadia Mono\", \"Consolas\", monospace; -fx-font-size: 12px;");

        Button close = new Button(I18n.t("common.close"));
        close.setOnAction(e -> shell.stage().close());
        HBox btns = new HBox(8, close);
        btns.setAlignment(Pos.CENTER_RIGHT);

        shell.body().getChildren().addAll(head, content, btns);
        shell.show();
    }

    // ---- 小工具 ----

    private Button iconButton(String iconText, Runnable action) {
        Button b = new Button();
        b.getStyleClass().add(UiStyles.ICON_BTN);
        b.setTooltip(new javafx.scene.control.Tooltip());
        Label icon = new Label(iconText);
        icon.getStyleClass().add("icon");
        b.setGraphic(icon);
        b.setOnAction(e -> action.run());
        return b;
    }

    private Button linkButton(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add(UiStyles.LINK_BTN);
        b.setOnAction(e -> action.run());
        return b;
    }

    private void info(String header, String msg) {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        JworkDialog.message(owner, header, null, msg);
    }

    private void error(String header, String msg) {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        JworkDialog.message(owner, header, null,
                msg == null ? I18n.t("common.error.unknown") : msg);
    }

    private static String initial(String name, String code) {
        String src = name == null || name.isBlank() ? code : name;
        if (src == null || src.isEmpty()) {
            return "?";
        }
        return String.valueOf(src.charAt(0)).toUpperCase(Locale.ROOT);
    }

    private static boolean contains(String s, String kw) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(kw);
    }

    /** 描述压成单行文本（换行/连续空白折叠），空描述用占位文案；行数截断交给 clampLines。 */
    private static String oneLine(String s) {
        if (s == null || s.isBlank()) {
            return I18n.t("skills.noDesc");
        }
        return s.replaceAll("\\s+", " ").trim();
    }
}
