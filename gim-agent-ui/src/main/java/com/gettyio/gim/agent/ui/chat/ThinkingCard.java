package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.ui.I18n;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 思考过程卡片：把模型一轮内的思考过程展示在助手正文上方。
 * <p>两种形态共用同一视觉结构（图标 + 标题 + 浅底内容块）：
 * <ul>
 *   <li>{@link #build(String)} 完成态——「已思考 · 摘要」一行，点击展开/收起，展开后按 Markdown 渲染全文；</li>
 *   <li>{@link #live()} 流式态——「思考中…」标题，正文随增量实时增长（纯文本，收尾重放时由完成态替换）。</li>
 * </ul>
 */
final class ThinkingCard {

    /** 标题行图标：圆圈勾（Segoe Fluent Icons 码点，与工具卡成功态同码点；字体族来自全局 .icon 类）。 */
    private static final String ICON_THOUGHT = "\uE930";

    /** 摘要最大字符数：与工具卡行摘要同口径（超长先截断再交给 Label 省略，避免拖慢布局）。 */
    private static final int SUMMARY_LIMIT = 120;

    private ThinkingCard() {
    }

    /** 构建完成态折叠卡片；{@code thinking} 为空白返回 null（该轮无思考，不显示）。默认收起，只露一行摘要。 */
    static Node build(String thinking) {
        if (thinking == null || thinking.isBlank()) {
            return null;
        }
        Label title = new Label(I18n.t("chat.thinking.done"));
        title.getStyleClass().add("thinking-title");
        // 锁定标题的完整宽度：行内空间不足时优先压缩摘要（否则长摘要把标题挤成省略号）
        title.setMinWidth(Region.USE_PREF_SIZE);

        Label sum = new Label("\u00B7 " + summary(thinking));
        sum.getStyleClass().add("thinking-summary");
        // 摘要吸收全部剩余宽：宽裕时撑满、不足时先被压缩并以省略号收尾（min=0 允许收缩）
        sum.setMinWidth(0);
        sum.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(sum, Priority.ALWAYS);

        HBox header = new HBox(6, icon(), title, sum);
        header.getStyleClass().add("thinking-header");
        header.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(4);
        body.getStyleClass().add("thinking-body");
        body.getChildren().addAll(MarkdownRenderer.render(thinking));
        body.setVisible(false);
        body.setManaged(false);

        header.setOnMouseClicked(e -> {
            boolean show = !body.isVisible();
            body.setVisible(show);
            body.setManaged(show);
        });

        VBox card = new VBox(2, header, body);
        card.getStyleClass().add("thinking-card");
        return card;
    }

    /** 流式态句柄：标题「思考中…」+ 实时增长的纯文本内容块，默认展开；收尾重放时由完成态整卡替换。 */
    static Live live() {
        Label title = new Label(I18n.t("chat.thinking"));
        title.getStyleClass().add("thinking-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(6, icon(), title, spacer);
        header.getStyleClass().add("thinking-header");
        header.setAlignment(Pos.CENTER_LEFT);

        Label body = new Label();
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        body.getStyleClass().add("thinking-text");
        VBox box = new VBox(body);
        box.getStyleClass().add("thinking-body");

        VBox card = new VBox(2, header, box);
        card.getStyleClass().add("thinking-card");
        return new Live(card, body);
    }

    /** 流式态思考卡：{@link #append(String)} 逐段追加文本（原文保留换行，随宽度自动折行）。 */
    static final class Live {

        private final VBox card;
        private final Label body;
        private final StringBuilder raw = new StringBuilder();

        private Live(VBox card, Label body) {
            this.card = card;
            this.body = body;
        }

        Node node() {
            return card;
        }

        void append(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            raw.append(delta);
            body.setText(raw.toString());
        }
    }

    /** 摘要：思考文本压平空白后按上限截断（与工具卡行摘要同规则）。 */
    private static String summary(String thinking) {
        String s = thinking.replaceAll("\\s+", " ").trim();
        return s.length() > SUMMARY_LIMIT ? s.substring(0, SUMMARY_LIMIT) + "\u2026" : s;
    }

    /** 图标 Label：全局 .icon 提供 Fluent 字体族，附加类控制尺寸/颜色。 */
    private static Label icon() {
        Label l = new Label(ICON_THOUGHT);
        l.getStyleClass().addAll("icon", "thinking-icon");
        return l;
    }
}
