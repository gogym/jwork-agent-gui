package com.gettyio.gim.agent.ui.jcef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * JCEF 页面资源装载辅助。
 *
 * <p>JCEF（Chromium）无法加载 {@code jar:} 子资源 URL，故把聊天页所需的 classpath 资源
 * （{@code chat.css / chat.js / marked.min.js / purify.min.js}）一次性提取到临时工作目录，
 * 再生成内联 HTML 骨架与 {@code window.javaBridge} 垫片（{@code bridge-shim.js}），
 * 返回指向 {@code index.html} 的 {@code file:///} URL 交 {@link JcefOsrView#loadUrl(String)} 装载。</p>
 *
 * <p>垫片把 {@code chat.js} 现有的 {@code bridge().onXxx(...)} 调用序列化为
 * {@code {fn,args}} JSON 并经 {@code CefMessageRouter} 注入的 {@code window.<queryFn>} 送达 Java，
 * 因此 {@code chat.js} 无需任何改动即可从 WebView 的 {@code JSObject.setMember} 迁移到 JCEF。</p>
 */
public final class JcefResources {

    private static final Logger log = LoggerFactory.getLogger(JcefResources.class);

    private static final String MARKED_JS = "/js/marked.min.js";
    private static final String PURIFY_JS = "/js/purify.min.js";
    private static final String CHAT_JS = "/web/chat.js";
    private static final String CHAT_CSS = "/web/chat.css";

    /** 提取并生成的聊天页 file:// URL（进程内只落地一次）。 */
    private static volatile String chatShellUrl;

    private JcefResources() {
    }

    /**
     * 生成聊天页并返回其 {@code file:///} URL。
     *
     * @param queryFn {@code CefMessageRouter} 注入 JS 的请求函数名（垫片据此调用）
     */
    public static synchronized String chatShell(String queryFn) throws IOException {
        if (chatShellUrl != null) {
            return chatShellUrl;
        }
        Path dir = workDir("chat");
        copy(CHAT_CSS, dir.resolve("chat.css"));
        copy(MARKED_JS, dir.resolve("marked.min.js"));
        copy(PURIFY_JS, dir.resolve("purify.min.js"));
        copy(CHAT_JS, dir.resolve("chat.js"));
        Files.writeString(dir.resolve("bridge-shim.js"), shim(queryFn), StandardCharsets.UTF_8);

        String html = """
                <!DOCTYPE html>
                <html lang="zh" data-theme="light" data-font="medium">
                <head>
                <meta charset="utf-8">
                <link rel="stylesheet" href="chat.css">
                </head>
                <body>
                <div id="list"></div>
                <script src="bridge-shim.js"></script>
                <script src="marked.min.js"></script>
                <script src="purify.min.js"></script>
                <script src="chat.js"></script>
                </body>
                </html>
                """;
        Files.writeString(dir.resolve("index.html"), html, StandardCharsets.UTF_8);
        String url = dir.resolve("index.html").toUri().toString();
        log.info("聊天 JCEF 页面已落地: {}", url);
        chatShellUrl = url;
        return url;
    }

    /**
     * 生成 {@code bridge-shim.js}：以 {@code window.javaBridge} 形态复刻旧 {@code JSObject} 桥的 10 个方法，
     * 每次调用把 {@code {fn,args}} 序列化为 JSON 经 {@code window.<queryFn>} 发往 Java。
     */
    private static String shim(String queryFn) {
        return """
                (function () {
                    'use strict';
                    var Q = '%s';
                    function send(fn, args) {
                        try {
                            if (typeof window[Q] === 'function') {
                                window[Q]({
                                    request: JSON.stringify({ fn: fn, args: args || [] }),
                                    onSuccess: function () {},
                                    onFailure: function () {}
                                });
                            }
                        } catch (e) { /* 桥未就绪时静默丢弃 */ }
                    }
                    window.javaBridge = {
                        onReady: function () { send('onReady', []); },
                        onAction: function (kind, id, raw) { send('onAction', [kind, id, raw]); },
                        onContext: function (id, x, y) { send('onContext', [id, x, y]); },
                        onScrollTop: function () { send('onScrollTop', []); },
                        onScrollState: function (atBottom, scrollable) { send('onScrollState', [atBottom, scrollable]); },
                        onLink: function (url) { send('onLink', [url]); },
                        onImage: function (path) { send('onImage', [path]); },
                        onCopyCode: function (text) { send('onCopyCode', [text]); },
                        onPreviewCode: function (code, lang) { send('onPreviewCode', [code, lang]); },
                        onEmpty: function (empty) { send('onEmpty', [empty]); }
                    };
                })();
                """.formatted(queryFn);
    }

    private static void copy(String classpath, Path out) throws IOException {
        try (InputStream in = JcefResources.class.getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IOException("缺少 classpath 资源: " + classpath);
            }
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path workDir(String name) throws IOException {
        Path base = Paths.get(System.getProperty("java.io.tmpdir", "."), "jwork", "cef-web", name);
        Files.createDirectories(base);
        return base;
    }
}
