package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 消息气泡的悬停操作条 + 右键菜单。
 * <p>操作条含「复制」常驻，以及默认隐藏的「朗读」（仅已完成的助手消息）、「重新生成」（仅最后一条助手）
 * 与「编辑重发」（仅最后一条用户）三个按需按钮，末尾经 {@link #setTime} 可显示消息时间；「从此消息分支」
 * 「删除」走右键菜单（对已落盘、有主键的消息生效）。原文与主键都通过 {@link Supplier}/{@link LongSupplier}
 * 现取，使实时流式气泡在收尾后也能读到最新文本。</p>
 */
final class MessageActions {

    /** 操作条时间显示格式（分钟精度即可）。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HBox bar;
    private final Button speakButton;
    private final Button regenerateButton;
    private final Button editButton;
    private final Label timeLabel;
    private final Supplier<String> textSupplier;
    private final LongSupplier idSupplier;
    private final MessageActionListener listener;
    /** 本条是否正在朗读：控制按钮在播放/停止两态间切换。 */
    private boolean speaking;

    MessageActions(Supplier<String> textSupplier, LongSupplier idSupplier, MessageActionListener listener) {
        this.textSupplier = textSupplier;
        this.idSupplier = idSupplier;
        this.listener = listener;

        bar = new HBox(5);
        // 按钮与时间高矮不同：垂直居中使两者与气泡右侧标注同处一条水平线
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("msg-actions");

        // 时间 Label（位于按钮之后）：默认隐藏，由 setTime 依据落盘时间显隐
        timeLabel = new Label();
        timeLabel.getStyleClass().add("msg-time");
        timeLabel.setManaged(false);
        timeLabel.setVisible(false);

        Button copy = iconButton("\uE8C8", "chat.msg.copy");
        copy.setOnAction(e -> {
            String t = textSupplier.get();
            if (t != null && !t.isEmpty()) {
                listener.onCopy(t);
            }
        });

        speakButton = iconButton("\uE768", "chat.msg.speak");
        speakButton.setOnAction(e -> {
            if (speaking) {
                listener.onSpeakStop();
                return;
            }
            String t = textSupplier.get();
            if (t == null || t.isBlank()) {
                return;
            }
            speaking = true;
            refreshSpeakButton();
            listener.onSpeak(t, () -> {
                speaking = false;
                refreshSpeakButton();
            });
        });
        speakButton.setVisible(false);
        speakButton.setManaged(false);

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

        bar.getChildren().addAll(copy, speakButton, editButton, regenerateButton, timeLabel);
    }

    /** 操作条节点（供气泡以叠层方式挂到左下角）。 */
    Node node() {
        return bar;
    }

    /** 设置消息时间（悬停操作条内显示，位于按钮之后）；传 {@code null} 隐藏（如实时流未落盘阶段）。 */
    void setTime(LocalDateTime createdAt) {
        String text = formatTime(createdAt);
        boolean show = !text.isEmpty();
        timeLabel.setText(text);
        timeLabel.setManaged(show);
        timeLabel.setVisible(show);
    }

    /** 时间显示格式：{@code yyyy-MM-dd HH:mm}；{@code null} 返回空串。 */
    static String formatTime(LocalDateTime createdAt) {
        return createdAt == null ? "" : TIME_FORMAT.format(createdAt);
    }

    /** 显示/隐藏「朗读」按钮（仅已完成的助手气泡显示）。 */
    void setSpeakShown(boolean shown) {
        speakButton.setVisible(shown);
        speakButton.setManaged(shown);
    }

    /** 刷新朗读按钮的两态（图标 play/stop 与提示文案）。 */
    private void refreshSpeakButton() {
        if (speakButton.getGraphic() instanceof Label icon) {
            icon.setText(speaking ? "\uE71A" : "\uE768");
        }
        speakButton.setTooltip(new Tooltip(I18n.t(speaking ? "chat.msg.speakStop" : "chat.msg.speak")));
    }

    void setRegenerateShown(boolean shown) {
        regenerateButton.setVisible(shown);
        regenerateButton.setManaged(shown);
    }

    void setEditShown(boolean shown) {
        editButton.setVisible(shown);
        editButton.setManaged(shown);
    }

    /** 在气泡宿主节点上装右键菜单：「从此消息分支」+「删除」（无主键时菜单项禁用）。 */
    void installContextMenu(Node host) {
        MenuItem fork = new MenuItem(I18n.t("chat.msg.fork"), UiTheme.icon("\uEBC9"));
        fork.setOnAction(e -> {
            long id = idSupplier.getAsLong();
            if (id > 0) {
                listener.onFork(id);
            }
        });
        MenuItem delete = new MenuItem(I18n.t("chat.msg.delete"), UiTheme.icon("\uE74D"));
        delete.setOnAction(e -> {
            long id = idSupplier.getAsLong();
            if (id > 0) {
                listener.onDelete(id);
            }
        });
        ContextMenu menu = new ContextMenu(fork, delete);
        host.setOnContextMenuRequested(e -> {
            boolean unsaved = idSupplier.getAsLong() <= 0;
            fork.setDisable(unsaved);
            delete.setDisable(unsaved);
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
