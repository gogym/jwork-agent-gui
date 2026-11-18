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
import io.agentscope.core.message.ToolUseBlock;

import java.util.List;
import java.util.Map;

/**
 * 工具调用的结构化编解码：把 RequireUserConfirmEvent 携带的 {@link ToolUseBlock} 列表
 * 序列化为 JSON 文本贯穿「拦截链 → HITL 弹框」，两端都能还原出工具名与入参字段
 * （如 {@code edit_file} 的 path/old_string/new_string），不再依赖 {@code Map.toString()} 的不可逆文本。
 * <p>格式：{@code [{"name":"edit_file","input":{"path":"a.txt", ...}}]}。
 * {@link #decode(String)} 对解析失败宽容返回空列表——拦截链与安全决策不能因编码异常而放行，
 * 但也不能因解析失败而崩溃。</p>
 *
 * @author gogym
 */
public final class ToolCallsCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolCallsCodec() {
    }

    /** 单个工具调用的解码视图。 */
    public record ToolCallView(String name, Map<String, Object> input) {

        /** 字符串型入参字段（缺失或非字符串返回 null）。 */
        public String text(String key) {
            Object v = input == null ? null : input.get(key);
            return v instanceof String s ? s : null;
        }

        /** 布尔型入参字段（兼容 Boolean 与 "true" 文本；缺失或非真值返回 false）。 */
        public boolean flag(String key) {
            Object v = input == null ? null : input.get(key);
            if (v instanceof Boolean b) {
                return b;
            }
            return v instanceof String s && "true".equalsIgnoreCase(s);
        }
    }

    /** 编码为 JSON 文本；序列化失败时退化为 {@code name(...)} 拼接的纯文本描述。 */
    public static String encode(List<ToolUseBlock> calls) {
        if (calls == null || calls.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(calls.stream()
                    .map(c -> Map.of("name", String.valueOf(c.getName()),
                            "input", c.getInput() == null ? Map.of() : c.getInput()))
                    .toList());
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            StringBuilder sb = new StringBuilder();
            for (ToolUseBlock call : calls) {
                if (!sb.isEmpty()) {
                    sb.append("; ");
                }
                sb.append(call.getName()).append('(').append(call.getInput()).append(')');
            }
            return sb.toString();
        }
    }

    /**
     * 单个调用的 JSON 文本：责任链按调用逐个裁决时用。
     * <p>整批文本只带第一个调用的工具名，策略的名字门槛（是不是 shell、是不是文件工具）
     * 于是对批内第 2 个以后的调用形同虚设——拆开重编，每个调用才都能被对的策略看到。</p>
     */
    public static String encodeSingle(ToolCallView call) {
        if (call == null) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(List.of(Map.of(
                    "name", String.valueOf(call.name()),
                    "input", call.input() == null ? Map.of() : call.input())));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return call.name() + "(" + call.input() + ")";
        }
    }

    /** 解码 {@link #encode} 产物；非 JSON（如退化文本）时返回空列表。 */
    public static List<ToolCallView> decode(String json) {        if (json == null || json.isBlank() || json.charAt(0) != '[') {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = MAPPER.readValue(json, new TypeReference<>() {
            });
            return raw.stream()
                    .map(m -> new ToolCallView(
                            String.valueOf(m.get("name")),
                            asMap(m.get("input"))))
                    .toList();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
