package com.gettyio.gim.agent.ui.chat;

import javafx.animation.PauseTransition;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.scene.robot.Robot;
import javafx.stage.Popup;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * 上下文占用条的悬停浮层：显示「已用/上限」明细，并提供「压缩当前会话」按钮。
 * <p>挂到占用条区域后鼠标悬停即自动弹出；移出触发节点或浮层后延迟一小段再隐藏，
 * 留出从条上滑入浮层的过渡时间。浮层用原始 {@link Popup} 承载：弹出时不抢键盘焦点
 * （输入框光标与草稿不受影响），显示时自动继承所属窗口的样式表（明暗主题随动）。</p>
 */
public final class ContextPopover {

    /** 悬停桥接时长：移出后延迟隐藏，覆盖「条 → 浮层」之间的移动。 */
    private static final Duration HIDE_DELAY = Duration.millis(300);
    /** 浮层与触发节点间的垂直间隙。 */
    private static final double GAP = 8;
    /** 浮层与所属窗口边缘的最小横向留白。 */
    private static final double EDGE_PADDING = 8;

    private final Popup popup = new Popup();
    private final VBox content;
    private final Label infoLabel = new Label();
    private final Button compactButton = new Button();
    private final Label statusLabel = new Label();
    private final PauseTransition hideDelay = new PauseTransition(HIDE_DELAY);

    private Node trigger;
    /** 光标位置采样器（懒加载）；采样不可用的环境下降级为纯事件驱动。 */
    private Robot robot;
    private Runnable onCompact;
    private Runnable onWillShow;

