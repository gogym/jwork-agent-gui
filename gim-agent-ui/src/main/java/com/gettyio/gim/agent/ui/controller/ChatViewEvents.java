package com.gettyio.gim.agent.ui.controller;

/**
 * {@link com.gettyio.gim.agent.ui.chat.ChatWebView} 向宿主控制器回抛的运行时事件（除气泡动作外的一切）。
 * 由 {@code MainController} 实现；经 {@link com.gettyio.gim.agent.ui.chat.ChatBridge} 从 WebView JS 侧调入。
 */
public interface ChatViewEvents {

    /** 页面脚本装载完成（桥已就绪），控制器可安全推送主题与历史。 */
    void chatReady();

    /** 右键消息：{@code id} 主键（0 未落盘），弹原生删除/分支菜单。 */
    void chatContext(long id, double x, double y);

    /** 滚动到顶：向上补页入口。 */
    void chatScrollTop();

    /** 滚动状态变化：控制「回到底部」按钮显隐。 */
    void chatScrollState(boolean atBottom, boolean scrollable);

    /** 前端顶部回收（贴底且 DOM 过重）：{@code newOldestId} 为回收后 DOM 首条主键，宿主前移补页游标。 */
    void chatTrimmed(long newOldestId);

    /** 正文外链点击。 */
    void chatLink(String url);

    /** 图片/文件行点击：打开所在目录。 */
    void chatImage(String path);

    /** 代码块复制。 */
    void chatCopyCode(String text);

    /** HTML/SVG 代码块预览。 */
    void chatPreviewCode(String code, String lang);

    /** 消息区空/非空变化：驱动欢迎页显隐。 */
    void chatEmpty(boolean empty);
}
