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

package com.gettyio.jwork.agent.ui.window;

import com.gettyio.jwork.agent.ui.i18n.I18n;
import com.gettyio.jwork.agent.ui.theme.UiTheme;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * 自定义标题栏：替代系统顶部程序 bar（窗口以 {@code UNDECORATED} 显示）。
 * <p>左侧品牌标识，右侧最小化 / 最大化(还原) / 关闭三个自绘按钮；
 * 空白区按住拖动可移动窗口，双击空白区切换最大化/还原。
 * 最大化按主屏 {@link Screen#getVisualBounds() 可视区} 手动铺满，避免遮挡任务栏。</p>
 *
 * @author gogym
 */
public final class TitleBar {

    /** Segoe 窗口控制字形（Chrome 风格）。 */
    private static final String GLYPH_MINIMIZE = "\uE921";
    private static final String GLYPH_MAXIMIZE = "\uE922";
    private static final String GLYPH_RESTORE = "\uE923";
    private static final String GLYPH_CLOSE = "\uE8BB";

    private final Stage stage;
    private final BorderPane node = new BorderPane();
    private final Label maxIcon;
    private final java.util.function.Consumer<Boolean> maximizeListener;
    /** 关闭按钮动作：默认真实关窗，可由宿主换成「最小化到托盘」等。 */
    private Runnable closeAction;
    /** 品牌区 HBox：[logo, title, spacer]；外部可在 spacer 前插入与标题同行右侧的按钮 */
    private HBox brand;

    private double dragX;
    private double dragY;
    private boolean maximized;
    private double restoreX;
    private double restoreY;
    private double restoreW;
    private double restoreH;

    public TitleBar(Stage stage, java.util.function.Consumer<Boolean> maximizeListener) {
        this.stage = stage;
        this.maximizeListener = maximizeListener;
        this.closeAction = stage::close;
        this.node.getStyleClass().add("title-bar");
        this.node.setLeft(buildBrand());
        this.maxIcon = iconLabel(GLYPH_MAXIMIZE);

        HBox controls = new HBox();
        controls.getStyleClass().add("window-controls");
        controls.getChildren().addAll(
                controlButton(GLYPH_MINIMIZE, "win-min", I18n.t("window.minimize"), e -> stage.setIconified(true)),
                controlButton(maxIcon, "win-max", I18n.t("window.maximize"), e -> toggleMaximize()),
                controlButton(GLYPH_CLOSE, "win-close", I18n.t("window.close"), e -> closeAction.run()));
        this.node.setRight(controls);
        installDragHandlers();
    }

    /** 替换关闭按钮动作（如托盘常驻时改为隐藏主窗）。 */
    public void setCloseAction(Runnable closeAction) {
        if (closeAction != null) {
            this.closeAction = closeAction;
        }
    }

    public Parent getNode() {
        return node;
    }

    /**
     * 在标题右侧插入一个动作节点（位于 logo + title 之后、spacer 之前），
     * 用于挂接与侧栏/面板相关的开关按钮。
     */
    public void addAfterBrand(javafx.scene.Node action) {
        if (brand == null || action == null) {
            return;
        }
        // 末尾是 spacer，将新节点插到 spacer 之前，保证不占据弹性空间
        int insertAt = Math.max(0, brand.getChildren().size() - 1);
        brand.getChildren().add(insertAt, action);
    }

    private HBox buildBrand() {
        HBox brand = new HBox(8);
        brand.getStyleClass().add("title-brand");
        // 品牌徽标：随包应用图标（绿底圆角方块 + 白 J），替代旧的字形徽标
        ImageView logo = UiTheme.brandBadge(20);
        Label text = new Label(I18n.t("app.title"));
        text.getStyleClass().add("title-text");
        Region spacer = new Region();
        spacer.getStyleClass().add("title-spacer");
        HBox.setHgrow(spacer, Priority.ALWAYS);
        brand.getChildren().addAll(logo, text, spacer);
        this.brand = brand;
        return brand;
    }

    private Button controlButton(String glyph, String styleClass, String tip,
                                 javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        return controlButton(iconLabel(glyph), styleClass, tip, action);
    }

    private Button controlButton(Label icon, String styleClass, String tip,
                                 javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button b = new Button();
        b.getStyleClass().addAll("win-btn", styleClass);
        b.setGraphic(icon);
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(action);
        return b;
    }

    private Label iconLabel(String glyph) {
        Label l = new Label(glyph);
        l.getStyleClass().add("win-btn-icon");
        return l;
    }

    private void toggleMaximize() {
        if (!maximized) {
            restoreX = stage.getX();
            restoreY = stage.getY();
            restoreW = stage.getWidth();
            restoreH = stage.getHeight();
            Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
            stage.setX(bounds.getMinX());
            stage.setY(bounds.getMinY());
            stage.setWidth(bounds.getWidth());
            stage.setHeight(bounds.getHeight());
            maximized = true;
            maxIcon.setText(GLYPH_RESTORE);
            node.getStyleClass().add("title-bar-maximized");
            notifyMaximize(true);
        } else {
            stage.setX(restoreX);
            stage.setY(restoreY);
            stage.setWidth(restoreW);
            stage.setHeight(restoreH);
            maximized = false;
            maxIcon.setText(GLYPH_MAXIMIZE);
            node.getStyleClass().remove("title-bar-maximized");
            notifyMaximize(false);
        }
    }

    private void notifyMaximize(boolean value) {
        if (maximizeListener != null) {
            maximizeListener.accept(value);
        }
    }

    private void installDragHandlers() {
        node.setOnMousePressed(this::onPressed);
        node.setOnMouseDragged(this::onDragged);
        node.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !isControlTarget(e)) {
                toggleMaximize();
            }
        });
    }

    private void onPressed(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || isControlTarget(e)) {
            return;
        }
        if (maximized) {
            // 从最大化态拖动：先还原并把指针落到窗口中部，避免整窗瞬间跳回
            toggleMaximize();
            stage.setX(Math.max(0, e.getScreenX() - restoreW / 2));
            stage.setY(Math.max(0, e.getScreenY() - node.getHeight() / 2));
        }
        dragX = e.getScreenX() - stage.getX();
        dragY = e.getScreenY() - stage.getY();
        node.setCursor(Cursor.DEFAULT);
    }

    private void onDragged(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || isControlTarget(e)) {
            return;
        }
        stage.setX(e.getScreenX() - dragX);
        stage.setY(e.getScreenY() - dragY);
    }

    private boolean isControlTarget(MouseEvent e) {
        return e.getTarget() instanceof Button;
    }
}
