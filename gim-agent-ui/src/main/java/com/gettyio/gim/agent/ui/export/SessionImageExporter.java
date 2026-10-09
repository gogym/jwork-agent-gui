package com.gettyio.gim.agent.ui.export;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
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
 * 会话长图导出：把 {@link SessionHtmlRenderer} 生成的 HTML 放进隐藏窗口内的 WebView 离屏渲染
 * （内容宽度固定 900 CSS 像素），按全内容高度一次性快照后编码为 PNG。
 * <p>隐藏窗口为 {@link StageStyle#UNDECORATED} + {@code opacity=0} + {@code initOwner}，
 * 不可见且不占任务栏；超长会话超过 8000 像素时按比例整体缩小（zoom），保证内容完整不截断、
 * 不触及 WebKit 纹理上限。zoom 缩放的是渲染内容，因此视口像素尺寸需同乘 zoom，逻辑视口
 * 始终保持 900 宽，快照恰好铺满内容不留空白。像素经 {@code PixelReader} 转为
 * {@code BufferedImage}（项目无 javafx-swing 依赖，不走 SwingFXUtils），PNG 编码在后台
 * 线程完成。必须在 JavaFX 应用线程调用。</p>
 */
public final class SessionImageExporter {

    private static final Logger LOG = Logger.getLogger(SessionImageExporter.class.getName());

    /** 长图内容宽度（CSS 像素）：与话题卡片 max-width 860px 匹配，两侧留边。 */
    private static final int CAPTURE_WIDTH = 900;

    /** 初始探测高度（CSS 像素）：先以该视口加载页面，再测出真实内容高。 */
    private static final int PROBE_HEIGHT = 800;

    /** 输出图像最大像素高度：超过则整体等比缩小，避免纹理超限与超长图不可读。 */
    private static final int MAX_OUTPUT_HEIGHT = 8000;

    /** 底部冗余（CSS 像素）：吸收像素取整误差，防止最后一行被裁切。 */
    private static final int HEIGHT_PADDING = 2;

    /** 注入样式：隐藏滚动条，避免其出现在快照边缘。 */
    private static final String SCROLLBAR_SUPPRESS =
            "<style>::-webkit-scrollbar{width:0 !important;height:0 !important;}</style>";

    private SessionImageExporter() {
    }

    /**
     * 异步导出长图：在 FX 线程调用；成功后 FX 线程回调 {@code onSuccess}（落盘路径），
     * 失败回调 {@code onError}（用户可读原因）。
     */
    public static void export(Window owner, String html, Path target,
                              Consumer<Path> onSuccess, Consumer<String> onError) {
        WebView view = new WebView();
        view.setContextMenuEnabled(false);
        view.setPrefWidth(CAPTURE_WIDTH);
        view.setPrefHeight(PROBE_HEIGHT);
        view.setZoom(1.0);

        Stage stage = new Stage(StageStyle.UNDECORATED);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setOpacity(0);
        Scene scene = new Scene(new Group(view), CAPTURE_WIDTH, PROBE_HEIGHT);
        scene.setFill(Color.WHITE);
        stage.setScene(scene);

        boolean[] finished = new boolean[1];
        view.getEngine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                // 双 runLater 等布局落定，再留一小段延迟让内嵌图片完成解码
                Platform.runLater(() -> Platform.runLater(() -> {
                    PauseTransition settle = new PauseTransition(Duration.millis(200));
                    settle.setOnFinished(e -> capture(view, stage, target, onSuccess, onError, finished));
                    settle.play();
                }));
            } else if (state == Worker.State.FAILED) {
                finish(stage, onError, finished, "网页渲染失败，无法导出长图");
            }
        });

        stage.show();
        view.getEngine().loadContent(withScrollbarSuppressed(html), "text/html");
    }

    /** 测量页面全高 → 计算缩放 → 调整视口尺寸 → 延迟快照。 */
    private static void capture(WebView view, Stage stage, Path target,
                                Consumer<Path> onSuccess, Consumer<String> onError, boolean[] finished) {
        if (finished[0]) {
            return;
        }
        double cssHeight;
        try {
            Object result = view.getEngine().executeScript(
                    "Math.max(document.body.scrollHeight, document.documentElement.scrollHeight)");
            cssHeight = result instanceof Number n ? n.doubleValue() : 0;
        } catch (Exception e) {
            cssHeight = 0;
        }
        if (cssHeight <= 1) {
            finish(stage, onError, finished, "页面高度测量失败，无法导出长图");
            return;
        }

        double totalCss = cssHeight + HEIGHT_PADDING;
        double zoom = Math.min(1.0, MAX_OUTPUT_HEIGHT / totalCss);
        double pixelWidth = Math.ceil(CAPTURE_WIDTH * zoom);
        double pixelHeight = Math.ceil(totalCss * zoom);

        // zoom 缩放渲染内容：视口像素尺寸乘以 zoom 后，逻辑视口仍为 CAPTURE_WIDTH 宽，
        // 快照恰好铺满内容（否则 zoom<1 时图右侧会留白）
        view.setZoom(zoom);
        view.setPrefWidth(pixelWidth);
        view.setPrefHeight(pixelHeight);
        stage.setWidth(pixelWidth);
        stage.setHeight(pixelHeight);

        PauseTransition settle = new PauseTransition(Duration.millis(150));
        settle.setOnFinished(e -> snapshot(view, stage, (int) pixelWidth, (int) pixelHeight,
                target, onSuccess, onError, finished));
        settle.play();
    }

    /** 整节点快照 → BufferedImage → 关窗 → 后台线程写 PNG。 */
    private static void snapshot(WebView view, Stage stage, int width, int height, Path target,
                                 Consumer<Path> onSuccess, Consumer<String> onError, boolean[] finished) {
        if (finished[0]) {
            return;
        }
        try {
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.WHITE);
            WritableImage image = new WritableImage(width, height);
            view.snapshot(params, image);

            int[] pixels = new int[width * height];
            image.getPixelReader().getPixels(0, 0, width, height,
                    PixelFormat.getIntArgbInstance(), pixels, 0, width);
            if (looksBlank(pixels)) {
                finish(stage, onError, finished, "长图渲染为空，请稍后重试");
                return;
            }

            BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            buffered.setRGB(0, 0, width, height, pixels, 0, width);

            finished[0] = true;
            stage.close();

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
            finish(stage, onError, finished, "长图渲染失败：" + e.getMessage());
        }
    }

    /** 抽样校验：整图近乎同色视为渲染异常（隐藏窗口未出图的典型表现），宁可报错也不输出空白图。 */
    private static boolean looksBlank(int[] pixels) {
        int step = Math.max(1, pixels.length / 4096);
        int probe = pixels[0];
        for (int i = step; i < pixels.length; i += step) {
            if (pixels[i] != probe) {
                return false;
            }
        }
        return true;
    }

    private static String withScrollbarSuppressed(String html) {
        String safe = html == null ? "" : html;
        return safe.contains("</head>")
                ? safe.replace("</head>", SCROLLBAR_SUPPRESS + "</head>")
                : SCROLLBAR_SUPPRESS + safe;
    }

    /** 幂等的失败收尾：仅第一次生效，关闭隐藏窗口并回调错误。 */
    private static void finish(Stage stage, Consumer<String> onError, boolean[] finished, String message) {
        if (finished[0]) {
            return;
        }
        finished[0] = true;
        if (stage != null) {
            stage.close();
        }
        if (onError != null) {
            onError.accept(message);
        }
    }
}
