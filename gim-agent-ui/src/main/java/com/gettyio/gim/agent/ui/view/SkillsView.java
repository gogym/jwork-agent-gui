package com.gettyio.gim.agent.ui.view;

import com.gettyio.gim.agent.domain.entity.SkillPackage;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.tools.skill.SkillService;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.config.AgentAdminService;
import com.gettyio.gim.agent.ui.window.GimDialog;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * 技能整页（对标 Qoder「发现 Skills」页）：页头 + 搜索 + 技能卡片网格 + 空状态。
 * <p>支持从目录安装、启/禁用、删除与 SKILL.md 详情预览，直连 {@link SkillService}。
 * 全部文案走 {@link I18n}，语言切换时刷新页头并重绘卡片。</p>
 */
@Component
public class SkillsView {

    @Autowired
    private SkillService skillService;

    /** 技能目录变化后失效引用该技能的智能体运行时（技能仓库在装配时扫描目录） */
    @Autowired
    private AgentAdminService agentAdminService;

    private final VBox root = new VBox();
    private final TilePane grid = new TilePane();
    private final StackPane body = new StackPane();
    private ScrollPane scroller;
    private final TextField searchField = new TextField();
    private Label titleLabel;
    private Label descLabel;
    private Button installButton;
    private Button refreshButton;

    public SkillsView() {
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
        VBox headText = new VBox(4, titleLabel, descLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        refreshButton = iconButton("\uE72C", this::reload);
        searchField.getStyleClass().add("search-field");
        searchField.textProperty().addListener((obs, o, n) -> reload());
        installButton = new Button();
        installButton.getStyleClass().add("btn-primary");
        installButton.setOnAction(e -> handleInstall());

        HBox header = new HBox(10, headText, spacer, refreshButton, searchField, installButton);
        header.getStyleClass().add("page-header");

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
        installButton.setText(I18n.t("skills.new"));
        searchField.setPromptText(I18n.t("skills.searchPrompt"));
        refreshButton.getTooltip().setText(I18n.t("common.refresh"));
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
        card.setMinWidth(380);
        card.setMaxWidth(500);

        Label avatar = new Label(initial(s.getSkillName(), s.getSkillCode()));
        avatar.getStyleClass().add("skill-avatar");

        Label name = new Label(s.getSkillName() + "  " + (s.getVersion() == null ? "" : "v" + s.getVersion()));
        name.getStyleClass().add("card-title");
        Label code = new Label(s.getSkillCode());
        code.getStyleClass().add("mono");
        VBox textBox = new VBox(2, name, code);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        Label badge = enabledStatusBadge(s);
        HBox top = new HBox(12, avatar, textBox, badge);
        top.setAlignment(Pos.CENTER_LEFT);

        Label desc = new Label(truncate(s.getDescription(), 100));
        desc.getStyleClass().add("card-desc");
        desc.setWrapText(true);

        Label meta = new Label(sourceText(s.getSource()));
        meta.getStyleClass().add("card-meta");

        boolean enabled = EnabledStatus.isEnabled(s.getEnabled());
        HBox actions = new HBox(4);
        actions.getStyleClass().add("card-actions");
        actions.getChildren().add(linkButton(enabled ? I18n.t("skills.action.disable") : I18n.t("skills.action.enable"), () -> handleSetEnabled(s, !enabled)));
        actions.getChildren().add(linkButton(I18n.t("skills.action.detail"), () -> openDetail(s)));
        Button del = linkButton(I18n.t("skills.action.delete"), () -> confirmDelete(s));
        del.getStyleClass().add("link-btn-danger");
        actions.getChildren().add(del);

        card.getChildren().addAll(top, desc, meta, new Separator(), actions);
        return card;
    }

    private Label enabledStatusBadge(SkillPackage s) {
        Label b = new Label();
        b.getStyleClass().add("badge");
        if (EnabledStatus.isEnabled(s.getEnabled())) {
            b.setText(I18n.t("skills.badge.enabled"));
            b.getStyleClass().add("badge-green");
        } else {
            b.setText(I18n.t("skills.badge.disabled"));
            b.getStyleClass().add("badge-gray");
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
        box.getStyleClass().add("empty-state");
        box.setMaxWidth(Double.MAX_VALUE);
        Label icon = new Label("\uE945");
        icon.getStyleClass().add("empty-icon");
        Label t = new Label(allDeleted ? I18n.t("skills.empty.none") : I18n.t("skills.empty.noMatch"));
        t.getStyleClass().add("empty-title");
        Label d = new Label(allDeleted ? I18n.t("skills.empty.noneDesc") : I18n.t("skills.empty.noMatchDesc"));
        d.getStyleClass().add("empty-desc");
        d.setWrapText(true);
        d.setMaxWidth(460);
        box.getChildren().addAll(icon, t, d);
        return box;
    }

    // ---- 操作 ----

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
        if (!GimDialog.confirm(owner, I18n.t("skills.action.delete"),
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
        GimDialog.Shell shell = GimDialog.shell(owner, I18n.t("skills.detailTitle", s.getSkillCode()));

        Label head = new Label(s.getSkillName() + "（" + s.getSkillCode() + "）");
        head.getStyleClass().add("dialog-header");
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
        b.getStyleClass().add("icon-btn");
        b.setTooltip(new javafx.scene.control.Tooltip());
        Label icon = new Label(iconText);
        icon.getStyleClass().add("icon");
        b.setGraphic(icon);
        b.setOnAction(e -> action.run());
        return b;
    }

    private Button linkButton(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("link-btn");
        b.setOnAction(e -> action.run());
        return b;
    }

    private void info(String header, String msg) {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        GimDialog.message(owner, header, null, msg);
    }

    private void error(String header, String msg) {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        GimDialog.message(owner, header, null,
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

    private static String truncate(String s, int max) {
        if (s == null || s.isBlank()) {
            return I18n.t("skills.noDesc");
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }
}
