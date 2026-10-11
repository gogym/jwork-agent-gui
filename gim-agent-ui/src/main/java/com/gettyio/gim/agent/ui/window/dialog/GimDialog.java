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

package com.gettyio.gim.agent.ui.window.dialog;

import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.theme.UiTheme;
import com.gettyio.gim.agent.ui.window.FrostedBackdrop;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.Optional;

/**
 * 统一无边框对话框：与主窗口同款圆角壳（透明 Stage + 投影 + 圆角裁剪）+
 * 自绘标题栏（标题文本 + 关闭按钮，可拖动），替代系统装饰的 Alert / 裸 Stage。
 * <p>用法：{@link #message}/{@link #confirm}/{@link #choose} 覆盖消息与确认场景，
 * {@link #promptText} 覆盖单行改名场景；{@link #shell} 返回带空内容区的壳，调用方往 {@link Shell#body()} 塞任意表单内容。
 * 有 owner 时窗口模态、居中于 owner 并把 owner 主窗口磨玻璃化，无 owner 时应用级模态。</p>
 *
 * @author gogym
 */
public final class GimDialog {

    /** 圆角半径（px），与 CSS 中 .window-shell 的 -fx-background-radius 保持一致。 */
    private static final double CORNER_RADIUS = 10;
    private static final String GLYPH_CLOSE = "\uE8BB";

    /** 消息语义级别：决定消息弹窗左侧图标的字形与配色（INFO=中性、WARN=需留意、ERROR=失败/错误）。 */
    public enum Level { INFO, WARN, ERROR }

    private GimDialog() {
    }

    /** 信息提示：单个「确定」按钮（默认 INFO 级图标）。 */
    public static void message(Window owner, String title, String header, String message) {
        message(owner, title, header, message, Level.INFO);
    }

    /** 信息提示（指定语义级别）：失败类用 {@link Level#ERROR}，需留意用 {@link Level#WARN}。 */
    public static void message(Window owner, String title, String header, String message, Level level) {
        Frame f = messageFrame(owner, title, header, message, level);
        HBox actions = new HBox(8);
        actions.getStyleClass().add("dialog-actions");
        Button ok = actionButton(I18n.t("common.ok"), true);
        ok.setOnAction(e -> f.stage.close());
        actions.getChildren().add(ok);
        f.body.getChildren().add(actions);
        prepare(f.stage);
        f.stage.showAndWait();
    }

    /** 确认对话框：确定 / 取消，确定返回 true。 */
    public static boolean confirm(Window owner, String title, String message) {
        return confirm(owner, title, null, message);
    }

    /** 确认对话框（带加粗副标题）。 */
    public static boolean confirm(Window owner, String title, String header, String message) {
        boolean[] result = {false};
        Frame f = messageFrame(owner, title, header, message);
        HBox actions = new HBox(8);
        actions.getStyleClass().add("dialog-actions");
        Button cancel = actionButton(I18n.t("common.cancel"), false);
        cancel.setOnAction(e -> f.stage.close());
        Button ok = actionButton(I18n.t("common.ok"), true);
        ok.setOnAction(e -> {
            result[0] = true;
            f.stage.close();
        });
        actions.getChildren().addAll(cancel, ok);
        f.body.getChildren().add(actions);
        prepare(f.stage);
        f.stage.showAndWait();
        return result[0];
    }

    /** 多选项对话框：返回被点按钮的索引；关闭（X / ESC）返回 -1。按钮按传入顺序排列，均为普通样式。 */
    public static int choose(Window owner, String title, String header, String message, String... options) {
        int[] result = {-1};
        Frame f = messageFrame(owner, title, header, message);
        HBox actions = new HBox(8);
        actions.getStyleClass().add("dialog-actions");
        for (int i = 0; i < options.length; i++) {
            int index = i;
            Button b = actionButton(options[i], false);
            b.setOnAction(e -> {
                result[0] = index;
                f.stage.close();
            });
            actions.getChildren().add(b);
        }
        f.body.getChildren().add(actions);
        prepare(f.stage);
        f.stage.showAndWait();
        return result[0];
    }

    /**
     * 强制型多选项对话框：不可关闭（无标题栏关闭按钮、无 ESC、忽略关闭请求），
     * 用户只能从给定的正向选项中选择一个，返回其索引；用于强制更新等必须响应的场景。
     * 首个选项按主按钮样式高亮。
     */
    public static int chooseForced(Window owner, String title, String header, String message, String... options) {
        int[] result = {-1};
        Frame f = messageFrame(owner, title, header, message);
        HBox actions = new HBox(8);
        actions.getStyleClass().add("dialog-actions");
        for (int i = 0; i < options.length; i++) {
            int index = i;
            Button b = actionButton(options[i], i == 0);
            b.setOnAction(e -> {
                result[0] = index;
                f.stage.close();
            });
            actions.getChildren().add(b);
        }
        f.body.getChildren().add(actions);
        prepare(f.stage);
        f.stage.showAndWait();
        return result[0];
    }

    /** 自定义内容对话框壳：提供 {@link #body()} 内容容器与显示方法。 */
    public static final class Shell {

