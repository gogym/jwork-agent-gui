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

package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.ui.controller.ChatViewEvents;

/**
 * 暴露给 WebView 内 {@code chat.js} 的 Java 桥对象（挂到 {@code window.javaBridge}）。
 * <p>仅承载 JS→Java 事件：气泡操作（复制/朗读/重生成/编辑重发）转调 {@link MessageActionListener}，
 * 其余（链接、图片、代码复制/预览、右键、收菜单、选区、滚动、空态、就绪）转调 {@link ChatViewEvents}。
 * WebView 的 JS 在 FX 应用线程上执行，方法体可直接操作 JavaFX，无需再 {@code Platform.runLater}。</p>
 * <p>注意：JSObject 反射调用要求方法所在类与其方法均为 public——WebView 侧反射调用 {@code window.javaBridge.xxx}
 * 只能访问公开类，否则静默失败（尤其 {@code onEmpty} 不回抛会导致欢迎页常驻）。所有分支都做了入参兜底，绝不因单个脏事件中断渲染。</p>
 *
 * @author gogym
 */
public final class ChatBridge {

    private final MessageActionListener actions;
    private final ChatViewEvents events;

    ChatBridge(MessageActionListener actions, ChatViewEvents events) {
        this.actions = actions;
        this.events = events;
    }

    /** 页面脚本装载完成（{@code chat.js} 末尾若桥已就绪会直接回调）。 */
    public void onReady() {
        events.chatReady();
    }

    /** 悬停操作条按钮：{@code kind} ∈ copy/speak/regenerate/edit；{@code id} 主键；{@code raw} 原文。 */
    public void onAction(String kind, long id, String raw) {
        switch (kind == null ? "" : kind) {
            case "copy" -> {
                if (raw != null && !raw.isEmpty()) {
                    actions.onCopy(raw);
                }
            }
            case "speak" -> {
                if (raw != null && !raw.isBlank()) {
                    actions.onSpeak(raw, () -> {
                    });
                }
            }
            case "regenerate" -> actions.onRegenerate();
            case "edit" -> {
                if (id > 0) {
                    actions.onEditResend(id, raw);
                }
            }
            default -> {
                // 未知动作忽略
            }
        }
    }

    /** 右键菜单：交控制器弹原生 ContextMenu（删除/分支），{@code x,y} 为视口坐标。 */
    public void onContext(long id, double x, double y) {
        events.chatContext(id, x, y);
    }

    /** 菜单外点击/滚动：交控制器收起已弹出的消息右键菜单。 */
    public void onDismissMenu() {
        events.chatDismissMenu();
    }

    /** 聊天页选区有无变化：驱动 Ctrl+C 兜底的让位判断。 */
    public void onSelection(boolean hasSelection) {
        events.chatSelection(hasSelection);
    }

    /** 滚动到顶：触发向上补页。 */
    public void onScrollTop() {
        events.chatScrollTop();
    }

    /** 滚动状态：{@code atBottom} 是否在底部、{@code scrollable} 是否可滚动（控「回到底部」显隐）。 */
    public void onScrollState(boolean atBottom, boolean scrollable) {
        events.chatScrollState(atBottom, scrollable);
    }

    /** 前端顶部回收后新的 DOM 最旧主键（{@code >0}）：控制器前移补页游标并恢复向上补页。 */
    public void onTrimmed(long newOldestId) {
        events.chatTrimmed(newOldestId);
    }

    /** 正文链接点击：交控制器 {@code Desktop.browse}。 */
    public void onLink(String url) {
        events.chatLink(url);
    }

    /** 图片/文件行点击：交控制器打开所在目录或查看。 */
    public void onImage(String path) {
        events.chatImage(path);
    }

    /** 代码块复制按钮。 */
    public void onCopyCode(String text) {
        events.chatCopyCode(text);
    }

    /** HTML/SVG 代码块预览按钮。 */
    public void onPreviewCode(String code, String lang) {
        events.chatPreviewCode(code, lang);
    }

    /** 消息数变化：驱动欢迎页显隐。 */
    public void onEmpty(boolean empty) {
        events.chatEmpty(empty);
    }
}
