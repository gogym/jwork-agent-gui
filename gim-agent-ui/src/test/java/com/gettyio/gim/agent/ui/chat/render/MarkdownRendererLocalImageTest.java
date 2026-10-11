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

package com.gettyio.gim.agent.ui.chat.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link MarkdownRenderer#localImagePath(String)} 单测：仅本地真实文件（绝对路径 / file:// URI）被识别为
 * 可渲染图片；远程 URL、相对路径、目录与不存在的文件一律返回 null 回落 alt 文本，
 * 保证渲染层不会加载任意网络资源。方法为纯路径判定，不依赖 JavaFX 工具包。
 *
 * @author gogym
 */
class MarkdownRendererLocalImageTest {

    @Test
    @DisplayName("绝对路径指向真实文件：识别并回传等价 Path")
    void absolutePathToRealFile(@TempDir Path dir) throws Exception {
        Path png = Files.write(dir.resolve("a.png"), new byte[]{1, 2, 3});
        assertEquals(png.toAbsolutePath(), MarkdownRenderer.localImagePath(png.toAbsolutePath().toString()));
    }

    @Test
    @DisplayName("file:// URI：还原为等价 Path")
    void fileUriAccepted(@TempDir Path dir) throws Exception {
        Path png = Files.write(dir.resolve("b.png"), new byte[]{1});
        assertEquals(png.toAbsolutePath(), MarkdownRenderer.localImagePath(png.toUri().toString()));
    }

    @Test
    @DisplayName("不存在的路径与目录：返回 null")
    void missingOrDirectoryRejected(@TempDir Path dir) {
        assertNull(MarkdownRenderer.localImagePath(dir.resolve("nope.png").toAbsolutePath().toString()));
        assertNull(MarkdownRenderer.localImagePath(dir.toAbsolutePath().toString()));
    }

    @Test
    @DisplayName("http(s) URL、相对路径与空值：返回 null")
    void remoteAndRelativeRejected() {
        assertNull(MarkdownRenderer.localImagePath("https://example.com/a.png"));
        assertNull(MarkdownRenderer.localImagePath("images/a.png"));
        assertNull(MarkdownRenderer.localImagePath(""));
        assertNull(MarkdownRenderer.localImagePath(null));
    }
}
