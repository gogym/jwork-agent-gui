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

package com.gettyio.gim.agent.ui.chat.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 图片缩略图的 JS 桥接载荷：{@code chat.js} 的 {@code imageThumbs(images)} 契约视图。
 * <p>两个来源共用本结构：历史重放的落盘图片（{@code dataUri} + 本地绝对 {@code path}，
 * 点击缩略图经桥回抛路径交由 Java 打开）与发送即时上屏的待发图片（仅 {@code dataUri}，
 * 此时 {@code path} 为 null 并省略）。</p>
 *
 * @param dataUri {@code data:<mime>;base64,...} 内联图源，恒序列化
 * @param path    本地绝对路径；待发图片无路径时为 null 并省略
 *
 * @author gogym
 */
@JsonPropertyOrder({"dataUri", "path"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebChatImage(String dataUri, String path) {

    /** 规整入参：dataUri 缺省为空串（恒序列化，前端以空图占位）。 */
    public WebChatImage {
        dataUri = dataUri == null ? "" : dataUri;
    }
}