    public ContextPopover() {
        infoLabel.getStyleClass().add("context-popup-info");
        compactButton.getStyleClass().add("context-popup-action");
        compactButton.setMaxWidth(Double.MAX_VALUE);
        compactButton.setOnAction(e -> {
            if (onCompact != null) {
                onCompact.run();
            }
        });
        statusLabel.getStyleClass().add("context-popup-status");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(240);
        hideStatus();

        content = new VBox(infoLabel, compactButton, statusLabel);
        content.getStyleClass().add("context-popup");
        popup.getContent().add(content);
        popup.setAutoFix(true);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        // 以内容框右下角为锚点定位：show 时浮层整体位于触发节点上方（底边留隙），不遮挡元素本身
        popup.setAnchorLocation(PopupWindow.AnchorLocation.CONTENT_BOTTOM_RIGHT);

        hideDelay.setOnFinished(e -> hideIfPointerAway());
        // 鼠标滑入浮层取消隐藏；滑出时仅当光标确实不在「条 ∪ 浮层」内才启动隐藏计时
        content.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> hideDelay.stop());
        content.addEventHandler(MouseEvent.MOUSE_EXITED, e -> scheduleHideIfPointerAway());
    }

    /** 把悬停触发挂到指定节点（占用条区域整体）。 */
    public void attachTrigger(Node node) {
        if (node == null) {
            return;
        }
        trigger = node;
        node.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> {
            hideDelay.stop();
            showFor(node);
        });
        node.addEventHandler(MouseEvent.MOUSE_EXITED, e -> scheduleHideIfPointerAway());
    }

    /** 显示前的回调：用于把最新占用数值与按钮状态注入浮层后再弹出。 */
    public void setOnWillShow(Runnable onWillShow) {
        this.onWillShow = onWillShow;
    }

    /** 「压缩当前会话」点击回调。 */
    public void setOnCompact(Runnable onCompact) {
        this.onCompact = onCompact;
    }

    /** 首行明细文案（如「115k/128k 已用上下文」）。 */
    public void setInfo(String text) {
        infoLabel.setText(text == null ? "" : text);
    }

    /** 压缩按钮文案（空闲为「压缩当前会话」，进行中可改为「压缩中…」）。 */
    public void setCompactLabel(String text) {
        compactButton.setText(text == null ? "" : text);
    }

    /** 压缩按钮可用态（压缩进行中禁点）。 */
    public void setCompactEnabled(boolean enabled) {
        compactButton.setDisable(!enabled);
    }

    /** 回显一条状态（压缩完成/跳过/失败）；{@code text} 为空则隐藏该行，{@code error} 控制警示色。 */
    public void setStatus(String text, boolean error) {
        if (text == null || text.isBlank()) {
            hideStatus();
            return;
        }
        statusLabel.setText(text);
        statusLabel.getStyleClass().remove("context-popup-status-error");
        if (error) {
            statusLabel.getStyleClass().add("context-popup-status-error");
        }
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
    }

    private void hideStatus() {
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
    }

    /** 浮层是否正在显示。 */
    public boolean isShowing() {
        return popup.isShowing();
    }

    /** 收起浮层（触发的占用条已被隐藏或无窗口时由调用方使用）。 */
    public void hide() {
        hideDelay.stop();
        if (popup.isShowing()) {
            popup.hide();
        }
    }

    private void showFor(Node node) {
        if (popup.isShowing() || node.getScene() == null) {
            return;
        }
        hideDelay.stop();
        Window owner = node.getScene().getWindow();
        if (owner == null) {
            return;
        }
        Bounds bounds = node.localToScreen(node.getBoundsInLocal());
        if (bounds == null) {
            return;
        }
        if (onWillShow != null) {
            onWillShow.run();
        }
        // 锚点 = 触发区右上角上方留隙：CONTENT_BOTTOM_RIGHT 令浮层整体位于占用条上方、右缘对齐
        popup.show(owner, bounds.getMaxX(), bounds.getMinY() - GAP);
        // 若越过所属窗口右缘则回贴（锚点定位后按实际宽度修正一次）
        double maxX = owner.getX() + owner.getWidth() - EDGE_PADDING - popup.getWidth();
        if (popup.getX() > maxX) {
            popup.setX(maxX);
        }
        // 上方空间不足时贴住窗口顶部（降级：先保浮层在所属窗口内）
        double minY = owner.getY() + EDGE_PADDING;
        if (popup.getY() < minY) {
            popup.setY(minY);
        }
    }

    /**
     * 窗口显示/隐藏会让 JavaFX 合成一对 ENTERED/EXITED（鼠标并未移动）：若仅按事件
     * 驱动隐藏计时，会出现「计时到点隐藏 → 重新弹出 → 再合成 EXITED」的闪烁循环。
     * 因此以光标屏幕坐标为准：仍在「触发区 ∪ 浮层」内则忽略本次移出。
     */
    private void scheduleHideIfPointerAway() {
        if (!isPointerOverTargets()) {
            hideDelay.playFromStart();
        }
    }

    /** 隐藏计时到点时的复核：光标已回到目标区域（如已滑入浮层）则取消隐藏。 */
    private void hideIfPointerAway() {
        if (!isPointerOverTargets()) {
            hide();
        }
    }

    private boolean isPointerOverTargets() {
        Point2D pointer = pointerPosition();
        if (pointer == null) {
            return false; // 采不到光标时退回纯事件驱动行为
        }
        // 浮层判定区下扩 GAP：覆盖「条 ↔ 浮层」之间的空隙，穿越时不误判为离开
        return pointerInside(trigger, pointer, 0) || pointerInside(content, pointer, GAP);
    }

    private static boolean pointerInside(Node node, Point2D pointer, double extendBottom) {
        if (node == null || node.getScene() == null) {
            return false;
        }
        Window window = node.getScene().getWindow();
        if (window == null || !window.isShowing()) {
            return false;
        }
        Bounds bounds = node.localToScreen(node.getBoundsInLocal());
        if (bounds == null) {
            return false;
        }
        if (extendBottom > 0) {
            bounds = new BoundingBox(bounds.getMinX(), bounds.getMinY(),
                    bounds.getWidth(), bounds.getHeight() + extendBottom);
        }
        return bounds.contains(pointer);
    }

    /** 光标当前屏幕坐标（JavaFX 坐标系，与 localToScreen 同系）；采样不可用时返回 null。 */
    private Point2D pointerPosition() {
        try {
            if (robot == null) {
                robot = new Robot();
            }
            return robot.getMousePosition();
        } catch (RuntimeException e) {
            robot = null;
            return null;
        }
    }
}
