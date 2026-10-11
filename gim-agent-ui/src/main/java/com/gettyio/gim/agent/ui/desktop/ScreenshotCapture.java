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

package com.gettyio.gim.agent.ui.desktop;

import com.gettyio.gim.agent.ui.i18n.I18n;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * 截屏提问：抓取主屏当前画面（冻结），铺满全屏遮罩供用户拖框圈选区域，裁切后回调 PNG 字节。
 * <p>坐标换算兼顾 HiDPI：抓取用像素尺寸（视觉边界 × 输出缩放），遮罩与选择框用逻辑坐标，
 * 释放时按缩放系数把逻辑选区映射回位图做 {@link BufferedImage#getSubimage} 裁切。</p>
 * <p>抓取或写图失败仅记日志、静默返回（不回调），不打断对话主流程。ESC 取消。</p>
 *
 * @author gogym
 */
public final class ScreenshotCapture {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotCapture.class);
    /** 小于该边长的拖框视为误触（取消） */
    private static final int MIN_SELECTION = 4;

    private ScreenshotCapture() {
    }

    /**
     * 冻结主屏 → 全屏遮罩圈选 → 裁切区域回调 PNG 字节（在 FX 线程回调）。
     *
     * @param onPicked 选中区域 PNG 字节回调；取消或失败时不调用
     */
    public static void captureRegion(Consumer<byte[]> onPicked) {
        try {
            Screen primary = Screen.getPrimary();
            Rectangle2D vb = primary.getVisualBounds();
            double scale = Math.max(1.0, primary.getOutputScaleX());
            int pxW = (int) Math.round(vb.getWidth() * scale);
            int pxH = (int) Math.round(vb.getHeight() * scale);
            java.awt.Rectangle pixelRect = new java.awt.Rectangle(
                    (int) Math.round(vb.getMinX() * scale), (int) Math.round(vb.getMinY() * scale), pxW, pxH);
            Robot robot = new Robot();
            BufferedImage full = robot.createScreenCapture(pixelRect);
            byte[] fullPng = encodePng(full);
            if (fullPng == null) {
                return;
            }
            showOverlay(vb, scale, full, fullPng, onPicked);
        } catch (RuntimeException | java.awt.AWTException | IOException e) {
            log.warn("截屏抓取失败", e);
        }
    }

    /** 构建全屏遮罩：冻结画面为背景，拖框圈选，ESC/点外取消。 */
    private static void showOverlay(Rectangle2D vb, double scale, BufferedImage full,
                                    byte[] fullPng, Consumer<byte[]> onPicked) {
        Stage stage = new Stage(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle(I18n.t("screenshot.title"));
        stage.setX(vb.getMinX());
        stage.setY(vb.getMinY());

        ImageView shot = new ImageView(new Image(new ByteArrayInputStream(fullPng)));
        shot.setFitWidth(vb.getWidth());
        shot.setFitHeight(vb.getHeight());

        Pane layer = new Pane(shot);
        Rectangle dim = new Rectangle(vb.getWidth(), vb.getHeight(), Color.rgb(0, 0, 0, 0.35));
        Rectangle sel = new Rectangle();
        sel.setFill(Color.rgb(37, 99, 235, 0.18));
        sel.setStroke(Color.web("#2563EB"));
        sel.setStrokeWidth(1.5);
        sel.setVisible(false);
        Text hint = new Text(I18n.t("screenshot.hint"));
        hint.setFill(Color.web("#E5E7EB"));
        hint.setFont(Font.font(14));
        hint.setX(16);
        hint.setY(28);
        layer.getChildren().addAll(dim, sel, hint);
        layer.setStyle("-fx-cursor: crosshair;");

        StackPane root = new StackPane(layer);
        root.setStyle("-fx-background-color: transparent;");
        Scene scene = new Scene(root, vb.getWidth(), vb.getHeight(), Color.TRANSPARENT);
        stage.setScene(scene);

        final double[] anchor = new double[2];
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            anchor[0] = e.getX();
            anchor[1] = e.getY();
            sel.setVisible(true);
            relocateSel(sel, e.getX(), e.getY(), e.getX(), e.getY());
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e ->
                relocateSel(sel, anchor[0], anchor[1], e.getX(), e.getY()));
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            stage.hide();
            double sx = Math.min(anchor[0], e.getX());
            double sy = Math.min(anchor[1], e.getY());
            double sw = Math.abs(e.getX() - anchor[0]);
            double sh = Math.abs(e.getY() - anchor[1]);
            if (sw < MIN_SELECTION || sh < MIN_SELECTION) {
                return; // 视为误触，取消
            }
            byte[] png = crop(full, scale, sx, sy, sw, sh);
            if (png != null) {
                onPicked.accept(png);
            }
        });
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                stage.hide();
            }
        });

        stage.show();
        stage.toFront();
        root.requestFocus();
    }

    private static void relocateSel(Rectangle sel, double x1, double y1, double x2, double y2) {
        sel.setX(Math.min(x1, x2));
        sel.setY(Math.min(y1, y2));
        sel.setWidth(Math.abs(x2 - x1));
        sel.setHeight(Math.abs(y2 - y1));
    }

    /** 逻辑选区按缩放映射回位图裁切，编码为 PNG；越界裁剪、失败返回 null。 */
    private static byte[] crop(BufferedImage full, double scale, double sx, double sy, double sw, double sh) {
        int x = clamp((int) Math.round(sx * scale), 0, full.getWidth());
        int y = clamp((int) Math.round(sy * scale), 0, full.getHeight());
        int w = clamp((int) Math.round(sw * scale), 1, full.getWidth() - x);
        int h = clamp((int) Math.round(sh * scale), 1, full.getHeight() - y);
        try {
            BufferedImage sub = full.getSubimage(x, y, w, h);
            // getSubimage 共享父图 raster，复制为独立 ARGB 图再编码，避免尺寸/偏移异常
            BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            copy.createGraphics().drawImage(sub, 0, 0, null);
            return encodePng(copy);
        } catch (RuntimeException | IOException e) {
            log.warn("截屏裁切失败", e);
            return null;
        }
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", baos)) {
            log.warn("PNG 编码器不可用");
            return null;
        }
        return baos.toByteArray();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
