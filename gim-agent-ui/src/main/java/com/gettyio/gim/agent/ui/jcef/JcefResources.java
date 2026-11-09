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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JCEF 页面资源装载辅助。
 *
 * <p>JCEF（Chromium）无法加载 {@code jar:} 子资源 URL，且 CEF 默认禁止 {@code file://} 页面装载
 * {@code file://} 子资源（{@code file_access_from_file_urls=false}，外链脚本会静默不执行）。故把
 * {@code chat.css / chat.js / marked.min.js / purify.min.js} 与 {@code window.javaBridge} 垫片全部
 * <b>内联</b>进单个 HTML，返回其 {@code file:///} URL 交 {@link JcefOsrView#loadUrl(String)} 装载。</p>
 *
 * <p>垫片把 {@code chat.js} 现有的 {@code bridge().onXxx(...)} 调用序列化为
 * {@code {fn,args}} JSON 并经 {@code CefMessageRouter} 注入的 {@code window.<queryFn>} 送达 Java，
 * 因此 {@code chat.js} 无需任何改动即可从 WebView 的 {@code JSObject.setMember} 迁移到 JCEF。</p>
 *
 * @author gogym
 */
public final class JcefResources {

    private static final Logger log = LoggerFactory.getLogger(JcefResources.class);

    private static final String MARKED_JS = "/js/marked.min.js";
    private static final String PURIFY_JS = "/js/purify.min.js";
    private static final String CHAT_JS = "/web/chat.js";
    private static final String CHAT_CSS = "/web/chat.css";
    private static final String MERMAID_JS = "/js/mermaid.min.js";
    private static final String COMPOSER_JS = "/web/composer.js";
    private static final String COMPOSER_CSS = "/web/composer.css";

    /** 提取并生成的聊天页 file:// URL（进程内只落地一次）。 */
    private static volatile String chatShellUrl;
    /** 提取并生成的输入框页 file:// URL（进程内只落地一次）。 */
    private static volatile String composerShellUrl;
    /** Mermaid 图表页序号：同一目录内每次生成唯一页面名，供多个 {@code CefBrowser} 并发加载。 */
    private static final AtomicInteger MERMAID_SEQ = new AtomicInteger();

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
        // 全量内联为单文件页面：CEF 默认禁止 file:// 页面装载 file:// 子资源
        // （CefSettings.file_access_from_file_urls=false），外链脚本会静默不执行——
        // 表现为 window.ChatAPI/javaBridge 未定义、聊天区永远空白。
        String html = """
                <!DOCTYPE html>
                <html lang="zh" data-theme="light" data-font="medium">
                <head>
                <meta charset="utf-8">
                <style>
                %s
                </style>
                </head>
                <body>
                <div id="list"></div>
                <script>
                window.onerror = function (m, s, l, c, e) {
                    try { console.error('GIM_PAGE_ERROR: ' + m + ' @' + s + ':' + l); } catch (x) {}
                };
                </script>
                <script>
                %s
                </script>
                <script>
                %s
                </script>
                <script>
                %s
                </script>
                <script>
                %s
                </script>
                <script>
                console.log('GIM_BOOT bridge=' + typeof window.javaBridge + ' ChatAPI=' + typeof window.ChatAPI
                        + ' marked=' + typeof window.marked + ' purify=' + typeof window.DOMPurify + ' url=' + location.href);
                </script>
                </body>
                </html>
                """.formatted(readText(CHAT_CSS), shim(queryFn), readText(MARKED_JS),
                readText(PURIFY_JS), readText(CHAT_JS));
        Files.writeString(dir.resolve("index.html"), html, StandardCharsets.UTF_8);
        // Mermaid 图表与 index.html 同目录落地（同目录相对引用属同源，不受 file:// 跨目录限制）：
        // 2.7MB 的库不内联进页面，聊天页首次真出现 ```mermaid 才按需装载。缺资源不影响启动，
        // 前端装载失败即把图表块留作代码块（与旧行为一致）。
        try {
            Path mermaid = dir.resolve("mermaid.min.js");
            if (!Files.exists(mermaid)) {
                copy(MERMAID_JS, mermaid);
            }
        } catch (IOException e) {
            log.warn("mermaid.min.js 未落地聊天目录，图表按代码块显示", e);
        }
        String url = dir.resolve("index.html").toUri().toString();
        log.info("聊天 JCEF 页面已落地（CSS/JS 全内联）: {}", url);
        chatShellUrl = url;
        return url;
    }

    /**
     * 生成输入框页并返回其 {@code file:///} URL（CSS/JS 全内联，单文件页面）。
     *
     * <p>页面只有一个 {@code contenteditable} 编辑区：字符插入 / IME 组合渲染 / 状态上报全部由
     * {@code composer.js} 承载，经 {@code CefMessageRouter} 注入的 {@code window.<queryFn>} 与
     * Java 侧 {@code JcefComposer} 通信（无 javaBridge 垫片，JS 直调注入函数）。</p>
     *
     * @param queryFn {@code CefMessageRouter} 注入 JS 的请求函数名
     */
    public static synchronized String composerShell(String queryFn) throws IOException {
        if (composerShellUrl != null) {
            return composerShellUrl;
        }
        Path dir = workDir("composer");
        // 全量内联为单文件页面：CEF 默认禁止 file:// 页面装载 file:// 子资源（同 chatShell）
        String html = """
                <!DOCTYPE html>
                <html lang="zh" data-theme="light">
                <head>
                <meta charset="utf-8">
                <style>
                %s
                </style>
                </head>
                <body>
                <div id="editor" contenteditable="true" spellcheck="false"></div>
                <script>
                window.onerror = function (m, s, l, c, e) {
                    try { console.error('GIM_COMPOSER_PAGE_ERROR: ' + m + ' @' + s + ':' + l); } catch (x) {}
                };
                console.log('GIM_COMPOSER_QUERY=' + typeof window['%s']);
                </script>
                <script>
                %s
                </script>
                </body>
                </html>
                """.formatted(readText(COMPOSER_CSS), queryFn, readText(COMPOSER_JS));
        Files.writeString(dir.resolve("index.html"), html, StandardCharsets.UTF_8);
        String url = dir.resolve("index.html").toUri().toString();
        log.info("输入框 JCEF 页面已落地（CSS/JS 全内联）: {}", url);
        composerShellUrl = url;
        return url;
    }

    /**
     * 生成 {@code bridge-shim.js}：以 {@code window.javaBridge} 形态复刻旧 {@code JSObject} 桥的 13 个方法，
     * 每次调用把 {@code {fn,args}} 序列化为 JSON 经 {@code window.<queryFn>} 发往 Java。
     *
     * <p>JSON 在发送前把非 ASCII 字符转义为 <code>&#92;uXXXX</code>（与 Java 侧 {@code executeScript}
     * 的 {@code asciiEscape} 对称）：JNI 的 {@code NewStringUTF} 按 Modified UTF-8 解码，astral emoji
     * 会被误解码为乱码字节甚至截断整串——如 {@code onAction} 的 raw 消息原文、{@code onCopyCode}
     * 的代码文本含 emoji 时，操作会带乱码数据或整条事件被丢弃。</p>
     */
    private static String shim(String queryFn) {
        return """
                (function () {
                    'use strict';
                    var Q = '%s';
                    /* JSON 文本 ASCII 化：JS→Java 的 JNI 桥（NewStringUTF）承载不了标准 UTF-8 的
                       四字节序列（astral emoji 会乱码甚至截断整串），非 ASCII 全转反斜杠-uXXXX
                       （JSON 合法转义，Java 侧 Jackson 解析时还原），与 Java→JS 的 asciiEscape 对称。 */
                    function asciiJson(s) {
                        return s.replace(/[^\\x20-\\x7e]/g, function (ch) {
                            return '\\\\u' + ('0000' + ch.charCodeAt(0).toString(16)).slice(-4);
                        });
                    }
                    function send(fn, args) {
                        try {
                            if (typeof window[Q] === 'function') {
                                window[Q]({
                                    request: asciiJson(JSON.stringify({ fn: fn, args: args || [] })),
                                    onSuccess: function () {},
                                    onFailure: function () {}
                                });
                            } else {
                                console.error('GIM_BRIDGE_MISSING: window.' + Q + ' 未注入（CefMessageRouter 未生效） fn=' + fn);
                            }
                        } catch (e) {
                            console.error('GIM_BRIDGE_ERROR: ' + e + ' fn=' + fn);
                        }
                    }
                    window.javaBridge = {
                        onReady: function () { send('onReady', []); },
                        onAction: function (kind, id, raw) { send('onAction', [kind, id, raw]); },
                        onContext: function (id, x, y) { send('onContext', [id, x, y]); },
                        onScrollTop: function () { send('onScrollTop', []); },
                        onScrollState: function (atBottom, scrollable) { send('onScrollState', [atBottom, scrollable]); },
                        onTrimmed: function (id) { send('onTrimmed', [id]); },
                        onLink: function (url) { send('onLink', [url]); },
                        onImage: function (path) { send('onImage', [path]); },
                        onArtifact: function (path, action) { send('onArtifact', [path, action]); },
                        onCopyCode: function (text) { send('onCopyCode', [text]); },
                        onPreviewCode: function (code, lang) { send('onPreviewCode', [code, lang]); },
                        onEmpty: function (empty) { send('onEmpty', [empty]); },
                        onDismissMenu: function () { send('onDismissMenu', []); },
                        onSelection: function (has) { send('onSelection', [!!has]); }
                    };
                })();
                """.formatted(queryFn);
    }

    /**
     * 生成 Mermaid 图表页并返回其 {@code file:///} URL。
     * <p>把 {@code mermaid.min.js} 提取到共享目录（仅首次），图表 HTML 写入<b>同一目录</b>下的唯一文件名，
     * 从而页面内相对引用 {@code <script src="mermaid.min.js">} 为同源，不受 Chromium 对 {@code file://}
     * 跨目录访问的安全限制。页面内联 {@code reportHeight} 垫片，渲染/重排后经 {@code window.<queryFn>}
     * 上报文档高度。</p>
     *
     * @param code    Mermaid 图表源码
     * @param dark    是否暗色主题（决定 mermaid 初始化主题）
     * @param queryFn 与 {@link JcefOsrView} 一致的请求函数名
     */
    public static synchronized String mermaidShell(String code, boolean dark, String queryFn) throws IOException {
        Path dir = workDir("mermaid");
        Path js = dir.resolve("mermaid.min.js");
        if (!Files.exists(js)) {
            copy(MERMAID_JS, js);
        }
        String escaped = code == null ? "" : code
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        String html = """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8">
                <style>
                  html,body { margin:0; padding:4px; background:transparent; overflow:hidden; }
                  .mermaid svg { max-width: 100%%; height: auto; }
                </style>
                </head><body>
                <pre class="mermaid">%s</pre>
                <script>
                (function () {
                    'use strict';
                    var Q = '%s';
                    function reportHeight(h) {
                        try {
                            if (typeof window[Q] === 'function') {
                                window[Q]({ request: JSON.stringify({ fn: 'reportHeight', args: [h] }),
                                    onSuccess: function () {}, onFailure: function () {} });
                            }
                        } catch (e) { /* 桥未就绪忽略 */ }
                    }
                    window.javaBridge = { reportHeight: reportHeight };
                })();
                </script>
                <script src="mermaid.min.js"></script>
                <script>
                  mermaid.initialize({ startOnLoad: true, theme: '%s', securityLevel: 'strict' });
                  var mo = new MutationObserver(function () {
                      window.javaBridge.reportHeight(document.body.scrollHeight);
                  });
                  mo.observe(document.body, { childList: true, subtree: true, attributes: true });
                  window.javaBridge.reportHeight(document.body.scrollHeight);
                </script>
                </body></html>
                """.formatted(escaped, queryFn, dark ? "dark" : "default");
        String page = "mermaid-" + MERMAID_SEQ.incrementAndGet() + ".html";
        Files.writeString(dir.resolve(page), html, StandardCharsets.UTF_8);
        return dir.resolve(page).toUri().toString();
    }

    private static void copy(String classpath, Path out) throws IOException {
        try (InputStream in = JcefResources.class.getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IOException("缺少 classpath 资源: " + classpath);
            }
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 读取 classpath 文本资源（UTF-8），用于将聊天页 CSS/JS 内联进单文件 HTML。 */
    private static String readText(String classpath) throws IOException {
        try (InputStream in = JcefResources.class.getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IOException("缺少 classpath 资源: " + classpath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path workDir(String name) throws IOException {
        Path base = Paths.get(System.getProperty("java.io.tmpdir", "."), "jwork", "cef-web", name);
        Files.createDirectories(base);
        return base;
    }
}
