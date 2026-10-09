package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.core.chat.ToolCallRecordCodec;
import com.gettyio.gim.agent.ui.I18n;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 助手消息气泡句柄：流式期间以单个纯文本 {@link Label} 承载增量（避免半截代码栅栏在每帧重渲时闪烁），
 * 流结束（或历史重放）时调用 {@link #renderMarkdown(String)} 用 {@link MarkdownRenderer} 整体重渲一次。
 * <p>消息正文下方挂一行底部行（左对齐）：悬停时整体浮现——左侧 {@link MessageActions} 操作条
 * （复制 / 重新生成，含消息时间）与右侧「由 AI 生成」标注同处一条水平线居中；右键菜单提供删除。
 * 原文与主键由本类持有、经 {@code Supplier} 现取，故实时气泡收尾后仍能被正确复制与删除。</p>
 */
public final class AssistantBubble {

    private final HBox row;
    private final StackPane host;
    private final VBox body;
    /** 正文容器（流式 live 标签 / Markdown 节点 / 工具卡片），与下挂的操作条分离，避免渲染时被连带清除。 */
    private final VBox contentBox;
    private final Label live;
    private final MessageActions actions;
    /** 「思考中」占位文案的脉动动画：正文首帧到达或收尾渲染时停止。 */
    private final Timeline thinkingPulse;
    /** 「思考中」计时动画：每秒递增已耗时秒数并刷新占位文案，让用户感知等待。 */
    private final Timeline thinkingTimer;
    /** 流式期间思考卡的实时句柄（首个 THINKING_DELTA 到达时创建）；收尾重放时随整卡替换。 */
    private ThinkingCard.Live thinkingLive;

    private String rawSource = "";
    private long conversationId;
    /** 是否仍显示「思考中」占位：首帧正文/工具提示或收尾渲染时置 false 并清除占位。 */
    private boolean thinking;
    /** 已思考秒数，计时动画每秒 +1，0 秒时仅显基础文案。 */
    private int thinkingSeconds;

    AssistantBubble(double maxWidth, MessageActionListener listener) {
        live = new Label();
        live.setWrapText(true);
        live.getStyleClass().add("bubble-text");
        contentBox = new VBox(live);
        body = new VBox(2);
        body.getStyleClass().add("bubble-assistant");
        body.setMaxWidth(maxWidth);
        body.getChildren().add(contentBox);
        actions = new MessageActions(() -> rawSource, () -> conversationId, listener);
        // 底部行：操作条与「由 AI 生成」标注默认隐去但仍占位，悬停气泡时整行浮现；
        // 整行常驻占位，位置确定不受内容高矮影响
        Label aiLabel = new Label(I18n.t("chat.msg.aiGenerated"));
        aiLabel.getStyleClass().add("msg-ai-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(actions.node(), spacer, aiLabel);
        footer.setAlignment(Pos.CENTER_LEFT);
        // 底部行整体与正文留一段上间距，避免贴住正文最后一行
        VBox.setMargin(footer, new Insets(6, 0, 0, 0));
        body.getChildren().add(footer);
        host = new StackPane(body);
        host.getStyleClass().add("bubble-host");
        row = new HBox(host);
        row.getStyleClass().add("bubble-row");
        row.setAlignment(Pos.TOP_LEFT);
        actions.installContextMenu(row);
        thinkingPulse = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(live.opacityProperty(), 0.4)),
                new KeyFrame(Duration.millis(900), new KeyValue(live.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(1800), new KeyValue(live.opacityProperty(), 0.4)));
        thinkingPulse.setCycleCount(Timeline.INDEFINITE);
        thinkingTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            thinkingSeconds++;
            refreshThinkingText();
        }));
        thinkingTimer.setCycleCount(Timeline.INDEFINITE);
        // 兜底：气泡脱离场景（收尾重放替换、切会话、关窗）时停掉脉动与计时动画，避免无限动画在游离节点上空转泄漏
        row.sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) {
                thinkingPulse.stop();
                thinkingTimer.stop();
            }
        });
        showThinking();
    }

    /** 显示「思考中」脉动占位：气泡刚建立、首帧正文未到前给予可见反馈，避免被误认为无响应。 */
    private void showThinking() {
        thinking = true;
        thinkingSeconds = 0;
        live.getStyleClass().add("bubble-thinking");
        live.setOpacity(0.4);
        refreshThinkingText();
        thinkingPulse.playFromStart();
        thinkingTimer.playFromStart();
    }

    /** 刷新「思考中」占位文案：0 秒仅显基础文案，1 秒起追加已耗时秒数。 */
    private void refreshThinkingText() {
        String base = I18n.t("chat.thinking");
        live.setText(thinkingSeconds > 0
                ? base + I18n.t("chat.thinking.elapsed", thinkingSeconds)
                : base);
    }

    /** 结束「思考中」占位：停止脉动与计时、移除占位文案与样式，为首帧正文让路；非思考态则不动。 */
    private void clearThinking() {
        if (!thinking) {
            return;
        }
        thinking = false;
        thinkingPulse.stop();
        thinkingTimer.stop();
        live.setOpacity(1.0);
        live.getStyleClass().remove("bubble-thinking");
        live.setText("");
    }

    /** 气泡根节点（供容器挂载）。 */
    public Node node() {
        return row;
    }

    /** 流式追加：把本帧纯文本拼到当前实时文本之后（原文同步累加，供复制）。 */
    public void appendPlain(String text) {
        clearThinking();
        rawSource += text;
        live.setText(rawSource);
    }

    /** 整体渲染：清空气泡内容并用 Markdown 渲染结果替换；空内容回落为一条空文本行。 */
    public void renderMarkdown(String markdown) {
        renderContent(markdown, null, List.of());
    }

    /**
     * 整体渲染：先按发生顺序挂上思考折叠卡（「已思考 · 摘要」，点击展开全文）与工具折叠卡，再渲染 Markdown 正文。
     * <p>历史重放与实时收尾共用；{@code thinking} 为空白时无思考卡，{@code tools} 为空时无工具卡。</p>
     */
    public void renderContent(String markdown, String thinking, List<ToolCallRecordCodec.ToolCallRecord> tools) {
        clearThinking();
        rawSource = markdown == null ? "" : markdown;
        List<Node> rendered = MarkdownRenderer.render(rawSource);
        boolean hasTools = tools != null && !tools.isEmpty();
        Node thinkingCard = ThinkingCard.build(thinking);
        contentBox.getChildren().clear();
        if (thinkingCard != null) {
            // 思考先于工具与正文发生，故置于最上；与下方元素留一段空隙
            VBox.setMargin(thinkingCard, new Insets(0, 0, 8, 0));
            contentBox.getChildren().add(thinkingCard);
        }
        if (hasTools) {
            // 一轮内的多次工具调用合并为一张「执行工具 N 次」折叠卡片（组卡内按行展开输入/响应），与下方正文留一段空隙
            Node toolsCard = ToolCallGroupCard.build(tools);
            VBox.setMargin(toolsCard, new Insets(0, 0, 8, 0));
            contentBox.getChildren().add(toolsCard);
        }
        if (rendered.isEmpty() && !hasTools && thinkingCard == null) {
            live.setText(rawSource);
            contentBox.getChildren().add(live);
        } else if (!rendered.isEmpty()) {
            contentBox.getChildren().addAll(rendered);
        }
    }

    /**
     * 流式追加思考增量：首个增量到达时以思考卡替换「思考中」占位（标题「思考中…」，正文实时增长），
     * 后续增量持续追加；收尾（或历史重放）时整卡由 {@link #renderContent} 的完成态替换。
     */
    public void appendThinking(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (thinkingLive == null) {
            clearThinking();
            thinkingLive = ThinkingCard.live();
            contentBox.getChildren().add(0, thinkingLive.node());
        }
        thinkingLive.append(delta);
    }

    /** 关联落盘主键（历史重放或实时收尾后回填；0 表示尚未持久化，删除/编辑不可用）。 */
    public void setConversationId(long conversationId) {
        this.conversationId = conversationId;
    }

    /** 回填消息时间（悬停操作条内显示）；实时流未落盘阶段不调用即保持隐藏。 */
    public void setTime(LocalDateTime createdAt) {
        actions.setTime(createdAt);
    }

    MessageActions actions() {
        return actions;
    }
}
