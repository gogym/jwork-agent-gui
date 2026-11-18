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

import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.effect.BoxBlur;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 主窗口外壳：常驻的 {@code TRANSPARENT} 窗口骨架，顶部为自定义 {@link TitleBar}，
 * 中部为可切换的内容区（主界面 / 设置视图）。
 * <p>结构：外层 {@link StackPane} 留出透明边距承载投影，内层 {@link BorderPane} 用圆角
 * {@link Rectangle} 裁剪，使贴边的标题栏/侧栏也呈现四角圆角；最大化时去掉圆角与边距铺满屏幕。</p>
 * <p>中部再分两层：下层 {@code contentHolder} 放当前视图、上层 {@code frostVeil} 为弹窗期间的
 * 磨玻璃遮罩（模糊只作用于视图，标题栏保持清晰），开关由 {@link FrostedBackdrop} 在对话框弹出/关闭时驱动。</p>
 * <p>作为应用单例在 UI 各组件间共享：{@code JworkAgentApplication} 启动时 {@link #configure} 装配，
 * 设置视图通过 {@link #setContent} 替换中部，返回应用时 {@link #showApp} 还原，标题栏始终常驻。</p>
 *
 * @author gogym
 */
public class AppShell {

    /** 圆角半径（px），与 CSS 中 .window-shell 的 -fx-background-radius 保持一致。 */
    private static final double CORNER_RADIUS = 10;
    /** 磨玻璃模糊半径（px）与迭代次数：弹窗期间作用于内容视图，取值偏轻以保留背景轮廓。 */
    private static final double FROST_BLUR = 10;
    private static final int FROST_ITERATIONS = 2;

    private StackPane root;
    private BorderPane body;
    private Rectangle cornerClip;
    private Stage stage;
    private Parent appContent;
    /** 当前视图容器（磨玻璃模糊挂在这里）。 */
    private StackPane contentHolder;
    /** 磨玻璃遮罩层：盖在视图之上、标题栏之下，平时 opacity 为 0。 */
    private Region frostVeil;
    private BoxBlur frostBlur;
    /** 标题栏实例；configure 后不为空。 */
    private TitleBar titleBar;
    /** 主界面基线最小高度（首次 setContent 时捕获启动装配值，视图需求回落后还原）。 */
    private double baseMinHeight = -1;
    /** 早于 configure() 注册的标题栏回调，装配时一次性刷入。 */
    private final List<Consumer<TitleBar>> pendingTitleBarHooks = new ArrayList<>();

    /** 装配外壳：顶置自定义标题栏，中部放入主界面内容。必须在场景创建前调用。 */
    public void configure(Stage stage, Parent appContent) {
        this.stage = stage;
        this.appContent = appContent;

        this.body = new BorderPane();
        this.body.getStyleClass().add("window-shell");
        this.titleBar = new TitleBar(stage, this::onMaximizeChanged);
        this.body.setTop(titleBar.getNode());

        this.contentHolder = new StackPane(appContent);
        // 顶部锚定：视图最小高度超出可用区时只向下溢出（底部裁切），不向上盖住常驻标题栏
        this.contentHolder.setAlignment(Pos.TOP_LEFT);
        this.frostVeil = new Region();
        this.frostVeil.getStyleClass().add("frost-veil");
        // 常驻布局、只切 opacity：不可见的子节点可能被排版跳过，导致遮罩首次出现时尺寸不对
        this.frostVeil.setMouseTransparent(true);
        this.frostVeil.setOpacity(0);
        StackPane centerStack = new StackPane(contentHolder, frostVeil);
        centerStack.setAlignment(Pos.TOP_LEFT);
        this.body.setCenter(centerStack);

        // 圆角裁剪：贴边子节点（标题栏/侧栏）随容器一起呈现四角圆角
        this.cornerClip = new Rectangle();
        this.cornerClip.setArcWidth(CORNER_RADIUS * 2);
        this.cornerClip.setArcHeight(CORNER_RADIUS * 2);
        this.cornerClip.widthProperty().bind(body.widthProperty());
        this.cornerClip.heightProperty().bind(body.heightProperty());
        body.setClip(cornerClip);

        this.root = new StackPane();
        this.root.getStyleClass().add("window-root-shell");
        // 顶部锚定：body 最小高度（标题栏＋视图骨架）超出窗口时只向下溢出，
        // 居中会把整个 body 向上推、标题栏被推出窗外（设置视图曾因此「消失」）
        this.root.setAlignment(Pos.TOP_LEFT);
        this.root.getChildren().add(body);

        FrostedBackdrop.register(stage, this::applyFrost, this::clearFrost);

        // 刷入早于 configure() 注册的标题栏钩子
        if (!pendingTitleBarHooks.isEmpty()) {
            List<Consumer<TitleBar>> snapshot = new ArrayList<>(pendingTitleBarHooks);
            pendingTitleBarHooks.clear();
            snapshot.forEach(hook -> hook.accept(titleBar));
        }
    }

    /**
     * 标题栏就绪回调：{@code configure()} 之后立即执行；之前则入队等装配完成后一次性刷入。
     * <p>MainController 的 {@code @FXML initialize()} 早于 {@code configure()} 执行，需要这个机制才能将
     * 按钮插到标题栏上。</p>
     */
    public void onTitleBarReady(Consumer<TitleBar> hook) {
        if (hook == null) {
            return;
        }
        if (titleBar != null) {
            hook.accept(titleBar);
        } else {
            pendingTitleBarHooks.add(hook);
        }
    }

    public Parent getNode() {
        return root;
    }

    public Stage getStage() {
        return stage;
    }

    /** 替换中部内容（标题栏与遮罩层保持不变）。 */
    public void setContent(Parent content) {
        if (contentHolder != null && content != null) {
            contentHolder.getChildren().setAll(content);
            applyContentMinHeight();
        }
    }

    /**
     * 窗口最小高度随中部视图的骨架最小高度抬升（不超过所在屏幕可视高）：
     * 设置视图菜单骨架较高，不抬升时压矮窗口会让溢出内容盖住常驻标题栏；
     * 返回主界面后需求回落，基线最小高度自动还原。首次调用捕获基线（启动装配设定值）。
     */
    private void applyContentMinHeight() {
        if (baseMinHeight < 0) {
            baseMinHeight = stage.getMinHeight();
        }
        double need = root.minHeight(-1);
        double min = Math.min(Math.max(baseMinHeight, need), screenVisualHeight());
        stage.setMinHeight(min);
        // 当前高度不足时立即补足（setMinHeight 只约束后续拖拽）：打开高骨架视图即完整可见
        if (stage.getHeight() < min) {
            stage.setHeight(min);
        }
    }

    /** 窗口所在屏幕的可视区高度（多屏取窗口当前所在屏，取不到回退主屏）。 */
    private double screenVisualHeight() {
        List<Screen> screens = Screen.getScreensForRectangle(stage.getX(), stage.getY(),
                stage.getWidth(), stage.getHeight());
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
        return screen.getVisualBounds().getHeight();
    }

    /** 还原为主界面内容。 */
    public void showApp() {
        setContent(appContent);
    }

    /** 弹窗期间：内容视图高斯模糊并叠一层半透明 veil，形成磨玻璃背景（标题栏不参与）。 */
    private void applyFrost() {
        if (frostBlur == null) {
            frostBlur = new BoxBlur(FROST_BLUR, FROST_BLUR, FROST_ITERATIONS);
        }
        contentHolder.setEffect(frostBlur);
        frostVeil.setOpacity(1);
    }

    /** 弹窗关闭：撤除模糊与 veil，内容恢复清晰。 */
    private void clearFrost() {
        contentHolder.setEffect(null);
        frostVeil.setOpacity(0);
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
