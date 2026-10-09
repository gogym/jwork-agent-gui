package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.core.chat.TokenUsage;
import com.gettyio.gim.agent.ui.I18n;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 多模型对比卡片：同一问题并行发给多个模型，逐列流式展示（一列一个模型）。
 * <p>挂载在消息区用户气泡之后；每列独立订阅一条 {@code AgentGateway.stream} 流。流式期间
 * 以单个纯文本 {@link Label} 承载增量（与助手气泡同策略，避免半截 Markdown 每帧重渲闪烁），
 * 终结时整列以 {@link MarkdownRenderer} 重渲并定格状态徽章与底部耗时/用量。</p>
 * <p>对比是试验性动作：不落库、不进历史（切换会话即消失），每列走临时会话 id，与真实会话互不影响。</p>
 */
public class CompareCard {

    /** 对比目标：一个模型（由工具条多选勾选产生）。 */
    public record Target(String providerCode, String modelCode, String label) {
    }

    /** 列内容区固定高度：各列等高便于横向比对 */
    private static final double COL_BODY_HEIGHT = 300;

    private final HBox row = new HBox(10);
    private final List<Column> columns = new ArrayList<>();

    public CompareCard(List<Target> targets) {
        row.getStyleClass().add("compare-card");
        row.setMaxWidth(Double.MAX_VALUE);
        for (Target t : targets) {
            Column col = new Column(t);
            columns.add(col);
            HBox.setHgrow(col.node, Priority.ALWAYS);
            row.getChildren().add(col.node);
        }
    }

    /** 卡片根节点（供消息区挂载）。 */
    public Node node() {
        return row;
    }

    /** 第 {@code index} 列句柄（与构造入参 targets 同序）。 */
    public Column column(int index) {
        return columns.get(index);
    }

    /**
     * 单列句柄：头部（模型名 + 状态徽章）+ 流式正文区 + 底部（耗时 · tokens）。
     * <p>所有方法都须在 FX 线程调用（订阅侧统一经 {@code Platform.runLater} 进来）。</p>
     */
    public static final class Column {

        private final VBox node = new VBox(6);
        private final Label live = new Label();
        private final VBox content = new VBox();
        private final ScrollPane scroller = new ScrollPane(content);
        private final Label badge = new Label();
        private final Label foot = new Label();
        private final StringBuilder raw = new StringBuilder();
        private final long startMillis = System.currentTimeMillis();
        private TokenUsage usage;
        /** 终结标志：完成/出错/停止只认第一次，防三路终态竞态重复定格。 */
        private boolean settled;

        private Column(Target target) {
            node.getStyleClass().add("compare-col");
            node.setMaxWidth(Double.MAX_VALUE);

            Label title = new Label(target.label());
            title.getStyleClass().add("compare-col-title");
            title.setMaxWidth(Double.MAX_VALUE);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            badge.getStyleClass().addAll("badge", "badge-blue");
            badge.setText(I18n.t("compare.running"));
            HBox head = new HBox(8, title, spacer, badge);
            head.setAlignment(Pos.CENTER_LEFT);
            head.getStyleClass().add("compare-col-head");
            head.setMinWidth(0);

            live.setWrapText(true);
            live.getStyleClass().add("compare-col-text");
            content.getChildren().add(live);
            content.setMaxWidth(Double.MAX_VALUE);
            scroller.setFitToWidth(true);
            scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroller.setPrefHeight(COL_BODY_HEIGHT);
            scroller.setMinHeight(COL_BODY_HEIGHT);
            scroller.setMaxHeight(COL_BODY_HEIGHT);
            scroller.getStyleClass().add("compare-col-scroll");

            foot.getStyleClass().add("card-meta");
            node.getChildren().addAll(head, scroller, foot);
        }

        /** 追加正文增量（明文直拼，收尾时整体重渲）。 */
        public void appendDelta(String text) {
            if (settled || text == null || text.isEmpty()) {
                return;
            }
            raw.append(text);
            live.setText(raw.toString());
        }

        /** 工具调用提示行（对比列只读模式，通常为读类工具的短提示）。 */
        public void noteTool(String toolName) {
            if (settled || toolName == null) {
                return;
            }
            raw.append('\n').append(I18n.t("chat.toolCall", toolName));
        }

        /** 记录本轮累计用量（USAGE 事件），收尾时定格到底部行。 */
        public void setUsage(TokenUsage usage) {
            if (usage != null && !usage.isEmpty()) {
                this.usage = usage;
            }
        }

        /** 正常完成：Markdown 重渲正文，徽章转绿，底部定格耗时与用量。 */
        public void complete() {
            if (settled) {
                return;
            }
            settled = true;
            renderFinal();
            style(badge, "badge-green", I18n.t("compare.done"));
            foot.setText(footText());
        }

        /** 出错终结：保留半截正文并追加失败提示，徽章转红。 */
        public void fail(String message) {
            if (settled) {
                return;
            }
            settled = true;
            String msg = message == null || message.isBlank() ? I18n.t("common.error.unknown") : message;
            raw.append('\n').append(I18n.t("chat.failed", msg));
            renderFinal();
            style(badge, "badge-red", I18n.t("compare.failed"));
            foot.setText(footText());
        }

        /** 用户停止：保留半截正文，徽章转灰。 */
        public void stopped() {
            if (settled) {
                return;
            }
            settled = true;
            renderFinal();
            style(badge, "badge-gray", I18n.t("compare.stopped"));
            foot.setText(footText());
        }

        /** 正文收尾渲染：清掉实时明文改用 Markdown；空输出回落一行占位文案。 */
        private void renderFinal() {
            String markdown = raw.toString();
            if (markdown.isBlank()) {
                content.getChildren().setAll(placeholder());
                return;
            }
            List<Node> rendered = MarkdownRenderer.render(markdown);
            if (rendered.isEmpty()) {
                content.getChildren().setAll(placeholder());
            } else {
                content.getChildren().setAll(rendered);
            }
        }

        private Label placeholder() {
            Label empty = new Label(I18n.t("compare.col.empty"));
            empty.getStyleClass().add("card-meta");
            return empty;
        }

        /** 底部行：耗时（秒，一位小数） · 总 tokens（无用量回报时只显耗时）。 */
        private String footText() {
            double seconds = Math.max(0, System.currentTimeMillis() - startMillis) / 1000.0;
            String time = String.format(Locale.ROOT, "%.1f", seconds);
            if (usage == null) {
                return I18n.t("compare.foot.time", time);
            }
            long total = usage.inputTokens() + usage.outputTokens();
            return I18n.t("compare.foot", time, String.valueOf(total));
        }

        /** 重置徽章样式类（保留 badge 基类，换掉颜色与文案）。 */
        private static void style(Label badge, String colorClass, String text) {
            badge.getStyleClass().removeAll("badge-blue", "badge-green", "badge-gray", "badge-red");
            badge.getStyleClass().add(colorClass);
            badge.setText(text);
        }
    }
}
