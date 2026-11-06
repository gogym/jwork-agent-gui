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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 助手消息时序分段的 JSON 编解码：一段 {@code text / thinking / tools} 按流式事件的发生顺序
 * 合并而成，落盘进 {@code conversations.metadata.segments}，历史重放时按段还原「思考卡 →
 * 正文 → 工具组」的原始时序（对齐参考 UI 的交错呈现）。
 * <p>解码容错：脏数据只告警并返回空列表，渲染层不能因一段坏数据而崩溃。</p>
 *
 * @author gogym
 */
public final class ChatSegmentCodec {

    private static final Logger log = LoggerFactory.getLogger(ChatSegmentCodec.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private ChatSegmentCodec() {
    }

    /**
     * 一个时序段。
     *
     * @param type  {@link #TEXT} / {@link #THINKING} / {@link #TOOLS}
     * @param text  text/thinking 段的文本；tools 段为 null
     * @param tools tools 段的调用清单；其余段为空列表
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatSegment(String type, String text, List<ToolCallRecordCodec.ToolCallRecord> tools) {

        public static final String TEXT = "text";
        public static final String THINKING = "thinking";
        public static final String TOOLS = "tools";

        public ChatSegment {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }

        public static ChatSegment ofText(String text) {
            return new ChatSegment(TEXT, text, null);
        }

        public static ChatSegment ofThinking(String text) {
            return new ChatSegment(THINKING, text, null);
        }

        public static ChatSegment ofTools(List<ToolCallRecordCodec.ToolCallRecord> tools) {
            return new ChatSegment(TOOLS, null, tools);
        }
    }

    /** 编码为 JSON；空列表或序列化失败返回 {@code null}（metadata 里不写该键）。 */
    public static String encode(List<ChatSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(segments);
        } catch (Exception e) {
            log.warn("时序分段序列化失败，忽略", e);
            return null;
        }
    }

    /** 解码分段；null/空/解析失败均返回空列表。 */
    public static List<ChatSegment> decode(String json) {
        if (json == null || json.isBlank() || json.charAt(0) != '[') {
            return List.of();
        }
        try {
            List<ChatSegment> out = MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, ChatSegment.class));
            // 去掉空段：脏数据或空文本不参与渲染
            List<ChatSegment> clean = new ArrayList<>(out.size());
            for (ChatSegment s : out) {
                if (s != null && hasContent(s)) {
                    clean.add(s);
                }
            }
            return clean;
        } catch (Exception e) {
            log.warn("时序分段解析失败，按无分段处理", e);
            return List.of();
        }
    }

    /** 段是否带可渲染内容。 */
    public static boolean hasContent(ChatSegment s) {
        if (ChatSegment.TOOLS.equals(s.type())) {
            return !s.tools().isEmpty();
        }
        return s.text() != null && !s.text().isEmpty();
    }
}
