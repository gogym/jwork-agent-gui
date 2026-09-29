package com.gettyio.gim.agent.ui;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Window;

/**
 * UI 主题中心：浅色/深色模式 + 字体大小档位。
 * <p>样式表组合 = 基础表 + 深色覆盖表（仅覆盖 -gim-* 语义色变量）
 * + 字号覆盖表；偏好经 {@link UserPrefs} 持久化到
 * {@code ~/.gim-agent/theme.properties}（与语言偏好同文件不同键），
 * 切换时对全部已打开窗口即时生效。</p>
 */
public final class UiTheme {

    public enum Mode { LIGHT, DARK }

    public enum FontSize { SMALL, MEDIUM, LARGE }

    private static final String BASE_CSS = "/css/gim-agent.css";
    private static final String DARK_CSS = "/css/gim-agent-dark.css";
    private static final String FONT_SMALL_CSS = "/css/gim-agent-font-small.css";
    private static final String FONT_LARGE_CSS = "/css/gim-agent-font-large.css";

    private static Mode mode = Mode.LIGHT;
    private static FontSize fontSize = FontSize.MEDIUM;
    private static boolean loaded;

    private UiTheme() {
    }

    public static synchronized Mode mode() {
        ensureLoaded();
        return mode;
    }

    public static synchronized FontSize fontSize() {
        ensureLoaded();
        return fontSize;
    }

    public static synchronized void setMode(Mode m) {
        ensureLoaded();
        if (m != mode) {
            mode = m;
            UserPrefs.put("mode", m.name());
            applyAll();
        }
    }

    public static synchronized void setFontSize(FontSize f) {
        ensureLoaded();
        if (f != fontSize) {
            fontSize = f;
            UserPrefs.put("font", f.name());
            applyAll();
        }
    }

    /** 为场景应用当前主题（重建样式表列表，天然幂等）。 */
    public static synchronized void apply(Scene scene) {
        ensureLoaded();
        scene.getStylesheets().setAll(url(BASE_CSS));
        if (mode == Mode.DARK) {
            scene.getStylesheets().add(url(DARK_CSS));
        }
        if (fontSize == FontSize.SMALL) {
            scene.getStylesheets().add(url(FONT_SMALL_CSS));
        } else if (fontSize == FontSize.LARGE) {
            scene.getStylesheets().add(url(FONT_LARGE_CSS));
        }
    }

    /** 对全部已打开窗口重新应用当前主题（模式/字号切换时调用）。 */
    public static void applyAll() {
        for (Window w : Window.getWindows()) {
            Scene s = w.getScene();
            if (s != null) {
                apply(s);
            }
        }
    }

    /** Segoe 图标字体 Label 工厂。 */
    public static Label icon(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("icon");
        return l;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            mode = Mode.valueOf(UserPrefs.get("mode", Mode.LIGHT.name()));
            fontSize = FontSize.valueOf(UserPrefs.get("font", FontSize.MEDIUM.name()));
        } catch (IllegalArgumentException e) {
            // 偏好值非法时回退默认主题，不影响启动
            mode = Mode.LIGHT;
            fontSize = FontSize.MEDIUM;
        }
    }

    private static String url(String path) {
        return UiTheme.class.getResource(path).toExternalForm();
    }
}
