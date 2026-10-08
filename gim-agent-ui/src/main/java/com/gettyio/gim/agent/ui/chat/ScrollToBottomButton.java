package com.gettyio.gim.agent.ui.chat;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.util.Duration;

/**
 * 消息区「回到底部」悬浮按钮：向上滚动离开最新消息时显现，点击后平滑滚回底部。
 * <p>显隐由「内容超出视口」+「未贴底」两个条件驱动，订阅滚动位置、视口尺寸与内容高度自动刷新；
 * 点击后用短动画把 {@code vvalue} 推到 1.0，结束时补一次齐底兜底（抵消流式换行的延迟结算）。</p>
 */
public final class ScrollToBottomButton {

    /** 贴底判定容差：vvalue 达此阈值视为已在底部 */
    private static final double AT_BOTTOM_EPS = 1e-3;
    /** 滚动动画时长 */
    private static final Duration SCROLL_DURATION = Duration.millis(260);

    private final ScrollPane scrollPane;
    private final Button button;

    public ScrollToBottomButton(ScrollPane scrollPane, Button button) {
        this.scrollPane = scrollPane;
        this.button = button;
        button.setOnAction(e -> scrollToBottom());
        scrollPane.vvalueProperty().addListener((obs, was, now) -> update());
        scrollPane.viewportBoundsProperty().addListener((obs, was, now) -> update());
        Node content = scrollPane.getContent();
        if (content != null) {
            content.layoutBoundsProperty().addListener((obs, was, now) -> update());
        }
        update();
    }

    /** 依「可滚动 + 未贴底」刷新按钮显隐（不占位，避免影响消息区布局）。 */
    private void update() {
        Node content = scrollPane.getContent();
        double contentH = content == null ? 0 : content.getLayoutBounds().getHeight();
        double viewH = scrollPane.getViewportBounds().getHeight();
        boolean show = contentH > viewH + 1 && scrollPane.getVvalue() < 1 - AT_BOTTOM_EPS;
        button.setVisible(show);
        button.setManaged(show);
    }

    /** 平滑滚到底部；已在底部则不动。 */
    private void scrollToBottom() {
        scrollPane.applyCss();
        scrollPane.layout();
        double from = scrollPane.getVvalue();
        if (from >= 1 - AT_BOTTOM_EPS) {
            return;
        }
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(scrollPane.vvalueProperty(), from)),
                new KeyFrame(SCROLL_DURATION, new KeyValue(scrollPane.vvalueProperty(), 1.0, Interpolator.EASE_OUT)));
        timeline.setOnFinished(e -> {
            scrollPane.layout();
            scrollPane.setVvalue(1.0);
            // 兜底：流式换行/高度结算常延后一帧，届时再齐一次底
            Platform.runLater(() -> {
                scrollPane.layout();
                scrollPane.setVvalue(1.0);
            });
        });
        timeline.play();
    }
}
