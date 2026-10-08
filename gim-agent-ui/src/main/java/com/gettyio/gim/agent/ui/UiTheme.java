package com.gettyio.gim.agent.ui;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.text.Font;
import javafx.stage.Window;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * UI 主题中心：浅色/深色模式 + 字体大小档位。
 * <p>样式表组合 = 基础表 + 深色覆盖表（仅覆盖 -gim-* 语义色变量）
 * + 字号覆盖表；偏好经 {@link UserPrefs} 持久化到
 * {@code ~/.jwork/theme.properties}（与语言偏好同文件不同键），
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
    private static boolean chatFontLoaded;

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

    /**
     * 聊天区字体（Noto Sans SC）运行时注册。
     * <p>随包分发两个静态字重 {@code /fonts/NotoSansSC-Regular.ttf} 与
     * {@code /fonts/NotoSansSC-Bold.ttf}（classpath 资源，OFL 许可见同目录 LICENSE-OFL.txt）：
     * 不同机器的系统字体差异大，随包分发保证各机器观感一致。必须是静态字重而非可变字体（VF）
     * ——JavaFX 字体引擎不支持 VF 字重轴：VF 注册后族内只有 Regular 单实例且不做合成粗体，
     * 加粗/标题字重会全部静默失效（实测 bold 与 regular 解析到同一实例）；两个静态文件同族
     * 不同 usWeightClass，CSS 的 {@code -fx-font-weight: bold} 才能命中真实 Bold。
     * 注意 JavaFX 字体枚举看不到未激活的自注册字体、字体族只认列表首项、不做逐项回退，
     * 必须此处 {@link Font#loadFont} 显式注册；资源缺失时回退本机安装路径，全部不可用时
     * 静默跳过，聊天区自然回退系统字体，不阻断启动。</p>
     */
    public static synchronized void ensureChatFont() {
        if (chatFontLoaded) {
            return;
        }
        chatFontLoaded = true;
        // 随包字体优先：两个字重逐一注册，任一成功即认定可用（缺 Bold 只影响粗体呈现）
        if (loadBundled("/fonts/NotoSansSC-Regular.ttf") | loadBundled("/fonts/NotoSansSC-Bold.ttf")) {
            return;
        }
        String windir = System.getenv("WINDIR");
        String local = System.getenv("LOCALAPPDATA");
        String[] candidates = {
                file(windir, "Fonts\\NotoSansSC-Regular.ttf"),
                file(local, "Microsoft\\Windows\\Fonts\\NotoSansSC-Regular.ttf"),
                file(windir, "Fonts\\NotoSansSC-VF.ttf"),
                file(local, "Microsoft\\Windows\\Fonts\\NotoSansSC-VF.ttf"),
        };
        for (String path : candidates) {
            if (path == null) {
                continue;
            }
            try (InputStream in = Files.newInputStream(Path.of(path))) {
                if (Font.loadFont(in, 13) != null) {
                    return;
                }
            } catch (IOException ignored) {
                // 该候选路径没有字体文件：试下一个
            }
        }
    }

    /** 从 classpath 注册随包字体资源：成功返回 true，资源缺失或不可读返回 false。 */
    private static boolean loadBundled(String resource) {
        try (InputStream in = UiTheme.class.getResourceAsStream(resource)) {
            return in != null && Font.loadFont(in, 13) != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String file(String dir, String relative) {
        return dir == null ? null : dir + "\\" + relative;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        ensureChatFont();
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
