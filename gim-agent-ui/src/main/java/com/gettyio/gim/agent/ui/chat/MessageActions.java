package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 消息气泡的悬停操作条 + 右键菜单。
 * <p>操作条含「复制」常驻，以及默认隐藏的「重新生成」（仅最后一条助手）与「编辑重发」（仅最后一条用户）
 * 两个按需按钮；「删除」走右键菜单（对已落盘、有主键的消息生效）。原文与主键都通过 {@link Supplier}/
 * {@link LongSupplier} 现取，使实时流式气泡在收尾后也能读到最新文本。</p>
 */
final class MessageActions {

    private final HBox bar;
    private final Button regenerateButton;
    private final Button editButton;
    private final Supplier<String> textSupplier;
    private final LongSupplier idSupplier;
    private final MessageActionListener listener;

    MessageActions(Supplier<String> textSupplier, LongSupplier idSupplier, MessageActionListener listener) {
        this.textSupplier = textSupplier;
        this.idSupplier = idSupplier;
        this.listener = listener;

        bar = new HBox(2);
        bar.getStyleClass().add("msg-actions");

        Button copy = iconButton("\uE8C8", "chat.msg.copy");
        copy.setOnAction(e -> {
            String t = textSupplier.get();
            if (t != null && !t.isEmpty()) {
                listener.onCopy(t);
            }
        });

        regenerateButton = iconButton("\uE72C", "chat.msg.regenerate");
        regenerateButton.setOnAction(e -> listener.onRegenerate());
        regenerateButton.setVisible(false);
        regenerateButton.setManaged(false);

        editButton = iconButton("\uE70F", "chat.msg.editResend");
        editButton.setOnAction(e -> {
            long id = idSupplier.getAsLong();
            if (id > 0) {
                listener.onEditResend(id, textSupplier.get());
            }
        });
        editButton.setVisible(false);
        editButton.setManaged(false);

        bar.getChildren().addAll(copy, editButton, regenerateButton);
    }

    /** 操作条节点（供气泡以叠层方式挂到右上角）。 */
    Node node() {
        return bar;
    }

    void setRegenerateShown(boolean shown) {
        regenerateButton.setVisible(shown);
        regenerateButton.setManaged(shown);
    }

    void setEditShown(boolean shown) {
        editButton.setVisible(shown);
        editButton.setManaged(shown);
    }

    /** 在气泡宿主节点上装右键「删除」菜单（无主键时菜单项禁用）。 */
    void installContextMenu(Node host) {
        MenuItem delete = new MenuItem(I18n.t("chat.msg.delete"), UiTheme.icon("\uE74D"));
        ContextMenu menu = new ContextMenu(delete);
        delete.setOnAction(e -> {
            long id = idSupplier.getAsLong();
            if (id > 0) {
                listener.onDelete(id);
            }
        });
        host.setOnContextMenuRequested(e -> {
            delete.setDisable(idSupplier.getAsLong() <= 0);
            menu.show(host, e.getScreenX(), e.getScreenY());
        });
    }

    private static Button iconButton(String glyph, String tooltipKey) {
        Button b = new Button();
        b.setGraphic(UiTheme.icon(glyph));
        b.getStyleClass().add("msg-action-btn");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(I18n.t(tooltipKey)));
        return b;
    }
}
