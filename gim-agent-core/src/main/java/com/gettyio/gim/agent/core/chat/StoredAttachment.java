package com.gettyio.gim.agent.core.chat;

/**
 * 落盘后的图片附件引用：随消息写入 {@code conversations.metadata}，回放时按 {@code path} 读回缩略图。
 *
 * @param name 原始文件名
 * @param mime MIME 类型
 * @param path 相对 {@code GimPaths.attachmentsDir()} 的存储路径，如 {@code <sessionId>/<uuid>.png}
 */
public record StoredAttachment(String name, String mime, String path) {
}
