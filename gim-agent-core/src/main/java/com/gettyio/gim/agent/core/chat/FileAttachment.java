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

package com.gettyio.gim.agent.core.chat;

/**
 * 发送用的文档附件（transient）：承载当轮需要抽取正文喂给模型的文档原始字节。
 * <p>落盘与文本抽取由 {@link ChatService} 完成，持久化后的引用见 {@link StoredAttachment}（kind=file）。</p>
 *
 * @param fileName   原始文件名（用于推导扩展名、判定抽取器与展示）
 * @param mediaType  MIME 类型（可空，缺扩展名时辅助判定）
 * @param base64Data Base64 编码的文件字节（不含 data URI 前缀）
 * @param sizeBytes  原始字节数（用于展示与超限校验）
 *
 * @author gogym
 */
public record FileAttachment(String fileName, String mediaType, String base64Data, long sizeBytes) {
}
