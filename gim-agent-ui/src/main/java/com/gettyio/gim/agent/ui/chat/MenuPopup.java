/*
 * Copyright 2026 gogym
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
 *
 * @author gogym
 */
public final class MenuPopup {

    /**
     * 向上/向下贴合锚点按钮时的补偿量（px）：{@code ContextMenu} skin 的
     * {@code getBoundsInLocal()} 含透明阴影/内边距，基准 0 会让菜单与按钮之间留一道阴影缝，
     * 故统一往下（上弹）/往上（下弹）压这段缝。所有贴合菜单共此单一调参入口。
     */
    public static final double GAP = 12;

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
        double y = b.getMinY() - h + GAP;
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
                y = b.getMaxY() + GAP; // 上方空间不够翻到下方，同样贴近按钮底边
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
