package com.gettyio.gim.agent.ui.chat;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.geometry.Side;
import javafx.stage.Window;

/**
 * 菜单弹出定位工具：让 {@link ContextMenu} 在锚点上方弹出并夹取到窗口可视范围内。
 * <p>被附件、模型、审批三处工具条菜单共用（原 {@code MainController#showMenuAboveWithinWindow}）。</p>
 */
public final class MenuPopup {

    private MenuPopup() {
    }

    /**
     * 让菜单在锚点上方弹出，并夹取到主窗口可视范围内：先按 {@link Side#TOP} 弹出量取真实尺寸，
     * 再重定位——水平不越右/左边界，上方空间不够则翻到下方。
     */
    public static void showAbove(ContextMenu menu, Node anchor) {
        menu.show(anchor, Side.TOP, 0, 0);
        Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        if (b == null) {
            return;
        }
        Parent skin = (Parent) menu.getSkin().getNode();
        skin.applyCss();
        skin.layout();
        double w = skin.getBoundsInLocal().getWidth();
        double h = skin.getBoundsInLocal().getHeight();
        double margin = 8;
        double x = b.getMinX();
        double y = b.getMinY() - h; // 贴合按钮顶边：复用实例消除累积漂移后，基准 0 即贴合
        Window win = anchor.getScene() == null ? null : anchor.getScene().getWindow();
        if (win != null) {
            double winLeft = win.getX() + margin;
            double winRight = win.getX() + win.getWidth() - margin;
            double winTop = win.getY() + margin;
            if (x + w > winRight) {
                x = Math.max(winLeft, winRight - w);
            }
            if (x < winLeft) {
                x = winLeft;
            }
            if (y < winTop) {
                y = b.getMaxY() + 4;
            }
        }
        menu.setX(x);
        menu.setY(y);
    }

    /** 是否处于「刚被关闭」的防抖窗口内（250ms）：用于把再点按钮识别为关闭手势而非重开。 */
    public static boolean inHideDebounce(long hiddenAt) {
        return System.currentTimeMillis() - hiddenAt < 250;
    }

    /**
     * 让菜单在锚点下方弹出，并夹取到主窗口可视范围内：先按 {@link Side#BOTTOM} 弹出量取真实尺寸，
     * 再重定位——水平不越右/左边界，下方空间不够则翻到上方。用于顶部工具条按钮（如技能页「导入技能」）。
     */
    public static void showBelow(ContextMenu menu, Node anchor) {
        menu.show(anchor, Side.BOTTOM, 0, 0);
        Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        if (b == null) {
            return;
        }
        Parent skin = (Parent) menu.getSkin().getNode();
        skin.applyCss();
        skin.layout();
        double w = skin.getBoundsInLocal().getWidth();
        double h = skin.getBoundsInLocal().getHeight();
        double margin = 8;
        double x = b.getMinX();
        double y = b.getMaxY() + 4; // 贴合按钮底边向下弹出
        Window win = anchor.getScene() == null ? null : anchor.getScene().getWindow();
        if (win != null) {
            double winLeft = win.getX() + margin;
            double winRight = win.getX() + win.getWidth() - margin;
            double winBottom = win.getY() + win.getHeight() - margin;
            if (x + w > winRight) {
                x = Math.max(winLeft, winRight - w);
            }
            if (x < winLeft) {
                x = winLeft;
            }
            if (y + h > winBottom) {
                y = b.getMinY() - h - 4; // 下方放不下翻到上方
            }
        }
        menu.setX(x);
        menu.setY(y);
    }
}
