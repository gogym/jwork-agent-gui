package com.gettyio.gim.agent.core.chat;

/**
 * 发送用的图片附件（transient）：仅承载当轮发给多模态模型所需的数据，不负责持久化。
 * <p>落盘由 {@link ChatService} 完成，持久化后的引用见 {@link StoredAttachment}。</p>
 *
 * @param fileName   原始文件名（用于推导扩展名与展示）
 * @param mediaType  MIME 类型，如 {@code image/png}
 * @param base64Data Base64 编码的图片字节（不含 data URI 前缀）
 */
public record ImageAttachment(String fileName, String mediaType, String base64Data) {
}
