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

package com.gettyio.jwork.agent.ui.jcef;

import com.gettyio.jwork.agent.core.JworkPaths;
import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程级 JCEF 运行时单例：{@link CefApp} 全 JVM 只允许一个实例，此处惰性引导。
 *
 * <p>OSR 模式（{@code windowless_rendering_enabled=true}）：Chromium 内容以 BGRA 帧经
 * {@code onPaint} 回调送出，宿主用 {@link JcefOsrView} 把位图 blit 到 JavaFX Canvas，
 * 保留 JavaFX 侧的圆角 clip / 动画 / 事件模型，同时拿到彩色 emoji。</p>
 *
 * <p>原生库解包目录：数据根目录下的 {@code cef/}（随 {@link JworkPaths} 解析，默认 {@code ~/.jwork/cef}）。
 * Windows x64 natives 已作为 runtime 依赖 {@code me.friwi:jcef-natives-windows-amd64} 随包
 * （package 档平铺进 lib/ 随 msi 分发），jcefmaven 首启优先从 classpath 上的该 jar 解包，全程不联网；
 * 数据目录被删后也仅重新解包。仅平台不匹配（非 Windows 开发机）才回退在线下载。
 * 该目录在 Windows 用户目录下可写，与安装目录解耦，兼容 Program Files 只读环境。</p>
 *
 * <p>JDK 16+ 运行 OSR 必须附带 JOGL 的模块开放参数（否则 AWT {@code GLCanvas} 因无法访问
 * {@code sun.awt}/{@code sun.java2d} 内部类而抛「Unable to determine GraphicsConfiguration」，OSR 不产帧）：
 * {@code --add-exports java.base/java.lang=ALL-UNNAMED}、
 * {@code --add-exports java.desktop/sun.awt=ALL-UNNAMED}、
 * {@code --add-exports java.desktop/sun.java2d=ALL-UNNAMED}。这些参数须落在 java 命令行上，
 * 已在 IDEA 运行配置、javafx-maven-plugin 与 package-windows.ps1 三处统一注入。</p>
 *
 * @author gogym
 */
public final class JcefRuntime {

    private static final Logger log = LoggerFactory.getLogger(JcefRuntime.class);
    private static final JcefRuntime INSTANCE = new JcefRuntime();

    private volatile CefApp app;
    /** 在途/已完成的引导未来：失败后下次 {@link #appAsync()} 重建它即重试。 */
    private volatile CompletableFuture<CefApp> boot;
    /** shutdown hook 只挂一次（重试引导会多次进入 build）。 */
    private final AtomicBoolean hookAdded = new AtomicBoolean();

    private JcefRuntime() {}

    public static JcefRuntime get() {
        return INSTANCE;
    }

    /** 同步取进程级 {@link CefApp}：首次调用会阻塞到引导完成（可能触发 natives 解包，随包秒级；非 Windows 下载则分钟级）。 */
    public CefApp app() {
        return appAsync().join();
    }

    /**
     * 后台引导：返回 {@link CefApp} 就绪时完成的未来，首次调用起 daemon 线程执行构建。
     * <p>natives 目录缺失时需从随包 jar 解包（秒级），非 Windows 开发机则会同步下载数百 MB——绝不能在 FX 线程上跑，
     * 否则主窗冻死在装配期。视图侧订阅本未来，就绪后在 FX 线程装配浏览器；失败后未来被丢弃，
     * 再次调用（占位层的重试按钮）重新引导。</p>
     */
    public CompletableFuture<CefApp> appAsync() {
        CompletableFuture<CefApp> f = boot;
        if (f != null && !f.isCompletedExceptionally()) {
            return f;
        }
        synchronized (this) {
            f = boot;
            if (f == null || f.isCompletedExceptionally()) {
                f = new CompletableFuture<>();
                boot = f;
                final CompletableFuture<CefApp> target = f;
                Thread t = new Thread(() -> {
                    try {
                        target.complete(build());
                    } catch (Throwable e) {
                        log.error("JCEF 后台引导失败", e);
                        target.completeExceptionally(e);
                    }
                }, "jcef-boot");
                t.setDaemon(true);
                t.start();
            }
            return f;
        }
    }

    /** 构建进程级 {@link CefApp}（仅 jcef-boot 线程调用）；失败抛出由未来携带给订阅方。 */
    private CefApp build() {
        try {
            CefAppBuilder b = new CefAppBuilder();
            b.setInstallDir(installDir());
            // 禁后台网络（组件更新、域名预取等）：桌面嵌入场景不需要，不影响页面自身网络请求。
            // 注：实测该开关不覆盖 GCM 遗留注册（Chromium 141 仍会向已废弃端点注册并记 ERROR）。
            b.addJcefArgs("--disable-background-networking");
            CefSettings s = b.getCefSettings();
            s.windowless_rendering_enabled = true;
            // CEF 原生 stderr 日志只保留 FATAL（DISABLE 语义：stderr 仅 ≥FATAL 仍输出、不写日志文件）：
            // Chromium 141 的 GCM 服务每次启动都会向已废弃的注册端点发起请求并打 ERROR 日志
            // （google_apis\gcm\engine\registration_request.cc:291 DEPRECATED_ENDPOINT），
            // 这是 Chromium 内部遗留噪音、与应用无关、无法从源头关闭。
            // 需要排查 CEF 内部日志时，临时改回 LOGSEVERITY_WARNING 即可。
            s.log_severity = CefSettings.LogSeverity.LOGSEVERITY_DISABLE;
            // 可选覆盖 CEF 用户数据目录（含单实例锁）：并行跑第二个 CEF 进程的测试/工具场景用
            // -Djwork.cef.root=... 指向独立目录，避免与主应用争抢同一目录锁。
            String rootOverride = System.getProperty("jwork.cef.root");
            if (rootOverride != null && !rootOverride.isBlank()) {
                s.root_cache_path = rootOverride;
            }
            app = b.build();
            if (hookAdded.compareAndSet(false, true)) {
                Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "jcef-shutdown"));
            }
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

    private static File installDir() {
        return new JworkPaths().root().resolve("cef").toFile();
    }

    /**
     * 释放进程级 {@link CefApp}（连带渲染子进程与其占用的 cache 目录句柄）；未初始化或已释放则无操作。
     * <p>停机主路径（{@code AppExit} 的清理）与 JVM shutdown hook 都调它，故必须幂等：hook 只在
     * 进程开始退出时才跑，而 CEF 的 AWT 宿主线程正是拦住退出的那类线程，只靠 hook 会互等。</p>
     */
    public synchronized void shutdown() {
        boot = null;
        if (app == null) {
            return;
        }
        try {
            app.dispose();
        } catch (Throwable ignored) {
            // 关闭失败不影响进程退出
        }
        app = null;
    }
}
