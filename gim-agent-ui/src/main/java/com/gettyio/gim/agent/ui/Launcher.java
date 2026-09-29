package com.gettyio.gim.agent.ui;

import javafx.application.Application;

/**
 * 打包入口：作为 jar / jpackage 的 Main-Class。
 * <p>JavaFX 要求启动类不得直接是 {@code Application} 子类（否则非模块化运行会报
 * "JavaFX runtime components are missing"），故用独立的普通主类委托启动
 * {@link GimAgentApplication}。</p>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(GimAgentApplication.class, args);
    }
}
