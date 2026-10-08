package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.config.ProviderAdminService;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * 设置窗口共享的无状态布局构件：页面骨架、分组卡片、设置行、胶囊下拉、信息卡片等纯节点构造。
 * <p>由 {@link SettingsWindow} 及按设置页拆出的各子面板（如 {@link SecuritySettingsPanel}）复用，
 * 集中样式类名与布局细节，避免每个页面重复同一段搭样代码。全部为纯函数，不持有窗口状态。</p>
 */
final class SettingsLayout {

    private SettingsLayout() {
    }

    /** 页面骨架：标题 + 可选副标题，占满可用宽高。 */
    static VBox page(String title, String subtitle) {
        VBox box = new VBox();
        box.getStyleClass().add("settings-page");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMaxHeight(Double.MAX_VALUE);
        Label t = new Label(title);
        t.getStyleClass().add("page-title");
        box.getChildren().add(t);
        if (subtitle != null && !subtitle.isBlank()) {
            Label s = new Label(subtitle);
            s.getStyleClass().add("page-subtitle");
            s.setWrapText(true);
            box.getChildren().add(s);
        }
        return box;
    }

    /** 分组卡片：可选分组标题 + 卡片容器；相邻设置行之间自动补分隔线。 */
    static VBox groupCard(String groupTitle, List<Node> rows) {
        VBox wrap = new VBox(6);
        if (groupTitle != null && !groupTitle.isBlank()) {
            Label gt = new Label(groupTitle);
            gt.getStyleClass().add("settings-group-title");
            wrap.getChildren().add(gt);
        }
        VBox card = new VBox();
        card.getStyleClass().add("settings-group");
        for (int i = 0; i < rows.size(); i++) {
            Node r = rows.get(i);
            if (i > 0 && r instanceof HBox hb) {
                hb.getStyleClass().add("settings-row-divider");
            }
            card.getChildren().add(r);
        }
        wrap.getChildren().add(card);
        return wrap;
    }

    /** 一行：左侧标题 + 描述，右侧控件；{@code desc}/{@code control} 可为 null。 */
    static HBox settingRow(String title, String desc, Node control) {
        VBox text = new VBox(3);
        text.getStyleClass().add("row-text");
        Label t = new Label(title);
        t.getStyleClass().add("row-title");
        text.getChildren().add(t);
        if (desc != null && !desc.isBlank()) {
            Label d = new Label(desc);
            d.getStyleClass().add("row-desc");
            d.setWrapText(true);
            d.setMaxWidth(520);
            text.getChildren().add(d);
        }
        HBox row = new HBox(text);
        row.getStyleClass().add("settings-row");
        HBox.setHgrow(text, Priority.ALWAYS);
        if (control != null) {
            HBox ctrl = new HBox(control);
            ctrl.getStyleClass().add("row-control");
            row.getChildren().add(ctrl);
        }
        return row;
    }

    static ComboBox<String> pillCombo(String... options) {
        ComboBox<String> c = new ComboBox<>(FXCollections.observableArrayList(options));
        c.getStyleClass().add("pill-combo");
        return c;
    }

    static Label valueLabel(String value) {
        Label l = new Label(value);
        l.getStyleClass().add("row-value");
        return l;
    }

    static Button iconTextButton(String iconText, String text, Runnable action) {
        Button b = new Button(text);
        Label ic = new Label(iconText);
        ic.getStyleClass().add("icon");
        b.setGraphic(ic);
        b.setOnAction(e -> action.run());
        return b;
    }

    static VBox labeledField(String label, Node field) {
        Label l = new Label(label);
        l.getStyleClass().add("form-label");
        return new VBox(4, l, field);
    }

    static Button newButton(String text, Runnable action) {
        Button b = new Button(text);
        b.setOnAction(e -> action.run());
        return b;
    }

