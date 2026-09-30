package com.gettyio.gim.agent.core.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 能力清单的持久化编解码：agents.skills_json / agents.mcp_servers_json 存的是编码 JSON 数组
 * （如 {@code ["translate","weekly-report"]}），空能力写成 {@code []}。
 * <p>清单是严格白名单：未声明即无能力。列值为 NULL（字段从未写入）与 {@code []} 在运行时
 * 完全等价，都按空清单处理。</p>
 * <p>解析容错：非法 JSON 只告警并按空清单处理（fail-closed），不会因为一条脏数据把整个智能体
 * 装配成「拥有全部能力」的意外状态。也接受用户手写的逗号分隔清单，避免 MCP 参数栏误填导致能力全丢。</p>
 */
public final class CapabilityCodec {

    private static final Logger log = LoggerFactory.getLogger(CapabilityCodec.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 空清单的持久化形式：无能力时统一写这个值，而不是留 NULL */
    public static final String EMPTY_LIST = "[]";

    private CapabilityCodec() {
    }

    /** 读编码清单；null/空/解析失败均返回空列表（保持声明顺序、去重）。 */
    public static List<String> readCodes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        String text = json.trim();
        if (text.startsWith("[")) {
            try {
                JsonNode node = MAPPER.readTree(text);
                List<String> codes = new ArrayList<>();
                for (JsonNode item : node) {
                    addCode(codes, item.asText());
                }
                return distinct(codes);
            } catch (Exception e) {
                log.warn("能力清单 JSON 解析失败，按空清单处理: {}", text, e);
                return List.of();
            }
        }
        // 容错：逗号/空格分隔的手写清单
        List<String> codes = new ArrayList<>();
        for (String part : text.split("[,;\\s]+")) {
            addCode(codes, part);
        }
        return distinct(codes);
    }

    /** 写编码清单；空集合写成 {@code []}。 */
    public static String writeCodes(Collection<String> codes) {
        ArrayNode array = MAPPER.createArrayNode();
        Set<String> unique = normalize(codes);
        unique.forEach(array::add);
        try {
            return MAPPER.writeValueAsString(array);
        } catch (Exception e) {
            // ArrayNode 序列化不会失败，兜底即可
            return EMPTY_LIST;
        }
    }

    /** 读 {@code {"K":"V"}} 形态的字符串映射（MCP 环境变量 / 请求头）；解析失败返回空映射。 */
    public static Map<String, String> readStringMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = MAPPER.readTree(json.trim());
            if (!node.isObject()) {
                log.warn("期望 JSON 对象但得到其他类型，忽略: {}", json);
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> out.put(entry.getKey(), entry.getValue().asText()));
            return out;
        } catch (Exception e) {
            log.warn("JSON 对象解析失败，忽略该字段: {}", json, e);
            return Map.of();
        }
    }

    /**
     * 读命令参数：优先按 JSON 数组解析；不是数组时按空白切分并保留引号包裹的整体，
     * 使 {@code -y "some arg"} 与 {@code ["-y","some arg"]} 两种写法都能生效。
     */
    public static List<String> readArgs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        String text = json.trim();
        if (text.startsWith("[")) {
            return readCodes(text);
        }
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (Character.isWhitespace(c) && !inQuote) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            args.add(current.toString());
        }
        return args;
    }

    /** 去空白、去重，保持声明顺序。 */
    public static Set<String> normalize(Collection<String> codes) {
        Set<String> out = new LinkedHashSet<>();
        if (codes == null) {
            return out;
        }
        for (String code : codes) {
            addCode(out, code);
        }
        return out;
    }

    private static void addCode(Collection<String> sink, String code) {
        if (code == null) {
            return;
        }
        String trimmed = code.trim();
        if (!trimmed.isEmpty() && !sink.contains(trimmed)) {
            sink.add(trimmed);
        }
    }

    private static List<String> distinct(List<String> codes) {
        return new ArrayList<>(new LinkedHashSet<>(codes));
    }
}
