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

import com.gettyio.gim.agent.core.chat.ContextBreakdown;
import com.gettyio.gim.agent.ui.i18n.I18n;
import javafx.animation.PauseTransition;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.robot.Robot;
import javafx.stage.Popup;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * 上下文占用条的悬停浮层：标题 + 总占比、说明文案、分类分段条与图例（系统提示词/系统工具/技能/消息）、
 * 已用/上限明细行，以及「压缩当前会话」按钮。
 * <p>挂到占用条区域后鼠标悬停即自动弹出；移出触发节点或浮层后延迟一小段再隐藏，
 * 留出从条上滑入浮层的过渡时间。浮层用原始 {@link Popup} 承载：弹出时不抢键盘焦点
 * （输入框光标与草稿不受影响），显示时自动继承所属窗口的样式表（明暗主题随动）。</p>
 *
 * @author gogym
 */
public final class ContextPopover {

    /** 悬停桥接时长：移出后延迟隐藏，覆盖「条 → 浮层」之间的移动。 */
    private static final Duration HIDE_DELAY = Duration.millis(300);
    /** 浮层与触发节点间的垂直间隙。 */
    private static final double GAP = 8;
    /** 浮层与所属窗口边缘的最小横向留白。 */
    private static final double EDGE_PADDING = 8;
    /** 分类个数：系统提示词 / 系统工具 / 技能 / 消息；分段与圆点色类按索引区分。 */
    public static final int CATEGORIES = 4;
    /** 分段条段间隙（px）：与 CSS 的 -fx-spacing 同值，布局算宽时扣掉。 */
    private static final double SEG_GAP = 2;

    private final Popup popup = new Popup();
    private final VBox content;
    private final Label percentLabel = new Label();
    private final Label infoLabel = new Label();
    private final HBox barBox = new HBox();
    private final Region[] segments = new Region[CATEGORIES];
    private final VBox legendBox = new VBox();
    private final Label[] legendNames = new Label[CATEGORIES];
    private final Label[] legendPcts = new Label[CATEGORIES];
    private final Button compactButton = new Button();
    private final Label statusLabel = new Label();
    private final PauseTransition hideDelay = new PauseTransition(HIDE_DELAY);
    /** 当前各类占上限的比例（0–1）；分段条宽度据此随浮层实际宽度布局。 */
    private final double[] fractions = new double[CATEGORIES];

    private Node trigger;
    /** 光标位置采样器（懒加载）；采样不可用的环境下降级为纯事件驱动。 */
    private Robot robot;
    private Runnable onCompact;
    private Runnable onWillShow;

    /** 构建浮层：装配标题行、说明、分段条、图例、明细行、压缩按钮、状态行与隐藏计时/悬停桥接事件。 */
    public ContextPopover() {
        Label titleLabel = new Label(I18n.t("context.popup.title"));
        titleLabel.getStyleClass().add("context-popup-title");
        percentLabel.getStyleClass().add("context-popup-percent");
        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        HBox header = new HBox(titleLabel, headSpacer, percentLabel);
        header.setAlignment(Pos.CENTER_LEFT);

        Label descLabel = new Label(I18n.t("context.popup.desc"));
        descLabel.getStyleClass().add("context-popup-desc");
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(276);

        barBox.getStyleClass().add("context-popup-bar");
        barBox.setSpacing(SEG_GAP);
        barBox.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < CATEGORIES; i++) {
            Region seg = new Region();
            seg.getStyleClass().addAll("context-seg", "context-seg-" + i);
            seg.setManaged(false);
            seg.setVisible(false);
            segments[i] = seg;
            barBox.getChildren().add(seg);
        }
        // 分段宽度按浮层实际宽度算：Popup 首帧布局后宽度才定下来，监听补一次
        barBox.widthProperty().addListener((o, ov, nv) -> layoutSegments());

        legendBox.setSpacing(6);
        for (int i = 0; i < CATEGORIES; i++) {
            Region dot = new Region();
            dot.getStyleClass().addAll("context-dot", "context-dot-" + i);
            legendNames[i] = new Label();
            legendNames[i].getStyleClass().add("context-popup-legend-name");
            legendPcts[i] = new Label();
            legendPcts[i].getStyleClass().add("context-popup-legend-pct");
            legendPcts[i].setMinWidth(36);
            legendPcts[i].setAlignment(Pos.CENTER_RIGHT);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(dot, legendNames[i], spacer, legendPcts[i]);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setSpacing(6);
            legendBox.getChildren().add(row);
        }

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
        statusLabel.setMaxWidth(276);
        hideStatus();
        setBreakdown(null, null, null);

