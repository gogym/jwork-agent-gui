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

package com.gettyio.jwork.agent.ui.theme;

import com.gettyio.jwork.agent.ui.prefs.UserPrefs;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * UI 主题中心：浅色/深色模式 + 字体大小档位。
 * <p>样式表组合 = 基础表 + 深色覆盖表（仅覆盖 -jwork-* 语义色变量）
 * + 字号覆盖表；偏好经 {@link UserPrefs} 持久化到
 * {@code ~/.jwork/theme.properties}（与语言偏好同文件不同键），
 * 切换时对全部已打开窗口即时生效。</p>
 *
 * @author gogym
 */
public final class UiTheme {

    public enum Mode { LIGHT, DARK }

    public enum FontSize { SMALL, MEDIUM, LARGE }

    private static final String BASE_CSS = "/css/jwork-agent.css";
    private static final String DARK_CSS = "/css/jwork-agent-dark.css";
    private static final String FONT_SMALL_CSS = "/css/jwork-agent-font-small.css";
    private static final String FONT_LARGE_CSS = "/css/jwork-agent-font-large.css";

    private static Mode mode = Mode.LIGHT;
    private static FontSize fontSize = FontSize.MEDIUM;
    private static boolean loaded;
    private static boolean chatFontLoaded;
    /** 主题/字号变更监听器：WebView 渲染层（chat.css 无法直接吃 JavaFX token）据此同步 data-theme/data-font。 */
    private static final List<Runnable> THEME_LISTENERS = new CopyOnWriteArrayList<>();

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

    /** 切换主题模式：持久化偏好并重刷全部窗口样式，随后通知渲染层。 */
    public static synchronized void setMode(Mode m) {
        ensureLoaded();
        if (m != mode) {
            mode = m;
            UserPrefs.put("mode", m.name());
            applyAll();
            notifyTheme();
        }
    }

    /** 切换字号档位：持久化偏好并重刷全部窗口样式，随后通知渲染层。 */
    public static synchronized void setFontSize(FontSize f) {
        ensureLoaded();
        if (f != fontSize) {
            fontSize = f;
            UserPrefs.put("font", f.name());
            applyAll();
            notifyTheme();
        }
    }

    /** 注册主题/字号变更监听（弱语义：随进程存活）；注册方应立即自行同步一次当前值。 */
    public static void addThemeListener(Runnable listener) {
        THEME_LISTENERS.add(listener);
    }

    private static void notifyTheme() {
        for (Runnable r : THEME_LISTENERS) {
            r.run();
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
     * 应用徽标（绿底圆角方块 + 白 J）：按显示尺寸选择资源档位——
     * 256 档缩到 20px 会糊边，标题栏等小尺寸位改吃 32 档小图。
     */
    public static ImageView brandBadge(double size) {
        String resource = size <= 24 ? "/icons/app-icon-32.png"
                : size <= 72 ? "/icons/app-icon-128.png" : "/icons/app-icon-256.png";
        return brandImage(resource, size);
    }

    /** 独立图形标（绿 J + 星芒，透明底）：登录门品牌区/关于页/闪屏等品牌展示位。 */
    public static ImageView brandMark(double size) {
        return brandImage("/icons/brand-mark.png", size);
    }

    /** 为窗口设定任务栏与 Alt+Tab 图标（256 档应用徽标）。 */
    public static void applyAppIcon(Stage stage) {
        stage.getIcons().add(new Image(UiTheme.class.getResourceAsStream("/icons/app-icon-256.png")));
    }

    /** 从 classpath 读取品牌图片并等比缩放到给定边长（平滑插值）。 */
    private static ImageView brandImage(String resource, double size) {
        ImageView view = new ImageView(new Image(UiTheme.class.getResourceAsStream(resource)));
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
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
