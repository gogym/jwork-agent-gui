package com.gettyio.gim.agent.ui.jcef;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Frame;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * JCEF OSR 浏览器 + JavaFX Canvas 复合视图。
 *
 * <p>渲染链路：Chromium 离屏渲染出 BGRA 帧 → {@code onPaint} 回调 → 拷进 {@link WritableImage}
 * （{@link PixelFormat#getByteBgraPreInstance()}）→ 画到 FX {@link Canvas}。圆角 clip / 动画 / 布局
 * 全部仍由 JavaFX 负责，与旧 WebView 宿主一致。</p>
 *
 * <p>OSR 产帧前提：JCEF 内置 {@code CefBrowserOsr} 需其 {@code GLCanvas} 被 realize 到 displayable
 * 容器才会同步 native 尺寸并派发 {@code OnPaint}。这里用一个屏幕外的可见 {@link Frame} 承载画布
 * （真桌面会话可正常 realize；无桌面的 CI/远程会话无法产帧，属环境限制而非框架缺陷）。</p>
 *
 * <p>线程：{@code onPaint}、{@code onQuery} 均在 Chromium/AWT 线程回调，凡触碰 JavaFX 一律
 * {@link Platform#runLater} 派发回 FX 线程。JS→Java 经 {@link CefMessageRouter}（页面注入
 * {@code window.<queryFn>}），由 {@code requestHandler} 收 JSON 载荷。</p>
 */
public final class JcefOsrView {

    private static final Logger log = LoggerFactory.getLogger(JcefOsrView.class);

    private final Canvas canvas = new Canvas();
    private final StackPane host = new StackPane(canvas);
    private final CefClient client;
    private final CefBrowser browser;
    private final CefMessageRouter router;
    private volatile Frame realize;
    private volatile Runnable loadEnd;

    private final String queryFn;
    private final String cancelFn;

    /**
     * @param queryFn       JS 侧发起请求的函数名（注入到 {@code window.<queryFn>}）
     * @param cancelFn      JS 侧取消请求的函数名（注入到 {@code window.<cancelFn>}）
     * @param requestHandler 收到 JS→Java 请求（JSON 字符串）时的处理，将在 FX 线程回调；可为 null
     */
    public JcefOsrView(String queryFn, String cancelFn, Consumer<String> requestHandler) {
        this.queryFn = queryFn;
        this.cancelFn = cancelFn;
        this.client = JcefRuntime.get().createClient();

        CefMessageRouter.CefMessageRouterConfig cfg = new CefMessageRouter.CefMessageRouterConfig();
        cfg.jsQueryFunction = queryFn;
        cfg.jsCancelFunction = cancelFn;
        this.router = CefMessageRouter.create(cfg, new CefMessageRouterHandlerAdapter() {
            @Override
            public boolean onQuery(CefBrowser b, CefFrame f, long qid,
                                   String request, boolean persistent, CefQueryCallback cb) {
                if (requestHandler != null) {
                    Platform.runLater(() -> {
                        try {
                            requestHandler.accept(request);
                            if (cb != null) cb.success("");
                        } catch (Throwable t) {
                            log.warn("JCEF onQuery 处理失败", t);
                            if (cb != null) cb.failure(-1, String.valueOf(t.getMessage()));
                        }
                    });
                } else if (cb != null) {
                    cb.success("");
                }
                return true;
            }
        });
        this.client.addMessageRouter(router);

        this.client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser b, CefFrame f, int httpStatusCode) {
                if (f != null && f.isMain() && loadEnd != null) {
                    Runnable r = loadEnd;
                    Platform.runLater(r);
                }
            }
        });

        this.client.setOnPaintListener(evt -> {
            ByteBuffer bf = evt.getRenderedFrame();
            final int w = evt.getWidth(), h = evt.getHeight();
            if (bf == null || w <= 0 || h <= 0) return;
            final byte[] arr = new byte[w * h * 4];
            bf.rewind();
            bf.get(arr, 0, Math.min(arr.length, bf.remaining()));
            final int stride = w * 4;
            Platform.runLater(() -> {
                WritableImage img = new WritableImage(w, h);
                img.getPixelWriter().setPixels(0, 0, w, h,
                        PixelFormat.getByteBgraPreInstance(), arr, 0, stride);
                GraphicsContext gc = canvas.getGraphicsContext2D();
                gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
                gc.drawImage(img, 0, 0);
                canvas.setWidth(w);
                canvas.setHeight(h);
            });
        });

        this.browser = client.createBrowser("about:blank", true, false);
        this.browser.setWindowlessFrameRate(30);

        // 宿主尺寸变化 → 同步 OSR native 尺寸，令 Chromium 按真实视口重排重绘
        host.widthProperty().addListener((obs, o, n) -> syncSize(n.doubleValue(), host.getHeight()));
        host.heightProperty().addListener((obs, o, n) -> syncSize(host.getWidth(), n.doubleValue()));

        EventQueue.invokeLater(this::realizeOffscreen);
    }

    /** 屏幕外可见 Frame 承载 OSR 画布：让 AWT 判为 showing，触发 CefBrowserOsr 尺寸同步与 OnPaint。 */
    private void realizeOffscreen() {
        try {
            Component ui = browser.getUIComponent();
            ui.setSize(new Dimension(1, 1));
            Frame f = new Frame("jcef-osr-realizer");
            f.setUndecorated(true);
            f.setLayout(new BorderLayout());
            f.add(ui, BorderLayout.CENTER);
            f.setSize(1, 1);
            f.setLocation(-32000, -32000);
            f.setVisible(true);
            realize = f;
            browser.setWindowVisibility(true);
            browser.setFocus(true);
        } catch (Throwable t) {
            log.error("JCEF OSR realize 失败（该会话可能无桌面/AWT 显示设备），本视图不会产帧", t);
        }
    }

    /** 注册主框架装载完成回调（在 FX 线程触发），用于驱动就绪后重放挂起脚本；重复调用以最后一次为准。 */
    public void setOnLoadEnd(Runnable runnable) {
        this.loadEnd = runnable;
    }

    /** 宿主节点：控制器把它放进容器并撑满（对齐旧 ChatWebView.node() 语义）。 */
    public StackPane node() {
        return host;
    }

    public Canvas canvas() {
        return canvas;
    }

    public CefBrowser browser() {
        return browser;
    }

    /** Java→JS：在页面上下文执行脚本（对齐旧 engine.executeScript）。 */
    public void executeScript(String js) {
        try {
            browser.executeJavaScript(js, "about:blank", 0);
        } catch (Throwable t) {
            log.warn("JCEF executeScript 失败", t);
        }
    }

    /** 以 data: URL 装载内联 HTML（JCEF 不认 jar: URL；纯 ASCII 源，UTF-8 编码）。 */
    public void loadData(String html) {
        String url = "data:text/html;charset=utf-8,"
                + URLEncoder.encode(html, StandardCharsets.UTF_8).replace("+", "%20");
        browser.loadURL(url);
    }

    /** 装载任意 URL（http/file/data）。 */
    public void loadUrl(String url) {
        browser.loadURL(url);
    }

    /** 尺寸同步：驱动 OSR native resize，使 CEF 按新视口重排重绘。 */
    public void syncSize(double w, double h) {
        final int iw = (int) Math.max(1, Math.floor(w));
        final int ih = (int) Math.max(1, Math.floor(h));
        EventQueue.invokeLater(() -> {
            try {
                browser.getUIComponent().setSize(new Dimension(iw, ih));
            } catch (Throwable ignored) {}
        });
    }

    public void dispose() {
        try { browser.close(true); } catch (Throwable ignored) {}
        try { router.dispose(); } catch (Throwable ignored) {}
        try { client.dispose(); } catch (Throwable ignored) {}
        final Frame f = realize;
        realize = null;
        if (f != null) {
            EventQueue.invokeLater(() -> {
                try { f.dispose(); } catch (Throwable ignored) {}
            });
        }
    }
}
