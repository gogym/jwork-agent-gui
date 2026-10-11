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

package com.gettyio.gim.agent.ui.export;

import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionHtmlRenderer} 单测：话题卡片头部元信息、Markdown 渲染（含表格/代码块）、
 * 原始 HTML 转义防注入、本地图片内嵌为 data URI、远程 URL 不内嵌、工具调用行。
 * 纯字符串渲染不依赖 JavaFX 工具包。
 *
 * @author gogym
 */
class SessionHtmlRendererTest {

    private static ChatSession session() {
        ChatSession s = new ChatSession();
        s.setSessionId("s-1");
        s.setTitle("导出测试");
        s.setAgentCode("default");
        s.setModelCode("gpt-x");
        return s;
    }

    private static ChatConversation msg(String role, String content) {
        ChatConversation c = new ChatConversation();
        c.setRole(role);
        c.setContent(content);
        c.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        return c;
    }

    @Test
    @DisplayName("头部元信息与角色气泡：标题/会话/智能体/模型/消息数逐项落位")
    void renderHeaderAndRoles() {
        String html = SessionHtmlRenderer.render(session(), List.of(
                msg("USER", "你好 **Jwork**"),
                msg("ASSISTANT", "")));

        assertTrue(html.contains("<title>导出测试</title>"));
        assertTrue(html.contains("会话：s-1"));
        assertTrue(html.contains("智能体：default"));
        assertTrue(html.contains("模型：gpt-x"));
        assertTrue(html.contains("消息数：2"));
        assertTrue(html.contains("role-user"));
        assertTrue(html.contains(">用户<"));
        assertTrue(html.contains("role-assistant"));
        assertTrue(html.contains(">助手<"));
        assertTrue(html.contains("2026-01-02 03:04:05"));
        // 空内容落「（空）」占位
        assertTrue(html.contains("（空）"));
    }

    @Test
    @DisplayName("Markdown 渲染：粗体、代码块与表格转为 HTML")
    void renderMarkdownFeatures() {
        String html = SessionHtmlRenderer.render(session(), List.of(
                msg("ASSISTANT", "**加粗**\n\n```java\nint a = 1;\n```\n\n|A|B|\n|-|-|\n|1|2|")));

        assertTrue(html.contains("<strong>加粗</strong>"));
        assertTrue(html.contains("<pre><code"));
        assertTrue(html.contains("<table>"));
    }

    @Test
    @DisplayName("原始 HTML 转义：<script> 等标签以实体输出，不落可执行标签")
    void escapeRawHtml() {
        String html = SessionHtmlRenderer.render(session(), List.of(
                msg("USER", "<script>alert(1)</script>")));

        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    @DisplayName("本地图片内嵌：真实文件读为 data URI，原文路径不再出现")
    void embedLocalImage(@TempDir Path dir) throws Exception {
        Path png = Files.write(dir.resolve("pic.png"), new byte[]{1, 2, 3});
        String dest = png.toAbsolutePath().toString().replace('\\', '/');

        String html = SessionHtmlRenderer.render(session(), List.of(
                msg("ASSISTANT", "![图](<" + dest + ">)")));

        assertTrue(html.contains("data:image/png;base64,"));
        assertFalse(html.contains(dest));
    }

    @Test
    @DisplayName("远程 URL 与不存在的本地路径：保持原样不内嵌")
    void keepRemoteUrl() {
        String html = SessionHtmlRenderer.render(session(), List.of(
                msg("ASSISTANT", "![x](https://example.com/a.png)\n\n![y](/no/such/file.png)")));

        assertTrue(html.contains("https://example.com/a.png"));
        assertFalse(html.contains("data:image"));
    }

    @Test
    @DisplayName("工具调用行：工具名与入参摘要一并渲染")
    void renderToolCalls() {
        ChatConversation c = msg("ASSISTANT", "已修改");
        c.setToolCalls("[{\"name\":\"edit_file\",\"input\":{\"path\":\"a.txt\"}}]");

        String html = SessionHtmlRenderer.render(session(), List.of(c));

        assertTrue(html.contains("<code>edit_file</code>"));
        assertTrue(html.contains("path=a.txt"));
    }
}
