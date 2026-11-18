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

package com.gettyio.jwork.agent.ui.chat.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * 一条气泡消息的 JS 桥接载荷：{@code chat.js} 的 {@code buildMessage(m)} 契约视图。
 * <p>由 {@link com.gettyio.jwork.agent.ui.chat.ChatMessagePayload} 组装，经 Jackson 序列化后交
 * {@code ChatAPI.renderHistory / prependOlder / appendMessage} 渲染。字段名与省略规则属于
 * 对前端的稳定 ABI：</p>
 * <ul>
 *   <li>{@code id / role / content} 恒序列化（正文缺失按空串）——前端凭 {@code data-id}
 *       做删除/编辑/重生成/复制定位；</li>
 *   <li>{@code images / files / tools / segments} 为 null 或空集合时整体省略（对应附件区/工具卡不渲染）；</li>
 *   <li>{@code thinking / time} 无值省略；{@code rawContent} 仅在展示正文与原文不同
 *       （助手 Markdown 内联本地图片为 data URI）时附上，供「复制」取原文。</li>
 * </ul>
 *
 * @param id         消息主键（发送即时上屏尚未落库时为临时值 0）
 * @param role       {@code user} 或 {@code assistant}
 * @param content    展示正文（用户为纯文本，助手为 Markdown 源文）
 * @param images     图片缩略图载荷（data URI），空则省略
 * @param files      文件行载荷，空则省略
 * @param tools      工具调用详情载荷（仅助手），空则省略
 * @param thinking   思考过程全文（仅助手），无则省略
 * @param time       本地化时间文本（仅助手历史重放），无则省略
 * @param rawContent 复制用原文（仅本地图片内联后与展示正文不同时非 null）
 * @param segments   助手消息时序分段（有交错时非空，前端按段还原时序布局），无则省略
 *
 * @author gogym
 */
@JsonPropertyOrder({"id", "role", "content", "images", "files", "tools", "thinking", "time", "rawContent", "segments"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebChatMessage(
        long id,
        String role,
        String content,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<WebChatImage> images,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<WebChatFile> files,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<WebChatTool> tools,
        String thinking,
        String time,
        String rawContent,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<WebChatSegment> segments) {

    /**
     * 规整入参：role/content 缺省为空串（恒序列化），集合缺省为空列表
     * （配合字段级 {@code NON_EMPTY} 省略，前端见空如无）。
     */
    public WebChatMessage {
        role = role == null ? "" : role;
        content = content == null ? "" : content;
        images = images == null ? List.of() : List.copyOf(images);
        files = files == null ? List.of() : List.copyOf(files);
        tools = tools == null ? List.of() : List.copyOf(tools);
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    /** 旧签名入口：无时序分段。 */
    public WebChatMessage(long id, String role, String content, List<WebChatImage> images, List<WebChatFile> files,
                          List<WebChatTool> tools, String thinking, String time, String rawContent) {
        this(id, role, content, images, files, tools, thinking, time, rawContent, null);
    }
}
