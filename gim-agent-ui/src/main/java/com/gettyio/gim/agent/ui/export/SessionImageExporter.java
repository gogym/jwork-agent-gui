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

package com.gettyio.gim.agent.ui.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gettyio.gim.agent.ui.jcef.JcefOsrView;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.stage.Window;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 会话长图导出：把 {@link SessionHtmlRenderer} 生成的 HTML 交给 JCEF(Chromium OSR) 离屏渲染，
 * 按全内容高度定尺寸后取最近一帧位图（{@link JcefOsrView#lastImage()}）编码为 PNG。
 * <p>OSR 浏览器独立于 JavaFX 场景出帧（其自身以屏幕外 {@code Frame} realize），故无需挂 Stage：
 * 页面加载后经 {@code reportHeight} 垫片回报文档高度，据此把 OSR 视口撑到全高（超上限则整体
 * {@code zoom} 缩小），稳定后取位图。像素经 {@code PixelReader} 转为 {@code BufferedImage}
 * （项目无 javafx-swing，不走 SwingFXUtils），PNG 编码在后台线程完成。必须在 JavaFX 应用线程调用。</p>
 * <p>与聊天区同源用 Chromium 渲染，导出长图的 emoji/排版与界面一致。</p>
 *
 * @author gogym
 */
public final class SessionImageExporter {

    private static final Logger LOG = Logger.getLogger(SessionImageExporter.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@code CefMessageRouter} 注入 JS 的请求/取消函数名（与内联回报脚本一致）。 */
    private static final String QUERY_FN = "gimQuery";
    private static final String CANCEL_FN = "gimCancel";

    /** 长图内容宽度（CSS 像素）：与话题卡片 max-width 860px 匹配，两侧留边。 */
    private static final int CAPTURE_WIDTH = 900;

    /** 初始探测高度（CSS 像素）：先以该视口加载页面，再测出真实内容高。 */
    private static final int PROBE_HEIGHT = 800;

    /** 输出图像最大像素高度：超过则整体等比缩小，避免纹理超限与超长图不可读。 */
    private static final int MAX_OUTPUT_HEIGHT = 8000;

    /** 底部冗余（CSS 像素）：吸收像素取整误差，防止最后一行被裁切。 */
    private static final int HEIGHT_PADDING = 2;

    /** 注入样式：隐藏滚动条，避免其出现在快照边缘。 */
    private static final String STYLE_SUPPRESS =
            "<style>::-webkit-scrollbar{width:0 !important;height:0 !important;}"
                    + "html,body{margin:0;padding:0;}</style>";

    /** 内联回报脚本：装载后经 {@code CefMessageRouter} 请求函数持续上报文档全高（图片解码/重排会多次触发）。 */
    private static final String HEIGHT_REPORTER =
            "<script>(function(){'use strict';var Q='" + QUERY_FN + "';"
                    + "function rep(){try{if(typeof window[Q]==='function'){"
                    + "window[Q]({request:JSON.stringify({fn:'height',args:[Math.max(document.body.scrollHeight,"
                    + "document.documentElement.scrollHeight)]}),onSuccess:function(){},onFailure:function(){}});}}catch(e){}}"
                    + "var mo=new MutationObserver(rep);"
                    + "window.addEventListener('load',function(){mo.observe(document.body,{childList:true,subtree:true,attributes:true});rep();});"
                    + "setTimeout(rep,60);setTimeout(rep,240);})();</script>";

    /** 主动测量脚本：装载稳定后触发一次高度上报。 */
    private static final String HEIGHT_POLL =
            "try{window['" + QUERY_FN + "']({request:JSON.stringify({fn:'height',args:[Math.max("
                    + "document.body.scrollHeight,document.documentElement.scrollHeight)]}),"
                    + "onSuccess:function(){},onFailure:function(){}});}catch(e){}";

    private SessionImageExporter() {
    }

    /**
     * 异步导出长图：在 FX 线程调用；成功后 FX 线程回调 {@code onSuccess}（落盘路径），
     * 失败回调 {@code onError}（用户可读原因）。
     */
    @SuppressWarnings("unused")
    public static void export(Window owner, String html, Path target,
                              Consumer<Path> onSuccess, Consumer<String> onError) {
        final Object[] latestHeight = new Object[1];
        final boolean[] finished = new boolean[1];
        final PauseTransition[] debounce = new PauseTransition[1];
        final JcefOsrView[] holder = new JcefOsrView[1];

        JcefOsrView view = new JcefOsrView(QUERY_FN, CANCEL_FN, req -> {
            try {
                JsonNode n = MAPPER.readTree(req);
                if (!"height".equals(n.path("fn").asText(""))) {
                    return;
                }
                JsonNode a = n.path("args");
                double h = a.isArray() && a.size() > 0 ? a.get(0).asDouble(0) : 0;
                if (h <= 1) {
                    return;
                }
                latestHeight[0] = h;
                if (debounce[0] != null) {
                    debounce[0].stop();
                }
                PauseTransition t = new PauseTransition(Duration.millis(400));
                t.setOnFinished(e -> sizeAndCapture(holder[0], latestHeight[0], target, onSuccess, onError, finished));
                debounce[0] = t;
                t.play();
            } catch (Throwable ignored) {
                // 单个脏高度事件忽略，由后续上报或超时兜底
            }
        });
        holder[0] = view;

        view.setOnLoadEnd(() -> {
            PauseTransition settle = new PauseTransition(Duration.millis(200));
            settle.setOnFinished(e -> {
                view.executeScript(HEIGHT_POLL);
                if (!finished[0] && latestHeight[0] == null) {
                    // 装载完成但尚未收到高度：稍后仍无则报错
                    PauseTransition guard = new PauseTransition(Duration.millis(1500));
                    guard.setOnFinished(e2 -> {
                        if (!finished[0] && latestHeight[0] == null) {
                            fail(view, onError, finished, "页面高度测量失败，无法导出长图");
                        }
                    });
                    guard.play();
                }
            });
            settle.play();
        });

        view.syncSize(CAPTURE_WIDTH, PROBE_HEIGHT);
        view.loadData(withReporter(html));
    }

    /** 测量到全高 → 计算缩放 → 撑高 OSR 视口 → 稳定后取帧。 */
    private static void sizeAndCapture(JcefOsrView view, Object heightObj, Path target,
                                       Consumer<Path> onSuccess, Consumer<String> onError, boolean[] finished) {
        if (finished[0] || view == null) {
            return;
        }
        double cssHeight = heightObj instanceof Double d ? d : 0;
        if (cssHeight <= 1) {
            fail(view, onError, finished, "页面高度测量失败，无法导出长图");
            return;
        }
        double totalCss = cssHeight + HEIGHT_PADDING;
        double zoom = Math.min(1.0, (double) MAX_OUTPUT_HEIGHT / totalCss);
        int pixelWidth = (int) Math.ceil(CAPTURE_WIDTH * zoom);
        int pixelHeight = (int) Math.ceil(totalCss * zoom);

        if (zoom < 1.0) {
            view.executeScript("document.body.style.zoom='" + zoom + "';");
        }
        view.syncSize(pixelWidth, pixelHeight);

        PauseTransition settle = new PauseTransition(Duration.millis(320));
        settle.setOnFinished(e -> snapshot(view, target, onSuccess, onError, finished));
        settle.play();
    }

    /** 取最近一帧位图 → {@code BufferedImage} → 关浏览器 → 后台线程写 PNG。 */
    private static void snapshot(JcefOsrView view, Path target,
                                 Consumer<Path> onSuccess, Consumer<String> onError, boolean[] finished) {
        if (finished[0]) {
            return;
        }
        try {
            WritableImage image = view.lastImage();
            if (image == null) {
                fail(view, onError, finished, "长图渲染为空，请稍后重试");
                return;
            }
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            int[] pixels = new int[width * height];
            image.getPixelReader().getPixels(0, 0, width, height,
                    PixelFormat.getIntArgbInstance(), pixels, 0, width);
            if (looksBlank(pixels)) {
                fail(view, onError, finished, "长图渲染为空，请稍后重试");
                return;
            }

            BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            buffered.setRGB(0, 0, width, height, pixels, 0, width);

            finished[0] = true;
            view.dispose();

            Thread worker = new Thread(() -> {
                try {
                    Path parent = target.toAbsolutePath().getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    ImageIO.write(buffered, "png", target.toFile());
                    Platform.runLater(() -> onSuccess.accept(target));
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "会话长图写盘失败", e);
                    Platform.runLater(() -> onError.accept("保存长图失败：" + e.getMessage()));
                }
            }, "session-image-export");
            worker.setDaemon(true);
            worker.start();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "会话长图快照失败", e);
            fail(view, onError, finished, "长图渲染失败：" + e.getMessage());
        }
    }

    /** 抽样校验：整图近乎同色视为渲染异常（未出图的典型表现），宁可报错也不输出空白图。 */
    static boolean looksBlank(int[] pixels) {
        int step = Math.max(1, pixels.length / 4096);
        int probe = pixels[0];
        for (int i = step; i < pixels.length; i += step) {
            if (pixels[i] != probe) {
                return false;
            }
        }
        return true;
    }

    /** 注入隐藏滚动条样式与高度回报脚本：优先插入 {@code </head>} 前，否则前置/追加。 */
    static String withReporter(String html) {
        String safe = html == null ? "" : html;
        String head = STYLE_SUPPRESS;
        if (safe.contains("</head>")) {
            safe = safe.replace("</head>", head + "</head>");
        } else {
            safe = head + safe;
        }
        if (safe.contains("</body>")) {
            safe = safe.replace("</body>", HEIGHT_REPORTER + "</body>");
        } else {
            safe = safe + HEIGHT_REPORTER;
        }
        return safe;
    }

    /** 幂等失败收尾：仅第一次生效，关闭浏览器并回调错误。 */
    private static void fail(JcefOsrView view, Consumer<String> onError, boolean[] finished, String message) {
        if (finished[0]) {
            return;
        }
        finished[0] = true;
        if (view != null) {
            view.dispose();
        }
        if (onError != null) {
            onError.accept(message);
        }
    }
}
