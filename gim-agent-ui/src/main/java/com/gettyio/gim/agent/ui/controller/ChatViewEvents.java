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

package com.gettyio.gim.agent.ui.controller;

/**
 * {@link com.gettyio.gim.agent.ui.chat.ChatWebView} 向宿主控制器回抛的运行时事件（除气泡动作外的一切）。
 * 由 {@code MainController} 实现；经 {@link com.gettyio.gim.agent.ui.chat.ChatBridge} 从 WebView JS 侧调入。
 *
 * @author gogym
 */
public interface ChatViewEvents {

    /** 页面脚本装载完成（桥已就绪），控制器可安全推送主题与历史。 */
    void chatReady();

    /** 右键消息：{@code id} 主键（0 未落盘），弹原生删除/分支菜单。 */
    void chatContext(long id, double x, double y);

    /** 菜单外点击/滚动：收起消息右键菜单（JCEF 区域点击不触发 JavaFX 弹窗自动隐藏，需 JS 回抛）。 */
    void chatDismissMenu();

    /** 聊天页选区有无变化：Ctrl+C 兜底据此让位（输入框有选区且聊天区无选区时保留输入框的复制）。 */
    void chatSelection(boolean hasSelection);

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
