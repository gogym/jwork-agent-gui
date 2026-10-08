package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UserPrefs;
import com.gettyio.gim.agent.ui.window.AppShell;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * 侧栏收起/展开协作类：标题栏开关按钮的构造与挂载、宽度过渡动画、收起时对内容区左侧留白的补偿，
 * 状态持久化到用户偏好。
 * <p>抽自 {@code MainController}：控制器在 FXML 注入后交出侧栏两个外层容器与 {@link AppShell}，
 * 收起标志、动画句柄、开关按钮及其文案全归本类持有；语言切换时由控制器回调 {@link #refreshLabel()}。</p>
 */
public final class SidebarToggle {

    /** 侧栏展开时整体宽度：VBox 固定 250 + sidebar-margin 左边距 12（伸缩动画的满量程） */
    private static final double SIDEBAR_FULL_WIDTH = 262;
    /** 侧栏伸缩动画时长（毫秒） */
    private static final double SIDEBAR_ANIM_MS = 220;
    /** 侧栏偏好键：记住侧栏是否收起 */
    private static final String PREF_SIDEBAR_COLLAPSED = "sidebar.collapsed";

    private final StackPane sidebarMargin;
    private final StackPane contentMargin;
    private final AppShell appShell;

    /** 左侧菜单栏是否收起 */
    private boolean collapsed;
    /** 侧栏伸缩动画（复用：切换时若上一次还在跑先停下，避免叠加） */
    private Timeline anim;
    /** 标题栏上侧栏开关按钮：构造后通过 AppShell.onTitleBarReady 挂入标题栏 */
    private Button toggleButton;

    public SidebarToggle(StackPane sidebarMargin, StackPane contentMargin, AppShell appShell) {
        this.sidebarMargin = sidebarMargin;
        this.contentMargin = contentMargin;
        this.appShell = appShell;
    }

    /** 启动时调用：恢复上次的收起状态（不走动画）并把开关按钮挂到标题栏。 */
    public void restore() {
        collapsed = "true".equals(UserPrefs.get(PREF_SIDEBAR_COLLAPSED, "false"));
        applyState();
        installToggleButton();
    }

    /** 展开/收起切换（开关按钮触发）：记偏好、补内容区留白、跑动画。 */
    public void toggle() {
        collapsed = !collapsed;
        UserPrefs.put(PREF_SIDEBAR_COLLAPSED, String.valueOf(collapsed));
        applyContentMargin();
        animate(!collapsed);
    }

    /** 刷新开关按钮的 tooltip 文案（语言切换时由控制器回调）。 */
    public void refreshLabel() {
        if (toggleButton == null) {
            return;
        }
        if (toggleButton.getTooltip() == null) {
            toggleButton.setTooltip(new Tooltip());
        }
        toggleButton.getTooltip().setText(I18n.t("chat.sidebar.toggle"));
    }

    /** 根据当前 collapsed 标志瞬时应用侧栏宽度（启动恢复用，不走动画） */
    private void applyState() {
        if (sidebarMargin == null) {
            return;
        }
        sidebarMargin.setMaxWidth(collapsed ? 0 : SIDEBAR_FULL_WIDTH);
        sidebarMargin.setMouseTransparent(collapsed);
        applyContentMargin();
    }

    /**
     * 侧栏收起时给内容区补回左侧留白（与右侧 content-margin 的 4 对称），展开时去掉。
     * <p>展开态侧栏紧贴内容区、由 sidebar-margin 负责左沿留白；收起后 maxWidth 归 0 会连那 4 一起没了，
     * 故此处把留白责任临时交给内容区自身。</p>
     */
    private void applyContentMargin() {
        if (contentMargin == null) {
            return;
        }
        contentMargin.getStyleClass().remove("no-sidebar");
        if (collapsed) {
            contentMargin.getStyleClass().add("no-sidebar");
        }
    }

    /** 启动侧栏伸缩动画：maxWidth 在 0 ↔ 满量程间缓动；收起后把容器设为鼠标透明 */
    private void animate(boolean expand) {
        if (sidebarMargin == null) {
            return;
        }
        if (anim != null && anim.getStatus() == Timeline.Status.RUNNING) {
            anim.stop();
        }
        sidebarMargin.setMouseTransparent(false);
        double to = expand ? SIDEBAR_FULL_WIDTH : 0;
        anim = new Timeline(new KeyFrame(
                Duration.millis(SIDEBAR_ANIM_MS),
                new KeyValue(sidebarMargin.maxWidthProperty(), to, Interpolator.EASE_BOTH)));
        anim.setOnFinished(e -> sidebarMargin.setMouseTransparent(!expand));
        anim.play();
    }

    /** 构造侧栏开关按钮，挂到标题栏右侧（标题文字后、弹性 spacer 前） */
    private void installToggleButton() {
        if (toggleButton != null) {
            return;
        }
        Button btn = new Button();
        btn.setGraphic(buildIcon());
        btn.getStyleClass().add("sidebar-toggle");
        btn.setFocusTraversable(false);
        btn.setOnAction(e -> toggle());
        toggleButton = btn;
        appShell.onTitleBarReady(bar -> bar.addAfterBrand(btn));
    }

    /** 绘制侧栏开关图标：圆角矩形外框 + 左侧竖线分隔（匹配 Fluent “PanelLeft” 风格）。
     *  <p>容器高度与按钮内容区（标题栏 38px）一致，形状画在容器垂直正中，
     *  规避 Button 对无基线 graphic 顶部对齐导致的图标偏上问题。</p> */
    private static Node buildIcon() {
        double paneH = 38;
        double iconW = 14;
        double iconH = 12;
        double top = (paneH - iconH) / 2;
        double left = 1;

        Rectangle frame = new Rectangle(left, top, iconW, iconH);
        frame.setArcWidth(4);
        frame.setArcHeight(4);
        frame.setFill(null);
        frame.setStrokeWidth(1.4);
        frame.getStyleClass().add("sidebar-icon-stroke");

        Line divider = new Line(left + 4.5, top, left + 4.5, top + iconH);
        divider.setStrokeWidth(1.4);
        divider.getStyleClass().add("sidebar-icon-stroke");

        Pane icon = new Pane(frame, divider);
        icon.setPrefSize(16, paneH);
        icon.setMinSize(16, paneH);
        return icon;
    }
}
