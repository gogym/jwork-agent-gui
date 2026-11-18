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

/**
 * 发送用的图片附件（transient）：仅承载当轮发给多模态模型所需的数据，不负责持久化。
 * <p>落盘由 {@link ChatService} 完成，持久化后的引用见 {@link StoredAttachment}。</p>
 *
 * @param fileName   原始文件名（用于推导扩展名与展示）
 * @param mediaType  MIME 类型，如 {@code image/png}
 * @param base64Data Base64 编码的图片字节（不含 data URI 前缀）
 *
 * @author gogym
 */
public record ImageAttachment(String fileName, String mediaType, String base64Data) {
}
