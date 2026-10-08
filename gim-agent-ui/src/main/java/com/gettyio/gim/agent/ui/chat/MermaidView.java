package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.ui.UiTheme;
import javafx.concurrent.Worker;
import javafx.scene.Parent;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mermaid 图表视图：内嵌 {@link WebView} 加载打包进应用资源的 mermaid.min.js 离线渲染。
 * <p>HTML 骨架内联在本类中：{@code <pre class="mermaid">} 放图表源码，mermaid 初始化时
 * 自动渲染；渲染完成后 JS 经 {@code JavaBridge} 回调文档高度，把 WebView 的 prefHeight
 * 调到刚好包住图形（外层滚动由消息区的 ScrollPane 统一处理，图内不出现双层滚动条）。
 * 主题跟随 {@link UiTheme}：亮色 {@code default}、暗色 {@code dark}。</p>
 * <p>资源缺失或渲染引擎初始化失败时返回 {@code null}（调用方回退普通代码块渲染），
 * 保证没有打包 mermaid.min.js 的构建产物也能正常显示对话。</p>
 */
public class MermaidView {

    private static final Logger log = LoggerFactory.getLogger(MermaidView.class);

    /** 打包在资源目录里的 mermaid 离线脚本。 */
    public static final String MERMAID_JS = "/js/mermaid.min.js";

    private final WebView webView = new WebView();
    private final WebEngine engine = webView.getEngine();
    /** 渲染完成后由 JS 回调写入的文档高度（像素）。 */
    private volatile double docHeight = 80;

    private MermaidView(String code) {
        webView.setPrefSize(640, 120);
        webView.getStyleClass().add("md-mermaid");
        engine.getLoadWorker().stateProperty().addListener((obs, o, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                try {
                    JSObject bridge = (JSObject) engine.executeScript("window");
                    bridge.setMember("javaBridge", new JavaBridge());
                    engine.executeScript(
                            "new MutationObserver(function(){ window.javaBridge.reportHeight("
                                    + "document.body.scrollHeight); }).observe(document.body,"
                                    + "{childList:true,subtree:true,attributes:true});"
                                    + "window.javaBridge.reportHeight(document.body.scrollHeight);");
                } catch (Throwable t) {
                    log.warn("Mermaid 高度桥接失败，退回默认高度", t);
                }
            }
        });
        engine.loadContent(buildHtml(code), "text/html");
    }

    /** 尝试渲染；mermaid 资源不在 classpath 时返回 {@code null} 交由调用方回退。 */
    public static MermaidView tryCreate(String code) {
        if (MermaidView.class.getResource(MERMAID_JS) == null) {
            log.warn("缺少 {}，Mermaid 回退为代码块渲染", MERMAID_JS);
            return null;
        }
        return new MermaidView(code);
    }

    /** 渲染出的节点（WebView 已按内容高度自适应）。 */
    public Parent node() {
        return webView;
    }

    /** 组装内联 HTML：脚本从 classpath 取（resource URL），主题跟随应用。 */
    private static String buildHtml(String code) {
        boolean dark = UiTheme.mode() == UiTheme.Mode.DARK;
        String src = MermaidView.class.getResource(MERMAID_JS).toExternalForm();
        String escaped = code == null ? "" : code
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8">
                <style>
                  html,body { margin:0; padding:4px; background:transparent; overflow:hidden; }
                  .mermaid svg { max-width: 100%%; height: auto; }
                </style>
                <script src="%s"></script>
                </head><body>
                <pre class="mermaid">%s</pre>
                <script>
                  mermaid.initialize({ startOnLoad: true, theme: '%s', securityLevel: 'strict' });
                </script>
                </body></html>
                """.formatted(src, escaped, dark ? "dark" : "default");
    }

    /** JS→Java 高度回调：渲染/重排完成时把 WebView 高度对齐到文档实际高度。 */
    private class JavaBridge {
        public void reportHeight(double height) {
            if (height > 0 && height < 4096) {
                javafx.application.Platform.runLater(() -> {
                    docHeight = height + 8;
                    webView.setPrefHeight(docHeight);
                });
            }
        }
    }

    /** 仅测试用：当前文档高度。 */
    double currentDocHeight() {
        return docHeight;
    }
}
