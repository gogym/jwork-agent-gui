package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.core.chat.FileAttachment;
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

    /**
     * 将一个待发文档附件存入当前智能体的知识库（抽文本落盘 {@code knowledge/} 并更新索引）。
     * <p>区别于本轮临时抽取：这是“长期入库”，与当轮是否发送无关。</p>
     */
    void importAttachmentToKnowledge(FileAttachment attachment);

    /**
     * 请求生成图片：由宿主弹出提示词对话框并在当前会话直插一轮「提示词 + 生成图」的本地对话。
     */
    void requestImageGeneration();
}