        private final Frame frame;

        private Shell(Frame frame) {
            this.frame = frame;
        }

        public Stage stage() {
            return frame.stage;
        }

        /** 内容容器（已带统一内边距与间距），调用方往其中添加表单等节点。 */
        public VBox body() {
            return frame.body;
        }

        public void show() {
            prepare(frame.stage);
            frame.stage.show();
        }

        public void showAndWait() {
            prepare(frame.stage);
            frame.stage.showAndWait();
        }
    }

    /** 内部装配结果：窗口与其内容容器。 */
    private static final class Frame {
        final Stage stage;
        final VBox body;

        Frame(Stage stage, VBox body) {
            this.stage = stage;
            this.body = body;
        }
    }

    /** 组装消息型对话框（header + message + 按钮区由调用方补充）。 */
    private static Frame messageFrame(Window owner, String title, String header, String message) {
        return messageFrame(owner, title, header, message, null);
    }

    /**
     * 组装消息型对话框。{@code level} 非空时在文本左侧加语义图标并按级别取色（消息弹窗）；
     * 为空时保持无图标的纯文本布局（确认 / 多选项弹窗）。
     */
    private static Frame messageFrame(Window owner, String title, String header, String message, Level level) {
        Frame f = frame(owner, title);
        Label h = null;
        if (header != null && !header.isBlank()) {
            h = new Label(header);
            h.getStyleClass().add("dialog-header");
            h.setWrapText(true);
        }
        Label m = new Label(message == null ? "" : message);
        m.getStyleClass().add("dialog-message");
        m.setWrapText(true);
        m.setMaxWidth(460);
        if (level == null) {
            if (h != null) {
                f.body.getChildren().add(h);
            }
            f.body.getChildren().add(m);
            return f;
        }
        VBox texts = new VBox(6);
        if (h != null) {
            texts.getChildren().add(h);
        }
        texts.getChildren().add(m);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox row = new HBox(12, stateIcon(level), texts);
        // 图标与提示文本沿水平中线居中对齐：HBox 默认 fillHeight=true 会把文本列拉满行高，
        // 行对齐将失去纵向意义（文本被 VBox 顶对齐），必须关掉纵向拉伸
        row.setFillHeight(false);
        row.setAlignment(Pos.CENTER_LEFT);
        // 消息型弹窗留最小宽度：短文案下比例更稳，不显局促
        f.body.setMinWidth(320);
        f.body.getChildren().add(row);
        return f;
    }

    /** 语义级别对应的状态图标（Segoe Fluent Icons / MDL2 字形 + CSS 配色类）。 */
    private static Label stateIcon(Level level) {
        String glyph = switch (level) {
            case WARN -> "\uE7BA";
            case ERROR -> "\uEA39";
            default -> "\uE946";
        };
        String style = switch (level) {
            case WARN -> "dialog-state-warn";
            case ERROR -> "dialog-state-error";
            default -> "dialog-state-info";
        };
        Label icon = new Label(glyph);
        icon.getStyleClass().addAll("dialog-state-icon", style);
        return icon;
    }

    /** 无边框窗口骨架：透明 Stage + 圆角壳 + 自绘标题栏（标题 + 关闭，可拖动）。 */
    private static Frame frame(Window owner, String title) {
        return frame(owner, title, true);
    }

    /**
     * 无边框窗口骨架。
     *
     * @param dismissible 是否可关闭：{@code true} 显示关闭按钮并支持 ESC；
     *                    {@code false} 隐藏关闭按钮、禁用 ESC 并消费关闭请求（强制交互用）
     */
    private static Frame frame(Window owner, String title, boolean dismissible) {
        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        } else {
            stage.initModality(Modality.APPLICATION_MODAL);
        }
        stage.setTitle(title);

