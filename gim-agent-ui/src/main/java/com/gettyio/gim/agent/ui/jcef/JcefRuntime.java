package com.gettyio.gim.agent.ui.jcef;

import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 进程级 JCEF 运行时单例：{@link CefApp} 全 JVM 只允许一个实例，此处惰性引导。
 *
 * <p>OSR 模式（{@code windowless_rendering_enabled=true}）：Chromium 内容以 BGRA 帧经
 * {@code onPaint} 回调送出，宿主用 {@link JcefOsrView} 把位图 blit 到 JavaFX Canvas，
 * 保留 JavaFX 侧的圆角 clip / 动画 / 事件模型，同时拿到彩色 emoji。</p>
 *
 * <p>原生库解包目录：{@code ${user.home}/.jwork/cef}（首次启动 jcefmaven 从内置 jar 解包；
 * 离线随包场景由 jpackage 预置同路径后 {@link CefAppBuilder#setSkipInstallation(boolean)} 可跳过）。
 * 该目录在 Windows 用户目录下可写，与安装目录解耦，兼容 Program Files 只读环境。</p>
 *
 * <p>JDK 16+ 运行 OSR 必须附带 JOGL 的模块开放参数（否则 AWT {@code GLCanvas} 因无法访问
 * {@code sun.awt}/{@code sun.java2d} 内部类而抛「Unable to determine GraphicsConfiguration」，OSR 不产帧）：
 * {@code --add-exports java.base/java.lang=ALL-UNNAMED}、
 * {@code --add-exports java.desktop/sun.awt=ALL-UNNAMED}、
 * {@code --add-exports java.desktop/sun.java2d=ALL-UNNAMED}。这些参数须落在 java 命令行上，
 * 已在 IDEA 运行配置、javafx-maven-plugin 与 package-windows.ps1 三处统一注入。</p>
 */
public final class JcefRuntime {

    private static final Logger log = LoggerFactory.getLogger(JcefRuntime.class);
    private static final JcefRuntime INSTANCE = new JcefRuntime();

    private volatile CefApp app;

    private JcefRuntime() {}

    public static JcefRuntime get() {
        return INSTANCE;
    }

    /** 惰性创建并返回进程级 {@link CefApp}；同步，首次调用可能触发 natives 解包（数秒）。 */
    public synchronized CefApp app() {
        if (app != null) return app;
        try {
            CefAppBuilder b = new CefAppBuilder();
            b.setInstallDir(installDir());
            CefSettings s = b.getCefSettings();
            s.windowless_rendering_enabled = true;
            s.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
            // 可选覆盖 CEF 用户数据目录（含单实例锁）：并行跑第二个 CEF 进程的测试/工具场景用
            // -Djwork.cef.root=... 指向独立目录，避免与主应用争抢同一目录锁。
            String rootOverride = System.getProperty("jwork.cef.root");
            if (rootOverride != null && !rootOverride.isBlank()) {
                s.root_cache_path = rootOverride;
            }
            app = b.build();
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownQuietly, "jcef-shutdown"));
            log.info("JCEF CefApp 就绪 installDir={}", installDir().getAbsolutePath());
            return app;
        } catch (Exception e) {
            throw new IllegalStateException("JCEF 初始化失败", e);
        }
    }

    /** 便捷：创建一个新 {@link CefClient}（每个 {@link JcefOsrView} 一个 client）。 */
    public CefClient createClient() {
        return app().createClient();
    }

    public boolean isInitialized() {
        return app != null;
    }

    private static File installDir() {
        Path base = Paths.get(System.getProperty("user.home", "."), ".jwork", "cef");
        return base.toFile();
    }

    private synchronized void shutdownQuietly() {
        if (app == null) return;
        try { app.dispose(); } catch (Throwable ignored) {}
        app = null;
    }
}
