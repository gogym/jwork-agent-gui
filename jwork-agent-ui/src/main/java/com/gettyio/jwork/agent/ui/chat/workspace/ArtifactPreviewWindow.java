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

package com.gettyio.jwork.agent.ui.chat.workspace;

import com.gettyio.jwork.agent.ui.i18n.I18n;
import com.gettyio.jwork.agent.ui.theme.UiTheme;
import com.gettyio.jwork.agent.ui.jcef.JcefOsrView;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Artifacts 预览窗口：把 HTML / SVG 用 JCEF(Chromium OSR) 在独立窗口渲染（单例复用）。
 * <p>两类入口：聊天里的代码块（{@link MarkdownRenderer} 的预览钮，内联内容按 data URL 装载）
 * 与本轮交付的本机产物文件（{@link #showFile}，按 {@code file://} 装载，页面相对资源随之可用）。
 * 非模态、可调整大小：重复预览时替换文档并前置，不打断聊天操作。工具栏「在浏览器打开」在文件态
 * 直接交原文件、内联态写临时文件，便于用开发者工具调试。HTML 系原样载入，SVG 包一层白底居中骨架。
 * 可预览类型清单只 {@link #PREVIEW_LANGS} 一份，前端预览钮经 {@code ChatUiLabels} 取同一份。本类不含业务状态。</p>
 *
 * @author gogym
 */
public final class ArtifactPreviewWindow {

    private static final Logger log = LoggerFactory.getLogger(ArtifactPreviewWindow.class);

    private static Stage stage;
    private static JcefOsrView view;
    private static Label titleLabel;
    /** 当前预览文档（内联内容，「在浏览器打开」写临时文件用）；随每次 show 替换。 */
    private static String currentHtml = "";
    /** 当前预览的本机文件；内联预览时为 {@code null}（两者互斥，切换入口必清另一侧）。 */
    private static Path currentFile;

    /**
     * 可预览类型白名单：语言标识与文件扩展名同集合（小写）。
     * <p>唯一事实源——{@link MarkdownRenderer} 的预览钮、{@link WorkspacePanel} 的阅读类型分派、
     * 前端 {@code chat.js} 的代码块预览钮（经 {@code ChatUiLabels.previewLangs} 下发）都读它。
     * 三处各写一份时，{@code xhtml} 在网页端没有按钮、{@code xml} 有按钮却装载不出视图。</p>
     */
    public static final List<String> PREVIEW_LANGS = List.of("html", "htm", "xhtml", "svg");

    /**
     * 产物预览目标钩子：默认走独立弹窗；主控制器启动时注入 {@link WorkspacePanel} 把产物导入右侧栏。
     * <p>解耦静态渲染器 {@link MarkdownRenderer} 与有状态面板：渲染器只调 {@link #preview}，
     * 由本类决定交给面板还是回退弹窗。</p>
     */
    public interface PreviewSink {
        void preview(Window owner, String code, String language);
    }

    private static PreviewSink previewSink;

    /** 注入预览目标（右侧栏面板）；传 null 恢复默认弹窗行为。 */
    public static void setPreviewSink(PreviewSink sink) {
        previewSink = sink;
    }

    /**
     * 预览入口：有面板钩子时导入右侧栏，否则回退独立弹窗。
     * <p>由 {@link MarkdownRenderer} 代码块预览按钮调用。</p>
     */
    public static void preview(Window owner, String code, String language) {
        PreviewSink sink = previewSink;
        if (sink != null) {
            sink.preview(owner, code, language);
        } else {
            show(owner, code, language);
        }
    }

    private ArtifactPreviewWindow() {
    }

    /** 该语言是否提供预览入口（大小写、首尾空白不敏感）：清单见 {@link #PREVIEW_LANGS}。{@link MarkdownRenderer}（渲染包）及单测共用，故为 public。 */
    public static boolean isPreviewable(String language) {
        return language != null && PREVIEW_LANGS.contains(language.trim().toLowerCase(Locale.ROOT));
    }

    /** 该文件按扩展名是否可预览（同 {@link #isPreviewable} 白名单）。{@link WorkspacePanel} 的阅读类型分派及单测共用，故为 public。 */
    public static boolean isPreviewableFile(String fileName) {
        return isPreviewable(extensionOf(fileName));
    }

    /** 末段扩展名（小写、不含点）；无扩展名、以点开头（{@code .gitignore}）或点收尾均返回空串。 */
    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 1 || dot == fileName.length() - 1
                ? ""
                : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 组装可预览文档：HTML 系原样；SVG 包一层白底居中骨架。包私有便于单测。 */
    static String buildHtml(String code, String language) {
        if (language != null && "svg".equalsIgnoreCase(language.trim())) {
            return """
                    <!DOCTYPE html>
                    <html><head><meta charset="utf-8"><style>
                    html,body{margin:0;padding:16px;min-height:100vh;box-sizing:border-box;
                    display:flex;align-items:center;justify-content:center;background:#fff;}
                    svg{max-width:100%%;max-height:100%%;}
                    </style></head><body>%s</body></html>
                    """.formatted(code);
        }
        return code;
    }

    /** 打开（或复用）预览窗口展示代码内容；{@code owner} 为主窗口，用于归属与图标继承。 */
    public static void show(Window owner, String code, String language) {
        if (code == null || code.isBlank()) {
            return;
        }
        currentHtml = buildHtml(code, language);
        currentFile = null;
        ensureWindow(owner);
        String lang = language == null ? "" : language.trim().toUpperCase(Locale.ROOT);
        titleLabel.setText(lang.isEmpty()
                ? I18n.t("chat.artifact.window")
                : I18n.t("chat.artifact.window") + " · " + lang);
        view.loadData(currentHtml);
        if (stage.isShowing()) {
            stage.toFront();
        } else {
            stage.show();
        }
    }

    /**
     * 打开（或复用）预览窗口展示本机产物文件；返回 {@code true} 表示类型可预览且已装载，
     * {@code false} 表示该类型不进预览窗口，由调用方另择去处（右侧栏就地阅读、系统默认程序）。
     * <p>走 {@code file://} 而非把内容读成字符串：报表常带同目录的 css/图片/数据文件，内联装载
     * 会让这些相对引用全断，页面只剩空壳。标题显示文件名——交付物叫什么，用户认的是名字。</p>
     */
    public static boolean showFile(Window owner, Path file) {
        String name = file == null || file.getFileName() == null ? "" : file.getFileName().toString();
        if (!isPreviewableFile(name)) {
            return false;
        }
        currentHtml = "";
        currentFile = file;
        ensureWindow(owner);
        titleLabel.setText(name);
        view.loadUrl(file.toUri().toString());
        if (stage.isShowing()) {
            stage.toFront();
        } else {
            stage.show();
        }
        return true;
    }

    /** 首次调用装配窗口骨架（单例复用）；此后仅替换内容。 */
    private static void ensureWindow(Window owner) {
        if (stage != null) {
            return;
        }
        view = new JcefOsrView("jworkQuery", "jworkCancel", null);

        titleLabel = new Label(I18n.t("chat.artifact.window"));
        titleLabel.getStyleClass().add("artifact-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button openBrowser = new Button(I18n.t("chat.artifact.openBrowser"));
        openBrowser.getStyleClass().add("artifact-tool");
        openBrowser.setFocusTraversable(false);
        openBrowser.setOnAction(e -> openInBrowser());
        Button close = new Button(I18n.t("chat.artifact.close"));
        close.getStyleClass().add("artifact-tool");
        close.setFocusTraversable(false);
        close.setOnAction(e -> stage.close());
        HBox toolbar = new HBox(titleLabel, spacer, openBrowser, close);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("artifact-toolbar");

        BorderPane root = new BorderPane(view.node());
        root.setTop(toolbar);
        root.getStyleClass().add("artifact-root");

        Scene scene = new Scene(root, 960, 720);
        UiTheme.apply(scene);
        stage = new Stage();
        stage.setTitle(I18n.t("chat.artifact.window"));
        stage.setMinWidth(480);
        stage.setMinHeight(360);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setScene(scene);
    }

    /** 交系统默认浏览器打开当前预览：文件态用原文件（相对资源随之可用），内联态写临时文件。 */
    private static void openInBrowser() {
        try {
            Path file = currentFile;
            if (file == null) {
                if (currentHtml.isEmpty()) {
                    return;
                }
                file = Files.createTempFile("jwork-artifact-", ".html");
                Files.writeString(file, currentHtml, StandardCharsets.UTF_8);
                file.toFile().deleteOnExit();
            }
            Desktop.getDesktop().browse(file.toUri());
        } catch (Exception e) {
            log.warn("预览在浏览器打开失败", e);
        }
    }
}
