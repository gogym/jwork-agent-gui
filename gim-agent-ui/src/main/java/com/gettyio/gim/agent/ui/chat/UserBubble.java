package com.gettyio.gim.agent.ui.chat;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * 用户消息气泡：纯文本呈现（不做 Markdown 解析），可附图片缩略图。
 * <p>气泡左上角叠 {@link MessageActions} 悬停操作条（复制 / 编辑重发），右键菜单删除。原文与主键
 * 由本类持有、经 {@code Supplier} 现取，历史重放回填主键后即可删除。</p>
 */
public final class UserBubble {

    private final HBox row;
    private final MessageActions actions;
    private final String rawText;
    private long conversationId;

    UserBubble(String text, List<ImageView> thumbs, List<Node> fileRows, double maxWidth, MessageActionListener listener) {
        this.rawText = text == null ? "" : text;
        VBox col = new VBox(6);
        if (!rawText.isBlank()) {
            Label label = new Label(rawText);
            label.setWrapText(true);
            label.getStyleClass().add("bubble-user");
            label.setMaxWidth(maxWidth);
            col.getChildren().add(label);
        }
        if (fileRows != null && !fileRows.isEmpty()) {
            VBox filesBox = new VBox(4);
            filesBox.getStyleClass().add("bubble-files");
            filesBox.getChildren().addAll(fileRows);
            col.getChildren().add(filesBox);
        }
        if (thumbs != null && !thumbs.isEmpty()) {
            HBox imageRow = new HBox(6);
            imageRow.getStyleClass().add("bubble-images");
            imageRow.getChildren().addAll(thumbs);
            col.getChildren().add(imageRow);
        }
        actions = new MessageActions(() -> rawText, () -> conversationId, listener);
        StackPane host = new StackPane(col, actions.node());
        host.getStyleClass().add("bubble-host");
        StackPane.setAlignment(actions.node(), Pos.TOP_RIGHT);
        StackPane.setMargin(actions.node(), new Insets(4, 6, 0, 0));
        row = new HBox(host);
        row.getStyleClass().add("bubble-row");
        row.setAlignment(Pos.TOP_RIGHT);
        actions.installContextMenu(row);
    }

    /** 气泡根节点（供容器挂载）。 */
    public Node node() {
        return row;
    }

    /** 关联落盘主键（0 表示尚未持久化）。 */
    public void setConversationId(long conversationId) {
        this.conversationId = conversationId;
    }

    MessageActions actions() {
        return actions;
    }
}
