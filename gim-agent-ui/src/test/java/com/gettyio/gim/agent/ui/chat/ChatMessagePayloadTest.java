package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.core.chat.ImageAttachment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatMessagePayload} 纯逻辑单测（不启动 WebView、不依赖 JavaFX 工具包）：
 * 校验 Java→JS 的字符串/JSON 转义安全、用户/发送即时载荷结构，以及助手正文本地图片内联为
 * {@code data:} URI、远程 URL 保持原样三条契约，保证推给 {@code chat.js} 的 JSON 始终合法。
 */
class ChatMessagePayloadTest {

    @Test
    @DisplayName("jsString：引号、反斜杠、换行与控制字符安全转义")
    void jsStringEscapes() {
        assertEquals("\"a\\\"b\"", ChatMessagePayload.jsString("a\"b"));
        assertEquals("\"a\\\\b\"", ChatMessagePayload.jsString("a\\b"));
        assertEquals("\"a\\nb\"", ChatMessagePayload.jsString("a\nb"));
        // null 回退空串，绝不产出裸 null 破坏脚本
        assertEquals("\"\"", ChatMessagePayload.jsString(null));
    }

    @Test
    @DisplayName("toJson：消息列表序列化为合法 JSON 数组，空列表为 []")
    void toJsonSerializesArray() {
        assertEquals("[]", ChatMessagePayload.toJson(List.of()));
        String json = ChatMessagePayload.toJson(List.of(Map.of("id", 1L, "role", "user")));
        assertTrue(json.startsWith("["), json);
        assertTrue(json.endsWith("]"), json);
        assertTrue(json.contains("\"role\":\"user\""), json);
    }

    @Test
    @DisplayName("userLive：即时用户气泡携带 Base64 图片 data URI，无附件时省略 images/files")
    void userLivePayloadShape(@TempDir Path root) {
        ChatMessagePayload p = new ChatMessagePayload(new GimPaths(root));
        Map<String, Object> m = p.userLive(0L, "你好",
                List.of(new ImageAttachment("a.png", "image/png", "QUJD")), null);
        assertEquals("user", m.get("role"));
        assertEquals("你好", m.get("content"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> images = (List<Map<String, Object>>) m.get("images");
        assertEquals(1, images.size());
        assertEquals("data:image/png;base64,QUJD", images.get(0).get("dataUri"));

        Map<String, Object> plain = p.userLive(0L, "纯文本", List.of(), List.of());
        assertFalse(plain.containsKey("images"), plain.toString());
        assertFalse(plain.containsKey("files"), plain.toString());
    }

    @Test
    @DisplayName("user：历史用户消息无附件时仅含 role/content/id")
    void userPayloadNoAttachment(@TempDir Path root) {
        ChatMessagePayload p = new ChatMessagePayload(new GimPaths(root));
        Map<String, Object> m = p.user(42L, "问题", null);
        assertEquals(42L, m.get("id"));
        assertEquals("user", m.get("role"));
        assertEquals("问题", m.get("content"));
        assertFalse(m.containsKey("images"), m.toString());
        assertFalse(m.containsKey("files"), m.toString());
    }

    @Test
    @DisplayName("inlineLocalImages：本地真实文件内联为 data URI，远程 URL 原样保留")
    void inlineLocalImages(@TempDir Path root) throws Exception {
        ChatMessagePayload p = new ChatMessagePayload(new GimPaths(root));
        Path png = Files.write(root.resolve("a.png"), new byte[]{1, 2, 3});

        String local = p.inlineLocalImages("![图](" + png.toAbsolutePath() + ")");
        assertTrue(local.contains("data:image/png;base64,"), local);

        String remote = p.inlineLocalImages("![图](https://example.com/a.png)");
        assertEquals("![图](https://example.com/a.png)", remote);

        // 无图片引用时原样返回，含尖括号包裹的绝对路径也能内联
        String angled = p.inlineLocalImages("![图](<" + png.toAbsolutePath() + ">)");
        assertTrue(angled.contains("data:image/png;base64,"), angled);
    }

    @Test
    @DisplayName("assistant：本地图片内联后仍附 rawContent 原文供复制；无图片时不附 rawContent")
    void assistantRawContent(@TempDir Path root) throws Exception {
        ChatMessagePayload p = new ChatMessagePayload(new GimPaths(root));
        Path png = Files.write(root.resolve("a.png"), new byte[]{1, 2, 3});
        String md = "看这张图：![图](" + png.toAbsolutePath() + ")";

        Map<String, Object> m = p.assistant(7L, md, null, null, null, false);
        String shown = (String) m.get("content");
        assertTrue(shown.contains("data:image/png;base64,"), shown);
        assertEquals(md, m.get("rawContent"));

        Map<String, Object> plain = p.assistant(8L, "纯文本回答", null, null, null, false);
        assertFalse(plain.containsKey("rawContent"), plain.toString());
        assertEquals("纯文本回答", plain.get("content"));
    }
}
