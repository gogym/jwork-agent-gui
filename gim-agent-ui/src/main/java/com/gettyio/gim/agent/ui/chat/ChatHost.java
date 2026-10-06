package com.gettyio.gim.agent.ui.chat;

import javafx.scene.Scene;
import javafx.stage.Window;

/**
 * 对话视图协作类回调宿主的窄接口：由 {@code MainController} 实现。
 * <p>协作类（附件条、工具条菜单等）据此取当前窗口/场景、打开设置或关于对话框，
 * 而不反向依赖控制器类型，保持依赖方向单一。</p>
 */
public interface ChatHost {

    /** 当前主窗口：作为文件选择器/对话框的 owner。 */
    Window currentWindow();

    /** 当前场景：注册全局快捷键用。 */
    Scene currentScene();

    /** 打开设置页；{@code menuLabel} 非空时直达对应菜单页。 */
    void openSettings(String menuLabel);

    /** 弹出「关于」对话框。 */
    void showAbout();
}
