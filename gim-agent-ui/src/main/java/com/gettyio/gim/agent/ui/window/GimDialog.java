package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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

/**
 * 统一无边框对话框：与主窗口同款圆角壳（透明 Stage + 投影 + 圆角裁剪）+
 * 自绘标题栏（标题文本 + 关闭按钮，可拖动），替代系统装饰的 Alert / 裸 Stage。
 * <p>用法：{@link #message}/{@link #confirm}/{@link #choose} 覆盖消息与确认场景；
 * {@link #shell} 返回带空内容区的壳，调用方往 {@link Shell#body()} 塞任意表单内容。
 * 有 owner 时窗口模态并居中于 owner，无 owner 时应用级模态。</p>
 */
public final class GimDialog {

    /** 圆角半径（px），与 CSS 中 .window-shell 的 -fx-background-radius 保持一致。 */
    private static final double CORNER_RADIUS = 10;
    private static final String GLYPH_CLOSE = "\uE8BB";

    private GimDialog() {
    }

    /** 信息提示：单个「确定」按钮。 */
    public static void message(Window owner, String title, String header, String message) {
        Frame f = messageFrame(owner, title, header, message);
        HBox actions = new HBox(8);
        actions.getStyleClass().add("dialog-actions");
        Button ok = actionButton(I18n.t("common.ok"), true);
        ok.setOnAction(e -> f.stage.close());
        actions.getChildren().add(ok);
        f.body.getChildren().add(actions);
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
        f.stage.showAndWait();
        return result[0];
    }

    /** 自定义内容对话框壳：无边框圆角窗口 + 标题栏，内容区由调用方填充。 */
    public static Shell shell(Window owner, String title) {
        return new Shell(frame(owner, title));
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
            frame.stage.sizeToScene();
            frame.stage.show();
        }

        public void showAndWait() {
            frame.stage.sizeToScene();
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
        Frame f = frame(owner, title);
        if (header != null && !header.isBlank()) {
            Label h = new Label(header);
            h.getStyleClass().add("dialog-header");
            h.setWrapText(true);
            f.body.getChildren().add(h);
        }
        Label m = new Label(message == null ? "" : message);
        m.getStyleClass().add("dialog-message");
        m.setWrapText(true);
        m.setMaxWidth(460);
        f.body.getChildren().add(m);
        return f;
    }

    /** 无边框窗口骨架：透明 Stage + 圆角壳 + 自绘标题栏（标题 + 关闭，可拖动）。 */
    private static Frame frame(Window owner, String title) {
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
        bar.getChildren().addAll(titleText, spacer, closeButton(stage));
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
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE), stage::close);
        stage.setScene(scene);
        stage.setOnShown(e -> centerOnOwner(stage));
        return new Frame(stage, body);
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