        content = new VBox(header, descLabel, barBox, legendBox, infoLabel, compactButton, statusLabel);
        content.getStyleClass().add("context-popup");
        content.setPrefWidth(300);
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

    /** 标题行右侧的总占比文案（如「42%」）。 */
    public void setPercent(String text) {
        percentLabel.setText(text == null ? "" : text);
    }

    /** 明细行文案（如「115k/128k 已用上下文」）。 */
    public void setInfo(String text) {
        infoLabel.setText(text == null ? "" : text);
    }

    /**
     * 注入分类明细：分段条比例 + 图例名称/百分比；{@code fractions} 为 null 表示尚无扫描结果，
     * 分段条与图例整体隐藏（首帧到扫描回来之间的空窗不画假数据）。
     */
    public void setBreakdown(double[] fr, String[] names, String[] percents) {
        boolean show = fr != null && names != null && percents != null;
        barBox.setVisible(show);
        barBox.setManaged(show);
        legendBox.setVisible(show);
        legendBox.setManaged(show);
        for (int i = 0; i < CATEGORIES; i++) {
            fractions[i] = show ? Math.max(0, Math.min(1, fr[i])) : 0;
            if (show) {
                legendNames[i].setText(names[i]);
                legendPcts[i].setText(percents[i]);
            }
        }
        layoutSegments();
    }

    /** 按浮层实际宽度铺分段：零占比段不占位（含间隙），余量露出轨道底色。 */
    private void layoutSegments() {
        double width = barBox.getWidth();
        if (width <= 0) {
            return;
        }
        int visible = 0;
        for (double f : fractions) {
            if (f > 0) {
                visible++;
            }
        }
        double unit = Math.max(0, width - SEG_GAP * Math.max(0, visible - 1));
        for (int i = 0; i < CATEGORIES; i++) {
            double w = fractions[i] * unit;
            boolean show = fractions[i] > 0 && w > 0;
            segments[i].setVisible(show);
            segments[i].setManaged(show);
            segments[i].setMinWidth(w);
            segments[i].setPrefWidth(w);
            segments[i].setMaxWidth(w);
        }
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

    // ===================== 分类估算 → 展示比例（纯函数，供单测） =====================

    /** 缩放后的展示数据：各类占上限的比例（分段条宽）与右对齐百分比文案（图例）。 */
    public record ScaledBreakdown(double[] fractions, String[] percents) {
    }

    /**
     * 把分类估算缩放到占用条口径：各类独立粗估之和未必等于占用条总量（真实回报/压缩基准），
     * 等比缩放后图例百分比之和才与标题占比自洽；待发输入实时叠加进消息类。
     */
    public static ScaledBreakdown scale(ContextBreakdown bd, int pendingTokens, int estimatedTokens, int limit) {
        int[] vals = {bd.systemTokens(), bd.toolTokens(), bd.skillTokens(), bd.messageTokens() + pendingTokens};
        long raw = 0;
        for (int v : vals) {
            raw += Math.max(0, v);
        }
        double k = raw > 0 ? (double) Math.max(0, estimatedTokens) / raw : 0;
        double[] fr = new double[CATEGORIES];
        String[] pct = new String[CATEGORIES];
        for (int i = 0; i < CATEGORIES; i++) {
            double ratio = limit > 0 ? Math.max(0, vals[i]) * k / limit : 0;
            fr[i] = Math.max(0, Math.min(1, ratio));
            pct[i] = formatPercent(ratio * 100);
        }
        return new ScaledBreakdown(fr, pct);
    }

    /** 百分比文案：0 → 「0%」；不足 1% → 「&lt;1%」（参考图口径，避免小类被四舍五入抹掉）；其余取整。 */
    public static String formatPercent(double percent) {
        if (percent <= 0) {
            return "0%";
        }
        if (percent < 1) {
            return "<1%";
        }
        return Math.round(percent) + "%";
    }
}