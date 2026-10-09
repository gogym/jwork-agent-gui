package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import com.gettyio.gim.agent.ui.jcef.JcefOsrView;
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
import java.util.Locale;

/**
 * Artifacts 预览窗口：把聊天中 HTML / SVG 代码块用 JCEF(Chromium OSR) 在独立窗口渲染（单例复用）。
 * <p>非模态、可调整大小：重复预览时替换文档并前置，不打断聊天操作。工具栏提供「在浏览器打开」
 * （内容写临时文件交系统默认浏览器，便于用开发者工具调试）。由 {@link MarkdownRenderer}
 * 代码块卡片的预览按钮触发；HTML 系原样载入，SVG 包一层白底居中骨架。本类不含业务状态。</p>
 */
public final class ArtifactPreviewWindow {

    private static final Logger log = LoggerFactory.getLogger(ArtifactPreviewWindow.class);

    private static Stage stage;
    private static JcefOsrView view;
    private static Label titleLabel;
    /** 当前预览文档（「在浏览器打开」用）；随每次 show 替换。 */
    private static String currentHtml = "";

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

    /** 该语言是否提供预览入口：HTML 系与 SVG（大小写不敏感）。包私有便于单测。 */
    static boolean isPreviewable(String language) {
        if (language == null || language.isBlank()) {
            return false;
        }
        String lang = language.trim().toLowerCase(Locale.ROOT);
        return lang.equals("html") || lang.equals("htm") || lang.equals("xhtml") || lang.equals("svg");
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

    /** 首次调用装配窗口骨架（单例复用）；此后仅替换内容。 */
    private static void ensureWindow(Window owner) {
        if (stage != null) {
            return;
        }
        view = new JcefOsrView("gimQuery", "gimCancel", null);

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

    /** 把当前文档写临时文件并交系统默认浏览器打开（便于用开发者工具调试）。 */
    private static void openInBrowser() {
        if (currentHtml.isEmpty()) {
            return;
        }
        try {
            Path file = Files.createTempFile("jwork-artifact-", ".html");
            Files.writeString(file, currentHtml, StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            Desktop.getDesktop().browse(file.toUri());
        } catch (Exception e) {
            log.warn("预览在浏览器打开失败", e);
        }
    }
}
