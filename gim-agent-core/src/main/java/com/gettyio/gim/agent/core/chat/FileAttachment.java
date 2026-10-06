package com.gettyio.gim.agent.core.chat;

/**
 * 发送用的文档附件（transient）：承载当轮需要抽取正文喂给模型的文档原始字节。
 * <p>落盘与文本抽取由 {@link ChatService} 完成，持久化后的引用见 {@link StoredAttachment}（kind=file）。</p>
 *
 * @param fileName   原始文件名（用于推导扩展名、判定抽取器与展示）
 * @param mediaType  MIME 类型（可空，缺扩展名时辅助判定）
 * @param base64Data Base64 编码的文件字节（不含 data URI 前缀）
 * @param sizeBytes  原始字节数（用于展示与超限校验）
 */
public record FileAttachment(String fileName, String mediaType, String base64Data, long sizeBytes) {
}