        // 标题栏：HBox + center-left（与主窗口标题栏一致），保证标题在栏内垂直居中
        HBox bar = new HBox();
        bar.getStyleClass().add("title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        Label titleText = new Label(title);
        titleText.getStyleClass().add("title-text");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        bar.getChildren().addAll(titleText, spacer);
        if (dismissible) {
            bar.getChildren().add(closeButton(stage));
        } else {
            stage.setOnCloseRequest(e -> e.consume());
        }
        installDrag(stage, bar);

        VBox body = new VBox();
        body.getStyleClass().add("dialog-body");

        BorderPane shellPane = new BorderPane();
        shellPane.getStyleClass().add("window-shell");
        shellPane.setTop(bar);
        shellPane.setCenter(body);
        // 圆角裁剪：标题栏与内容区随容器一起呈现四角圆角
        Rectangle clip = new Rectangle();
        clip.setArcWidth(CORNER_RADIUS * 2);
        clip.setArcHeight(CORNER_RADIUS * 2);
        clip.widthProperty().bind(shellPane.widthProperty());
        clip.heightProperty().bind(shellPane.heightProperty());
        shellPane.setClip(clip);

        StackPane root = new StackPane(shellPane);
        root.getStyleClass().add("window-root-shell");

        Scene scene = new Scene(root, Color.TRANSPARENT);
        UiTheme.apply(scene);
        if (dismissible) {
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE), stage::close);
        }
        stage.setScene(scene);
        bindOwner(stage, owner);
        return new Frame(stage, body);
    }

    /** 弹出前置备：按内容算出窗口尺寸并预先居中到 owner，避免透明窗口先闪在系统默认位置。 */
    private static void prepare(Stage stage) {
        stage.sizeToScene();
        centerOnOwner(stage);
    }

    /**
     * 绑定 owner：显示时居中并给主窗口叠磨玻璃背景，关闭时撤除；
     * 窗口尺寸变化（内容变高、校验提示出现）后继续跟随居中。owner 未注册遮罩时只影响居中。
     */
    private static void bindOwner(Stage stage, Window owner) {
        Runnable[] frost = new Runnable[1];
        ChangeListener<Number> recenter = (obs, old, now) -> centerOnOwner(stage);
        stage.widthProperty().addListener(recenter);
        stage.heightProperty().addListener(recenter);
        stage.setOnShown(e -> {
            centerOnOwner(stage);
            runAndClear(frost);
            frost[0] = FrostedBackdrop.enter(owner);
        });
        stage.setOnHidden(e -> runAndClear(frost));
    }

    private static void runAndClear(Runnable[] holder) {
        if (holder[0] != null) {
            holder[0].run();
            holder[0] = null;
        }
    }

    /**
     * 单行输入对话框：确定返回去掉首尾空白的文本，取消 / 关闭返回空。
     * <p>内容为空白时「确定」不可点，比弹一条错误提示省一轮；Enter 等同确定，Esc 关闭。</p>
     *
     * @param initial   框内预填的原值，打开即全选，直接输入就是覆盖
     * @param maxLength 字符上限，超出后输入框本身就接不了
     */
    public static Optional<String> promptText(Window owner, String title, String label, String initial, int maxLength) {
        String[] saved = {null};
        Frame f = frame(owner, title);

        TextField field = new TextField(initial == null ? "" : initial);
        // TextField 没有 setMaxLength，超限的改动直接交给过滤器丢掉
        field.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().length() <= maxLength ? change : null));
        field.setMaxWidth(Double.MAX_VALUE);
        field.selectAll();

        Label cap = new Label(label);
        cap.getStyleClass().add("form-label");
        VBox form = new VBox(4, cap, field);
        form.setMinWidth(360);

        Button cancel = actionButton(I18n.t("common.cancel"), false);
        cancel.setOnAction(e -> f.stage.close());
        Button ok = actionButton(I18n.t("common.ok"), true);
        ok.setDisable(field.getText().isBlank());
        ok.setOnAction(e -> {
            String text = field.getText() == null ? "" : field.getText().trim();
            if (!text.isEmpty()) {
                saved[0] = text;
                f.stage.close();
            }
        });
        field.textProperty().addListener((obs, old, now) -> ok.setDisable(now == null || now.isBlank()));
        // TextField 的 onAction 由 Enter 触发，与主输入框发送的手感一致
        field.setOnAction(e -> ok.fire());

        HBox actions = new HBox(8, cancel, ok);
        actions.getStyleClass().add("dialog-actions");
        f.body.getChildren().addAll(form, actions);
        prepare(f.stage);
        f.stage.showAndWait();
        return Optional.ofNullable(saved[0]);
    }

    /** 自定义内容对话框壳：无边框圆角窗口 + 标题栏，内容区由调用方填充。 */
    public static Shell shell(Window owner, String title) {
        return new Shell(frame(owner, title));
    }

    private static Button closeButton(Stage stage) {
        Button b = new Button();
        b.getStyleClass().addAll("win-btn", "win-close");
        Label icon = new Label(GLYPH_CLOSE);
        icon.getStyleClass().add("win-btn-icon");
        b.setGraphic(icon);
        b.setFocusTraversable(false);
        b.setOnAction(e -> stage.close());
        return b;
    }

    private static Button actionButton(String text, boolean primary) {
        Button b = new Button(text);
        if (primary) {
            b.getStyleClass().add("btn-primary");
        }
        b.setMinWidth(76);
        return b;
    }

    /** 标题栏空白区按住拖动移动窗口（按钮上的操作不受影响）。 */
    private static void installDrag(Stage stage, HBox bar) {
        double[] drag = new double[2];
        bar.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY && !(e.getTarget() instanceof Button)) {
                drag[0] = e.getScreenX() - stage.getX();
                drag[1] = e.getScreenY() - stage.getY();
            }
        });
        bar.setOnMouseDragged(e -> {
            if (e.getButton() == MouseButton.PRIMARY && !(e.getTarget() instanceof Button)) {
                stage.setX(e.getScreenX() - drag[0]);
                stage.setY(e.getScreenY() - drag[1]);
            }
        });
    }

    /** 显示后居中于 owner（无 owner 保持系统默认位置）。 */
    private static void centerOnOwner(Stage stage) {
        Window owner = stage.getOwner();
        if (owner == null) {
            return;
        }
        stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
        stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
    }
}
