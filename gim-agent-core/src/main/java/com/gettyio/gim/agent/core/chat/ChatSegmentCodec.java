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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 助手消息时序分段的 JSON 编解码：一段 {@code text / thinking / tools / notice} 按流式事件的发生
 * 顺序合并而成，落盘进 {@code conversations.metadata.segments}，历史重放时由渲染层按段还原当时的
 * 交错布局。
 * <p>分段是「一轮的真相」的唯一来源：流式与重放共用同一套段序，收尾重放才不会让已上屏的轮次重新排版。
 * 因此只要本轮产生了分段就整份落盘，不再按段数取舍。</p>
 * <p>思考段带 {@code durationMillis}（该段思考实际耗时），前端只露「思考 · Ns」一行、点开才给全文；
 * 旁白段带 {@code noticeCode}，文案在渲染期按当前语言解析，落盘的只是码与参数——换语言后历史仍跟着换。</p>
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
     * @param type           {@link #TEXT} / {@link #THINKING} / {@link #TOOLS} / {@link #NOTICE}
     * @param text           text/thinking/notice 段的文本（notice 段为旁白参数，逗号分隔）；tools 段为 null
     * @param tools          tools 段的调用清单；其余段为空列表
     * @param durationMillis thinking 段的思考耗时（毫秒）；其余段为 null，序列化整体省略
     * @param noticeCode     notice 段的旁白码（渲染层据此查 i18n 文案）；其余段为 null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatSegment(String type, String text, List<ToolCallRecordCodec.ToolCallRecord> tools,
                              @JsonInclude(JsonInclude.Include.NON_NULL) Long durationMillis,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String noticeCode) {

        public static final String TEXT = "text";
        public static final String THINKING = "thinking";
        public static final String TOOLS = "tools";
        public static final String NOTICE = "notice";

        public ChatSegment {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }

        public static ChatSegment ofText(String text) {
            return new ChatSegment(TEXT, text, null, null, null);
        }

        /** 新开一段思考：耗时未知，由 {@link #withDuration} 在该段结束时补记。 */
        public static ChatSegment ofThinking(String text) {
            return new ChatSegment(THINKING, text, null, null, null);
        }

        public static ChatSegment ofTools(List<ToolCallRecordCodec.ToolCallRecord> tools) {
            return new ChatSegment(TOOLS, null, tools, null, null);
        }

        /** 运行体旁白：码 + 原始参数（未本地化），文案留给渲染层。 */
        public static ChatSegment ofNotice(String code, String args) {
            return new ChatSegment(NOTICE, args, null, null, code);
        }

        /** 同文本、附带耗时副本：思考段收尾时用。 */
        public ChatSegment withDuration(long millis) {
            return new ChatSegment(type, text, tools, Math.max(0L, millis), noticeCode);
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
        if (ChatSegment.NOTICE.equals(s.type())) {
            return s.noticeCode() != null && !s.noticeCode().isBlank();
        }
        return s.text() != null && !s.text().isEmpty();
    }
}
