package com.gettyio.gim.agent.core.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 落盘后的附件引用：随消息写入 {@code conversations.metadata}，回放时按 {@code path} 读回。
 *
 * @param name 原始文件名
 * @param mime MIME 类型
 * @param path 相对 {@code GimPaths.attachmentsDir()} 的存储路径，如 {@code <sessionId>/<uuid>.png}
 * @param kind 附件类别：{@code image}（图片，回放渲染缩略图）或 {@code file}（文档，回放渲染文件行）
 * @param sizeBytes 原始字节数；未知为 0
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
