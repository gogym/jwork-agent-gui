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
import com.gettyio.gim.agent.ui.theme.UiTheme;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 划词助手（全局热键 Ctrl+Alt+D）：抓取前台窗口的选中文本，在光标旁浮出操作条
 * 「问 AI / 翻译 / 润色」，动作交由 {@link QuickAskWindow} 流式作答。
 * <p>抓取经 {@link SelectionGrabber}（模拟 Ctrl+C + 剪贴板哨兵）在后台线程完成，不阻塞 FX；
 * 抓取在途时重复触发直接忽略。操作条为单例无边框置顶小窗：失焦 / ESC 自动隐藏，
 * 重复触发仅更新摘要与位置。「问 AI」仅预填输入框供用户补充问题；翻译 / 润色预填并立即发送。</p>
 *
 * @author gogym
 */
public class SelectionAssistant {

    private static final Logger log = LoggerFactory.getLogger(SelectionAssistant.class);

    /** 操作条摘要展示的最大字符数 */
    private static final int PREVIEW_MAX = 18;

    /** 操作条动作：ASK 预填待编辑；TRANSLATE / POLISH 预填并立即发送。 */
    enum Action {
        ASK, TRANSLATE, POLISH
    }

    private final QuickAskWindow quickAsk;
    /** 抓取去重：抓取在途时热键连按直接忽略 */
    private final AtomicBoolean grabbing = new AtomicBoolean();

    private Stage bar;
    private Label preview;
    /** 最近一次抓取的选中文本（操作条动作的载荷） */
    private String pending;

    public SelectionAssistant(QuickAskWindow quickAsk) {
        this.quickAsk = quickAsk;
    }

    /** 全局热键入口（消息泵线程调用）：后台抓取选中文本，取得后弹出操作条。 */
    public void trigger() {
        if (!SelectionGrabber.isSupported()) {
            return;
        }
        if (!grabbing.compareAndSet(false, true)) {
            return;
        }
        Point cursor = cursorPoint();
        Thread worker = new Thread(() -> {
            try {
                String text = SelectionGrabber.grab();
                if (text == null) {
                    return;
                }
                Platform.runLater(() -> showBar(text, cursor));
            } catch (RuntimeException e) {
                log.warn("划词抓取异常", e);
            } finally {
                grabbing.set(false);
            }
        }, "gim-selection-grab");
        worker.setDaemon(true);
        worker.start();
    }

    /** 组装动作请求文本：翻译 / 润色前置指令，问 AI 原样。包私有便于单测。 */
    static String buildPrompt(Action action, String text) {
        return switch (action) {
            case TRANSLATE -> I18n.t("selection.prompt.translate") + text;
            case POLISH -> I18n.t("selection.prompt.polish") + text;
            case ASK -> text;
        };
    }

    /** 操作条摘要：空白折叠为单空格、超长截断。包私有便于单测。 */
    static String truncatePreview(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /** 显示操作条（FX 线程）：单例复用，重复触发仅更新摘要与位置。 */
    private void showBar(String text, Point cursor) {
        pending = text;
        buildIfNeeded();
        preview.setText(truncatePreview(text, PREVIEW_MAX));
        positionBar(cursor);
        if (!bar.isShowing()) {
            bar.show();
        }
        bar.toFront();
        bar.requestFocus();
    }

    private void buildIfNeeded() {
        if (bar != null) {
            return;
        }
        preview = new Label();
        preview.getStyleClass().add("selection-preview");
        HBox box = new HBox(preview, actionButton(Action.ASK), actionButton(Action.TRANSLATE),
                actionButton(Action.POLISH));
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("selection-bar");

        Scene scene = new Scene(box);
        scene.setFill(Color.TRANSPARENT);
        UiTheme.apply(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hideBar();
            }
        });

        bar = new Stage(StageStyle.TRANSPARENT);
        bar.setResizable(false);
        bar.setAlwaysOnTop(true);
        // 无边框窗口没有可见标题栏；仅为任务切换器等系统入口兜底
        bar.setTitle(I18n.t("selection.bar.ask"));
        bar.setScene(scene);
        // 点击别处即失焦收起，不打断用户原操作
        bar.focusedProperty().addListener((obs, was, focused) -> {
            if (was && !focused) {
                hideBar();
            }
        });
    }

    private Button actionButton(Action action) {
        Button b = new Button(I18n.t(actionKey(action)));
        b.getStyleClass().add("selection-action");
        b.setFocusTraversable(false);
        b.setOnAction(e -> {
            String text = pending;
            hideBar();
            if (text != null) {
                quickAsk.askWith(buildPrompt(action, text), action != Action.ASK);
            }
        });
        return b;
    }

    private static String actionKey(Action action) {
        return switch (action) {
            case ASK -> "selection.bar.ask";
            case TRANSLATE -> "selection.bar.translate";
            case POLISH -> "selection.bar.polish";
        };
    }

    /** 依光标位置定位操作条：光标下方优先，空间不足翻上方，水平居中并夹取在屏幕工作区内。 */
    private void positionBar(Point cursor) {
        // 先应用样式再测量，保证首次弹出时宽度按运行时字体计算
        bar.getScene().getRoot().applyCss();
        bar.getScene().getRoot().layout();
        double w = bar.getScene().getRoot().prefWidth(-1);
        double h = bar.getScene().getRoot().prefHeight(-1);
        Rectangle2D primary = Screen.getPrimary().getVisualBounds();
        double cx = cursor != null ? cursor.getX() : primary.getMinX() + primary.getWidth() / 2;
        double cy = cursor != null ? cursor.getY() : primary.getMinY() + primary.getHeight() / 2;
        Screen screen = Screen.getScreensForRectangle(cx, cy, 1, 1).stream().findFirst()
                .orElse(Screen.getPrimary());
        Rectangle2D vb = screen.getVisualBounds();
        double x = clamp(cx - w / 2, vb.getMinX() + 8, vb.getMaxX() - w - 8);
        double y = cy + 18;
        if (y + h > vb.getMaxY() - 8) {
            y = cy - h - 14; // 下方放不下：翻到光标上方
        }
        bar.setX(x);
        bar.setY(clamp(y, vb.getMinY() + 8, vb.getMaxY() - h - 8));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(v, max));
    }

    private void hideBar() {
        if (bar != null && bar.isShowing()) {
            bar.hide();
        }
    }

    private static Point cursorPoint() {
        try {
            return java.awt.MouseInfo.getPointerInfo().getLocation();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
