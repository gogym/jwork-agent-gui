package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.core.chat.ToolCallRecordCodec;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * 助手消息气泡句柄：流式期间以单个纯文本 {@link Label} 承载增量（避免半截代码栅栏在每帧重渲时闪烁），
 * 流结束（或历史重放）时调用 {@link #renderMarkdown(String)} 用 {@link MarkdownRenderer} 整体重渲一次。
 * <p>气泡右上角叠一层 {@link MessageActions} 悬停操作条（复制 / 重新生成），右键菜单提供删除。原文与
 * 主键由本类持有、经 {@code Supplier} 现取，故实时气泡收尾后仍能被正确复制与删除。</p>
 */
public final class AssistantBubble {

    private final HBox row;
    private final StackPane host;
    private final VBox body;
    private final Label live;
    private final MessageActions actions;

    private String rawSource = "";
    private long conversationId;

    AssistantBubble(double maxWidth, MessageActionListener listener) {
        live = new Label();
        live.setWrapText(true);
        live.getStyleClass().add("bubble-text");
        body = new VBox(live);
        body.getStyleClass().add("bubble-assistant");
        body.setMaxWidth(maxWidth);
        actions = new MessageActions(() -> rawSource, () -> conversationId, listener);
        host = new StackPane(body, actions.node());
        host.getStyleClass().add("bubble-host");
        StackPane.setAlignment(actions.node(), Pos.TOP_RIGHT);
        StackPane.setMargin(actions.node(), new Insets(4, 6, 0, 0));
        row = new HBox(host);
        row.getStyleClass().add("bubble-row");
        row.setAlignment(Pos.TOP_LEFT);
        actions.installContextMenu(row);
    }

    /** 气泡根节点（供容器挂载）。 */
    public Node node() {
        return row;
    }

    /** 流式追加：把本帧纯文本拼到当前实时文本之后（原文同步累加，供复制）。 */
    public void appendPlain(String text) {
        rawSource += text;
        live.setText(rawSource);
    }

    /** 整体渲染：清空气泡内容并用 Markdown 渲染结果替换；空内容回落为一条空文本行。 */
    public void renderMarkdown(String markdown) {
        renderContent(markdown, List.of());
    }

    /**
     * 整体渲染：先按发生顺序挂上工具折叠卡片，再渲染 Markdown 正文。
     * <p>历史重放与实时收尾共用；{@code tools} 为空时等价于纯 {@link #renderMarkdown(String)}。</p>
     */
    public void renderContent(String markdown, List<ToolCallRecordCodec.ToolCallRecord> tools) {
        rawSource = markdown == null ? "" : markdown;
        List<Node> rendered = MarkdownRenderer.render(rawSource);
        boolean hasTools = tools != null && !tools.isEmpty();
        body.getChildren().clear();
        if (hasTools) {
            for (ToolCallRecordCodec.ToolCallRecord r : tools) {
                body.getChildren().add(ToolCallCard.build(r));
            }
        }
        if (rendered.isEmpty() && !hasTools) {
            live.setText(rawSource);
            body.getChildren().add(live);
        } else if (!rendered.isEmpty()) {
            body.getChildren().addAll(rendered);
        }
    }

    /** 关联落盘主键（历史重放或实时收尾后回填；0 表示尚未持久化，删除/编辑不可用）。 */
    public void setConversationId(long conversationId) {
        this.conversationId = conversationId;
    }

    MessageActions actions() {
        return actions;
    }
}
