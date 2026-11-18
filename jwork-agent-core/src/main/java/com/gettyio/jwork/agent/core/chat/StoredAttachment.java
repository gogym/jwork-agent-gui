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

package com.gettyio.jwork.agent.core.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 落盘后的附件引用：随消息写入 {@code conversations.metadata}，回放时按 {@code path} 读回。
 *
 * @param name 原始文件名
 * @param mime MIME 类型
 * @param path 相对 {@code JworkPaths.attachmentsDir()} 的存储路径，如 {@code <sessionId>/<uuid>.png}
 * @param kind 附件类别：{@code image}（图片，回放渲染缩略图）或 {@code file}（文档，回放渲染文件行）
 * @param sizeBytes 原始字节数；未知为 0
 *
 * @author gogym
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredAttachment(String name, String mime, String path, String kind, long sizeBytes) {

    public static final String KIND_IMAGE = "image";
    public static final String KIND_FILE = "file";

    /** 兼容旧数据/简化构造：默认按图片类别。 */
    public StoredAttachment(String name, String mime, String path) {
        this(name, mime, path, KIND_IMAGE, 0L);
    }

    /** 该附件是否应作为文件行渲染（缺省 kind 视为图片，兼容历史 metadata）。 */
    @JsonIgnore
    public boolean isFile() {
        return KIND_FILE.equalsIgnoreCase(kind);
    }
}
