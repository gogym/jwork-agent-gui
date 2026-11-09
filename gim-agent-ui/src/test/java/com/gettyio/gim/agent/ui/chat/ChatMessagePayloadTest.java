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

package com.gettyio.gim.agent.ui.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.core.chat.ChatSegmentCodec;
import com.gettyio.gim.agent.core.chat.ImageAttachment;
import com.gettyio.gim.agent.ui.chat.payload.ChatUiLabels;
import com.gettyio.gim.agent.ui.chat.payload.WebChatMessage;
import com.gettyio.gim.agent.ui.chat.workspace.ArtifactPreviewWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatMessagePayload} 纯逻辑单测（不启动 WebView、不依赖 JavaFX 工具包）：
 * 校验 Java→JS 的字符串/JSON 转义安全、消息桥接 DTO 的字段序列化与省略规则，以及助手正文本地图片
 * 内联为 {@code data:} URI、远程 URL 保持原样等契约，保证推给 {@code chat.js} 的 JSON 始终合法。
 *
 * @author gogym
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
    @DisplayName("toJson：消息列表序列化为合法 JSON 数组，字段顺序与省略规则稳定，空列表为 []")
    void toJsonSerializesArray() {
        assertEquals("[]", ChatMessagePayload.toJson(List.of()));
        WebChatMessage m = new WebChatMessage(1L, "user", "", List.of(), List.of(), List.of(), null, null, null);
        String json = ChatMessagePayload.toJson(List.of(m));
        // id/role/content 恒在且顺序固定；images/files/tools/thinking/time/rawContent 无值整体省略
        assertEquals("[{\"id\":1,\"role\":\"user\",\"content\":\"\"}]", json);
    }

    @Test
    @DisplayName("userLive：即时用户气泡携带 Base64 图片 data URI，无附件时序列化省略 images/files")
    void userLivePayloadShape(@TempDir Path root) {
        ChatMessagePayload p = new ChatMessagePayload(new GimPaths(root));
        WebChatMessage m = p.userLive(0L, "你好",
                List.of(new ImageAttachment("a.png", "image/png", "QUJD")), null);
        assertEquals("user", m.role());
        assertEquals("你好", m.content());
        assertEquals(1, m.images().size());
        assertEquals("data:image/png;base64,QUJD", m.images().get(0).dataUri());
        // 待发图片尚无本地路径，payload 里省略 path
        assertNull(m.images().get(0).path());

        WebChatMessage plain = p.userLive(0L, "纯文本", List.of(), List.of());
        assertTrue(plain.images().isEmpty());
        assertTrue(plain.files().isEmpty());
        String json = ChatMessagePayload.toJson(List.of(plain));
        assertFalse(json.contains("\"images\""), json);
        assertFalse(json.contains("\"files\""), json);
    }

    @Test
    @DisplayName("user：历史用户消息无附件时仅含 role/content/id")
    void userPayloadNoAttachment(@TempDir Path root) {
        ChatMessagePayload p = new ChatMessagePayload(new GimPaths(root));
        WebChatMessage m = p.user(42L, "问题", null);
        assertEquals(42L, m.id());
        assertEquals("user", m.role());
        assertEquals("问题", m.content());
        String json = ChatMessagePayload.toJson(List.of(m));
        assertFalse(json.contains("\"images\""), json);
        assertFalse(json.contains("\"files\""), json);
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

        WebChatMessage m = p.assistant(7L, md, null, null, null, false);
        String shown = m.content();
        assertTrue(shown.contains("data:image/png;base64,"), shown);
        assertEquals(md, m.rawContent());

        WebChatMessage plain = p.assistant(8L, "纯文本回答", null, null, null, false);
        assertNull(plain.rawContent());
        assertEquals("纯文本回答", plain.content());
    }

    @Test
    @DisplayName("segments 载荷：思考段带耗时、旁白段带本地化文本，段序原样送达前端")
    void segmentPayloadCarriesDurationAndNotice(@TempDir Path root) {
        ChatMessagePayload p = new ChatMessagePayload(new GimPaths(root));
        List<ChatSegmentCodec.ChatSegment> segs = List.of(
                ChatSegmentCodec.ChatSegment.ofThinking("先想一下").withDuration(2400L),
                ChatSegmentCodec.ChatSegment.ofNotice("iterStop", "200,1"),
                ChatSegmentCodec.ChatSegment.ofText("结论"));

        WebChatMessage m = p.assistant(9L, "结论", null, "先想一下", null, false, segs);

        assertEquals(3, m.segments().size(), "段序即真相：前端按此顺序排版，不在 Java 侧重排");
        assertEquals("thinking", m.segments().get(0).type());
        assertEquals(Long.valueOf(2400L), m.segments().get(0).ms());
        assertEquals("先想一下", m.segments().get(0).text());
        assertEquals("notice", m.segments().get(1).type());
        // 旁白在 Java 侧本地化成文案再下发：前端不再持有 noticeCode→文案这张表
        assertFalse(m.segments().get(1).text().isBlank());
        assertFalse(m.segments().get(1).text().contains("iterStop"));
        assertEquals("text", m.segments().get(2).type());
        assertNull(m.segments().get(2).ms(), "正文段不带耗时");
    }

    @Test
    @DisplayName("setTheme 载荷：可预览类型清单以 previewLangs 数组送达前端，内容与 Java 白名单同源")
    void previewLangsTravelsWithLabels() throws Exception {
        String js = ChatMessagePayload.jsValue(ChatUiLabels.fromI18n());
        // 前端只认 labels.previewLangs 这一个形状：字段缺失或不是数组，代码块预览钮会整体消失
        JsonNode arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(js).get("previewLangs");
        assertTrue(arr.isArray(), js);
        List<String> pushed = new ArrayList<>();
        arr.forEach(n -> pushed.add(n.asText()));
        assertEquals(ArtifactPreviewWindow.PREVIEW_LANGS, pushed);
        // 与谓词一致：清单里的每一项都必须真能预览
        pushed.forEach(lang -> assertTrue(ArtifactPreviewWindow.isPreviewable(lang), lang));
    }

    @Test
    @DisplayName("setTheme 载荷：工作日志要用的文案键齐备，缺一个前端就显示英文兜底或空标题")
    void turnLabelsAreAllPushed() throws Exception {
        JsonNode labels = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(ChatMessagePayload.jsValue(ChatUiLabels.fromI18n()));
        for (String key : List.of("thinking", "thinkingDone", "thinkingElapsed", "toolCount", "toolFailed",
                "toolRunning", "toolInput", "toolOutput", "artifactMore", "todoTitle", "todoProgress")) {
            assertTrue(labels.hasNonNull(key) && !labels.get(key).asText().isEmpty(), key);
        }
        // 耗时模板留 {0} 给前端填数：MessageFormat 在 Java 侧已把占位符原样透出
        assertTrue(labels.get("thinkingElapsed").asText().contains("{0}"), labels.toString());
        // 在途标题留 {0} 给工具名：槽位丢了，流式块头就只能显示不带名字的兜底串
        assertTrue(labels.get("toolRunning").asText().contains("{0}"), labels.toString());
    }

    @Test
    @DisplayName("jsValue：序列化失败即失败（返回 null 交调用方放弃），绝不回退成 JS 的 null 字面量")
    void jsValueFailureReturnsNullNotLiteral() {
        assertNull(ChatMessagePayload.jsValue(new Object()));
        // 字符串通道另有一套：脏值退化成空串仍可安全拼进脚本，不会让整条 executeScript 语法错
        assertEquals("\"\"", ChatMessagePayload.jsString(null));
    }
}
