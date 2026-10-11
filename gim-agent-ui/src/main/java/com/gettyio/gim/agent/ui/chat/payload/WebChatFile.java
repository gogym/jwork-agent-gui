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
 * 文件附件的 JS 桥接载荷：{@code chat.js} 的 {@code fileRow(f)} 契约视图。
 * <p>{@code size} 为人类可读文本（如 {@code 1.2 MB}），未知为 {@code ""} 由前端自行省略显示；
 * {@code path} 为本地绝对路径，点击文件行经桥回抛交由 Java 打开所在目录，
 * 无路径（预览引用尚未落盘）时为 null 并省略。</p>
 *
 * @param name 原始文件名，恒序列化
 * @param size 人类可读大小文本，恒序列化（未知为空串）
 * @param path 本地绝对路径；无则 null 并省略
 *
 * @author gogym
 */
@JsonPropertyOrder({"name", "size", "path"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebChatFile(String name, String size, String path) {

    /** 规整入参：name/size 缺省为空串（恒序列化，前端按空值自行兜底）。 */
    public WebChatFile {
        name = name == null ? "" : name;
        size = size == null ? "" : size;
    }
}
