package com.gettyio.gim.agent.core.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 图片附件清单的 JSON 编解码：写入 / 读取 {@code conversations.metadata}（数组
 * {@code [{"name":..,"mime":..,"path":..}]}）。
 * <p>解析容错：脏数据只告警并按「无附件」处理，不会因一条坏 metadata 让整条历史消息渲染失败。</p>
 */
public final class AttachmentCodec {

    private static final Logger log = LoggerFactory.getLogger(AttachmentCodec.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AttachmentCodec() {
    }

    /** 写清单 JSON；空清单返回 {@code null}（metadata 列留空，不写 "[]"）。 */
    public static String write(List<StoredAttachment> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(items);
        } catch (Exception e) {
            log.warn("附件清单序列化失败，按无附件落库", e);
            return null;
        }
    }

    /** 读清单；null/空/解析失败均返回空列表。 */
    public static List<StoredAttachment> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<StoredAttachment>>() {
            });
        } catch (Exception e) {
            log.warn("附件清单解析失败，按无附件处理: {}", json, e);
            return List.of();
        }
    }
}
