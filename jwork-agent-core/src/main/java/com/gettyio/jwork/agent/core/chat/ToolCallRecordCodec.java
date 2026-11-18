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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 助手消息「工具调用」列的编解码：把一轮对话里每次工具调用的可折叠详情
 * （工具名、入参、输出摘要、成败、耗时）序列化为 JSON 数组落 {@code conversations.tool_calls} 列，
 * 历史重放时还原为同样的结构。与 HITL 用的 {@link ToolCallsCodec}（只承载 name/input）分工不同，
 * 本编解码面向「展示 + 复盘」，故多带 output/ok/duration 三个字段。
 * <p>解码对解析失败宽容返回空列表——渲染层不能因一列脏数据而崩溃。</p>
 *
 * @author gogym
 */
public final class ToolCallRecordCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolCallRecordCodec() {
    }

    /** 单次工具调用的落盘/渲染视图。 */
    public record ToolCallRecord(String name, String input, String output, boolean ok, long durationMillis) {
    }

    /** 编码为 JSON 数组文本；空列表返回 {@code []}，序列化失败退化为 {@code []}（不阻断落盘）。 */
    public static String encode(List<ToolCallRecord> records) {
        if (records == null || records.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> raw = new ArrayList<>(records.size());
        for (ToolCallRecord r : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.name());
            m.put("input", r.input());
            m.put("output", r.output());
            m.put("ok", r.ok());
            m.put("durationMs", r.durationMillis());
            raw.add(m);
        }
        try {
            return MAPPER.writeValueAsString(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "[]";
        }
    }

    /** 解码 {@link #encode} 产物；非 JSON 或解析失败返回空列表。 */
    public static List<ToolCallRecord> decode(String json) {
        if (json == null || json.isBlank() || json.charAt(0) != '[') {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = MAPPER.readValue(json, new TypeReference<>() {
            });
            List<ToolCallRecord> out = new ArrayList<>(raw.size());
            for (Map<String, Object> m : raw) {
                out.add(new ToolCallRecord(
                        str(m.get("name")),
                        str(m.get("input")),
                        str(m.get("output")),
                        Boolean.TRUE.equals(m.get("ok")),
                        m.get("durationMs") instanceof Number n ? n.longValue() : 0L));
            }
            return out;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of();
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