    /** 分组块：标题 + 卡片（首行为描述 + 右侧操作按钮，其下为动态内容容器）。 */
    static VBox sectionBlock(String title, String desc, List<Node> actions, Region content) {
        Label gt = new Label(title);
        gt.getStyleClass().add("settings-group-title");

        Label d = new Label(desc);
        d.getStyleClass().add("row-desc");
        d.setWrapText(true);
        d.setMaxWidth(560);
        // 占位把操作按钮顶到卡片右缘；header 用 CENTER_LEFT 使按钮相对换行后的描述垂直居中
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox header = new HBox(8, d, sp);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("settings-row");
        for (Node a : actions) {
            if (a instanceof Region r) {
                r.setMinWidth(Region.USE_PREF_SIZE);
            }
            header.getChildren().add(a);
        }

        content.setMaxWidth(Double.MAX_VALUE);
        VBox card = new VBox(6, header, content);
        card.getStyleClass().add("settings-group");
        return new VBox(6, gt, card);
    }

    /**
     * 分组块（按钮整体垂直居中）：适用于描述 + 短列表的分区（Shell 白名单、文件授权目录、授权管理）。
     * <p>卡片左侧为「描述 + 内容」列，右侧操作按钮相对整列垂直居中，避免按钮只贴首行而右下方留白。
     * 危险命令区因下方是长列表不适用此布局（仍用 {@link #sectionBlock}）。</p>
     */
    static VBox sectionBlockCentered(String title, String desc, List<Node> actions, Region content) {
        Label gt = new Label(title);
        gt.getStyleClass().add("settings-group-title");

        Label d = new Label(desc);
        d.getStyleClass().add("row-desc");
        d.setWrapText(true);
        d.setMaxWidth(560);

        content.setMaxWidth(Double.MAX_VALUE);
        VBox body = new VBox(6, d, content);
        body.setPadding(new Insets(14, 0, 14, 0));
        body.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(body, Priority.ALWAYS);

        HBox actionsBox = new HBox(8);
        actionsBox.setAlignment(Pos.CENTER);
        for (Node a : actions) {
            if (a instanceof Region r) {
                r.setMinWidth(Region.USE_PREF_SIZE);
            }
            actionsBox.getChildren().add(a);
        }

        HBox card = new HBox(12, body, actionsBox);
        card.getStyleClass().add("settings-group");
        card.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, gt, card);
    }

    /** 单行：左侧标题（可带副标题）+ 右侧删除按钮。 */
    static HBox listRow(String title, String sub, Runnable onRemove) {
        Label t = new Label(title);
        t.getStyleClass().add("row-title");
        t.setWrapText(true);
        VBox text = new VBox(2, t);
        if (sub != null && !sub.isBlank()) {
            Label s = new Label(sub);
            s.getStyleClass().add("row-desc");
            text.getChildren().add(s);
        }
        text.getStyleClass().add("row-text");

        Button remove = iconTextButton("\uE74D", I18n.t("common.delete"), onRemove);
        remove.getStyleClass().addAll("link-btn", "link-btn-danger");
        remove.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(12, text, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        HBox.setHgrow(text, Priority.ALWAYS);
        return row;
    }

    static Label emptyHint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("row-desc");
        l.setWrapText(true);
        return l;
    }

    /** 卡片流式容器：宽度够时横向并排，不够则换行；卡片按 {@link #CARD_WIDTH} 取自身尺寸。 */
    static FlowPane cardsBox() {
        FlowPane box = new FlowPane(12, 12);
        box.setAlignment(Pos.TOP_LEFT);
        box.setRowValignment(javafx.geometry.VPos.TOP);
        return box;
    }

    /** 信息卡片固定宽度：不铺满整行。 */
    static final double CARD_WIDTH = 400;

    /** 只读信息行：键 + 值文本（值以字符串呈现，通用页路径行复用）。 */
    static HBox infoRow(String key, Object value) {
        return settingRow(key, String.valueOf(value), null);
    }

    /** 人格可能很长，卡片内只预览折叠后的一小段。 */
    static String preview(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= 72 ? flat : flat.substring(0, 72) + "\u2026";
    }

    /** MessageFormat 会给数字加上分组分隔符（2,000），计数与参数统一以文本传入。 */
    static String str(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    static HBox kvLine(String key, String value) {
        Label k = new Label(key);
        k.getStyleClass().add("provider-card-key");
        Label v = new Label(value == null || value.isBlank() ? "-" : value);
        v.getStyleClass().add("provider-card-val");
        v.setWrapText(true);
        HBox.setHgrow(v, Priority.ALWAYS);
        HBox h = new HBox(12, k, v);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** 图标行：与 kvLine 同构，但值是一个富节点（图标 + 文字）。 */
    static HBox kvIconLine(String key, Node value) {
        Label k = new Label(key);
        k.getStyleClass().add("provider-card-key");
        HBox h = new HBox(12, k, value);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** 构造“Segoe 图标 + 主题色 + 说明文字”的小行值。 */
    static HBox iconTextValue(String glyph, String colorClass, String text) {
        Label ic = new Label(glyph);
        ic.getStyleClass().addAll("icon", "card-status-icon", colorClass);
        Label tx = new Label(text);
        tx.getStyleClass().add("provider-card-val");
        HBox h = new HBox(6, ic, tx);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** 连通状态行：可用=绿勾、不可用=红叉、未测试=灰问号。 */
    static HBox statusIconValue(String status) {
        if (ProviderAdminService.MODEL_STATUS_VALID.equalsIgnoreCase(status)) {
            return iconTextValue("\uE73E", "status-ok", I18n.t("settings.model.status.valid"));
        }
        if (ProviderAdminService.MODEL_STATUS_INVALID.equalsIgnoreCase(status)) {
            return iconTextValue("\uE711", "status-bad", I18n.t("settings.model.status.invalid"));
        }
        return iconTextValue("\uE897", "status-unknown", I18n.t("settings.model.status.unknown"));
    }

    /** 多模态行：支持=蓝图片、不支持=红图片、未测试=灰图片。 */
    static HBox visionIconValue(Integer supportsVision) {
        if (supportsVision == null) {
            return iconTextValue("\uE8B9", "status-unknown", I18n.t("settings.model.vision.unknown"));
        }
        if (supportsVision == 1) {
            return iconTextValue("\uE8B9", "status-vision", I18n.t("settings.model.vision.supported"));
        }
        return iconTextValue("\uE8B9", "status-bad", I18n.t("settings.model.vision.unsupported"));
    }

    /**
     * 卡片首行：左侧名称 + 状态徽标，右侧编辑/删除按钮。
     * <p>宽度不够时 HBox 会把所有可压缩的子节点一起往最小宽度压，所以默认情况下名称过长会
     * 连带把徽标与按钮的文字挤成省略号。这里只让名称可压缩（过长则省略号截断，并挂 tooltip
     * 便于看全名），其余节点的最小宽度钉在首选宽度上，保证操作入口始终完整可见。</p>
     */
    static HBox cardHeadRow(Label name, Label badge, Button... actions) {
        name.setMinWidth(0);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setTooltip(new Tooltip(name.getText()));
        badge.setMinWidth(Region.USE_PREF_SIZE);

        HBox titleRow = new HBox(10, name, badge);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.setMaxWidth(Double.MAX_VALUE);

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        HBox head = new HBox();
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(titleRow, sp);
        for (Button action : actions) {
            action.setMinWidth(Region.USE_PREF_SIZE);
            head.getChildren().add(action);
        }
        return head;
    }

    /** 表单网格：统一 {@code form-card} 样式与行/列间距，供提供商/模型表单复用。 */
    static GridPane grid() {
        GridPane g = new GridPane();
        g.getStyleClass().add("form-card");
        g.setHgap(14);
        g.setVgap(10);
        return g;
    }
}
