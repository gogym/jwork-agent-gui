package com.gettyio.gim.agent.ui;

import com.gettyio.gim.agent.core.GimPaths;
import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 打包入口：作为 jar / jpackage 的 Main-Class。
 * <p>JavaFX 要求启动类不得直接是 {@code Application} 子类（否则非模块化运行会报
 * "JavaFX runtime components are missing"），故用独立的普通主类委托启动
 * {@link GimAgentApplication}。</p>
 * <p>同时承担进程级兜底：在 logback 初始化前把日志目录属性对齐 {@link GimPaths}，
 * 并注册非 FX 线程的默认未捕获异常处理器（jpackage 无控制台，不兜底就等于无声丢失）。</p>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        // 先于 logback 初始化设定文件日志目录，保证打包后日志一律落 ~/.jwork/logs/
        System.setProperty("gim.log.dir", new GimPaths().logsDir().toString());
        // 挂接启动画面：Spring 容器引导与主窗加载期间展示（主窗就绪后自动隐藏）
        System.setProperty("javafx.preloader", SplashPreloader.class.getName());
        // 全局灰度字体平滑：关闭 LCD 次像素渲染，消除字形边缘彩边，字体观感更干净
        System.setProperty("prism.lcdtext", "false");
        Logger log = LoggerFactory.getLogger(Launcher.class);
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                log.error("未捕获异常 on thread {}", thread.getName(), throwable));
        Application.launch(GimAgentApplication.class, args);
    }
}
