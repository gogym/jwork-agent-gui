package com.gettyio.gim.agent.ui.window;

import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import org.springframework.stereotype.Component;

/**
 * 主窗口外壳：常驻的 {@code TRANSPARENT} 窗口骨架，顶部为自定义 {@link TitleBar}，
 * 中部为可切换的内容区（主界面 / 设置视图）。
 * <p>结构：外层 {@link StackPane} 留出透明边距承载投影，内层 {@link BorderPane} 用圆角
 * {@link Rectangle} 裁剪，使贴边的标题栏/侧栏也呈现四角圆角；最大化时去掉圆角与边距铺满屏幕。</p>
 * <p>作为 Spring 单例在 UI 各组件间共享：{@code GimAgentApplication} 启动时 {@link #configure} 装配，
 * 设置视图通过 {@link #setContent} 替换中部，返回应用时 {@link #showApp} 还原，标题栏始终常驻。</p>
 */
@Component
public class AppShell {

    /** 圆角半径（px），与 CSS 中 .window-shell 的 -fx-background-radius 保持一致。 */
    private static final double CORNER_RADIUS = 10;

    private StackPane root;
    private BorderPane body;
    private Rectangle cornerClip;
    private Stage stage;
    private Parent appContent;

    /** 装配外壳：顶置自定义标题栏，中部放入主界面内容。必须在场景创建前调用。 */
    public void configure(Stage stage, Parent appContent) {
        this.stage = stage;
        this.appContent = appContent;

        this.body = new BorderPane();
        this.body.getStyleClass().add("window-shell");
        this.body.setTop(new TitleBar(stage, this::onMaximizeChanged).getNode());
        this.body.setCenter(appContent);

        // 圆角裁剪：贴边子节点（标题栏/侧栏）随容器一起呈现四角圆角
        this.cornerClip = new Rectangle();
        this.cornerClip.setArcWidth(CORNER_RADIUS * 2);
        this.cornerClip.setArcHeight(CORNER_RADIUS * 2);
        this.cornerClip.widthProperty().bind(body.widthProperty());
        this.cornerClip.heightProperty().bind(body.heightProperty());
        body.setClip(cornerClip);

        this.root = new StackPane();
        this.root.getStyleClass().add("window-root-shell");
        this.root.getChildren().add(body);
    }

    public Parent getNode() {
        return root;
    }

    public Stage getStage() {
        return stage;
    }

    /** 替换中部内容（标题栏保持不变）。 */
    public void setContent(Parent content) {
        if (body != null && content != null) {
            body.setCenter(content);
        }
    }

    /** 还原为主界面内容。 */
    public void showApp() {
        setContent(appContent);
    }

    /** 最大化态切换：加/去透明边距与圆角裁剪，使窗口还原时恢复圆角。 */
    private void onMaximizeChanged(boolean maximized) {
        if (maximized) {
            body.setClip(null);
            root.getStyleClass().add("maximized");
        } else {
            body.setClip(cornerClip);
            root.getStyleClass().remove("maximized");
        }
    }
}
