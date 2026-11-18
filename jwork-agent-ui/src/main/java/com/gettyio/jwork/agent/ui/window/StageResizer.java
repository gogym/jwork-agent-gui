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

import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * 无边框窗口边缘缩放：为 {@code UNDECORATED} 的 Stage 在场景根上补回拖拽改变尺寸的能力。
 * <p>以 Scene 级事件过滤器实现，仅在指针落在窗口四边/四角的 {@link #EDGE} 阈值内时接管事件，
 * 其余交互原样放行给子节点；最大化状态下禁用缩放。</p>
 *
 * @author gogym
 */
public final class StageResizer {

    private static final double EDGE = 7;

    private static final int NONE = 0;
    private static final int LEFT = 1;
    private static final int RIGHT = 2;
    private static final int TOP = 4;
    private static final int BOTTOM = 8;

    private final Scene scene;
    private final Stage stage;
    private int activeEdges = NONE;
    private double pressScreenX;
    private double pressScreenY;
    private double startX;
    private double startY;
    private double startW;
    private double startH;

    private StageResizer(Scene scene, Stage stage) {
        this.scene = scene;
        this.stage = stage;
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (activeEdges == NONE) {
                applyCursor(scene, edgesAt(e.getX(), e.getY(), scene));
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onPressed);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onDragged);
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            activeEdges = NONE;
            scene.setCursor(Cursor.DEFAULT);
        });
    }

    /** 给场景装上边缘缩放。 */
    public static void attach(Scene scene, Stage stage) {
        new StageResizer(scene, stage);
    }

    private void onPressed(MouseEvent e) {
        if (stage.isMaximized()) {
            return;
        }
        int edges = edgesAt(e.getX(), e.getY(), scene);
        if (edges == NONE) {
            return;
        }
        activeEdges = edges;
        pressScreenX = e.getScreenX();
        pressScreenY = e.getScreenY();
        startX = stage.getX();
        startY = stage.getY();
        startW = stage.getWidth();
        startH = stage.getHeight();
        e.consume();
    }

    private void onDragged(MouseEvent e) {
        if (activeEdges == NONE) {
            return;
        }
        double dx = e.getScreenX() - pressScreenX;
        double dy = e.getScreenY() - pressScreenY;
        double minW = Math.max(stage.getMinWidth(), 0);
        double minH = Math.max(stage.getMinHeight(), 0);

        double newX = startX;
        double newY = startY;
        double newW = startW;
        double newH = startH;

        if ((activeEdges & RIGHT) != RIGHT && (activeEdges & LEFT) != 0) {
            double w = startW - dx;
            if (w >= minW) {
                newX = startX + dx;
                newW = w;
            }
        } else if ((activeEdges & RIGHT) != 0) {
            newW = Math.max(minW, startW + dx);
        }

        if ((activeEdges & BOTTOM) != BOTTOM && (activeEdges & TOP) != 0) {
            double h = startH - dy;
            if (h >= minH) {
                newY = startY + dy;
                newH = h;
            }
        } else if ((activeEdges & BOTTOM) != 0) {
            newH = Math.max(minH, startH + dy);
        }

        stage.setX(newX);
        stage.setY(newY);
        stage.setWidth(newW);
        stage.setHeight(newH);
        e.consume();
    }

    private int edgesAt(double x, double y, Scene scene) {
        if (stage.isMaximized()) {
            return NONE;
        }
        double w = scene.getWidth();
        double h = scene.getHeight();
        int edges = NONE;
        if (x <= EDGE) {
            edges |= LEFT;
        }
        if (x >= w - EDGE) {
            edges |= RIGHT;
        }
        if (y <= EDGE) {
            edges |= TOP;
        }
        if (y >= h - EDGE) {
            edges |= BOTTOM;
        }
        return edges;
    }

    private void applyCursor(Scene scene, int edges) {
        boolean left = (edges & LEFT) != 0;
        boolean right = (edges & RIGHT) != 0;
        boolean top = (edges & TOP) != 0;
        boolean bottom = (edges & BOTTOM) != 0;
        if (left && top) {
            scene.setCursor(Cursor.NW_RESIZE);
        } else if (right && bottom) {
            scene.setCursor(Cursor.SE_RESIZE);
        } else if (right && top) {
            scene.setCursor(Cursor.NE_RESIZE);
        } else if (left && bottom) {
            scene.setCursor(Cursor.SW_RESIZE);
        } else if (left || right) {
            scene.setCursor(Cursor.H_RESIZE);
        } else if (top || bottom) {
            scene.setCursor(Cursor.V_RESIZE);
        } else {
            scene.setCursor(Cursor.DEFAULT);
        }
    }
}
