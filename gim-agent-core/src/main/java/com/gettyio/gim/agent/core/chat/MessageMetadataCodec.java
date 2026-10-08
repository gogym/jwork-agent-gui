package com.gettyio.gim.agent.core.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息级 {@code conversations.metadata} 的 JSON 编解码：统一承载附件清单、中断标记与思考过程。
 * <p>结构：{@code {"attachments":[{"name":..,"mime":..,"path":..}],"interrupted":true,"thinking":".."}}；
 * 全部字段缺省时列留空（返回 null，不写 "{}"）。解析容错：脏数据只告警并按「空元数据」处理，
 * 绝不因一条坏 metadata 让整条历史消息渲染失败。</p>
 */
public final class MessageMetadataCodec {

    private static final Logger log = LoggerFactory.getLogger(MessageMetadataCodec.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String KEY_ATTACHMENTS = "attachments";
    private static final String KEY_INTERRUPTED = "interrupted";
    private static final String KEY_THINKING = "thinking";

    private MessageMetadataCodec() {
    }

    /**
     * 消息元数据（读取侧视图）。
     *
     * @param attachments 图片/文件附件清单，永不为 null
     * @param interrupted 应答是否被中断（出错或用户停止），仅助手消息可能为 true
     * @param thinking    本轮模型的思考过程全文（仅助手消息可能非 null）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageMetadata(List<StoredAttachment> attachments, boolean interrupted, String thinking) {

        public MessageMetadata {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        /** 便捷视图：无思考过程（附件 + 中断标记）。 */
        public MessageMetadata(List<StoredAttachment> attachments, boolean interrupted) {
            this(attachments, interrupted, null);
        }
    }

    /** 写元数据 JSON；无附件、未中断且无思考时返回 {@code null}（metadata 列留空）。 */
    public static String write(List<StoredAttachment> attachments, boolean interrupted, String thinking) {
        boolean hasAttachments = attachments != null && !attachments.isEmpty();
        boolean hasThinking = thinking != null && !thinking.isBlank();
        if (!hasAttachments && !interrupted && !hasThinking) {
            return null;
        }
        Map<String, Object> node = new LinkedHashMap<>();
        if (hasAttachments) {
            node.put(KEY_ATTACHMENTS, attachments);
        }
        if (interrupted) {
            node.put(KEY_INTERRUPTED, true);
        }
        if (hasThinking) {
            node.put(KEY_THINKING, thinking);
        }
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("消息元数据序列化失败，按空元数据落库", e);
            return null;
        }
    }

    /** 读元数据；null/空/解析失败均返回空元数据（无附件、未中断）。 */
    public static MessageMetadata read(String json) {
        if (json == null || json.isBlank()) {
            return new MessageMetadata(List.of(), false);
        }
        try {
            return MAPPER.readValue(json, MessageMetadata.class);
        } catch (Exception e) {
            log.warn("消息元数据解析失败，按空元数据处理: {}", json, e);
            return new MessageMetadata(List.of(), false);
        }
    }

    /** 便捷：只取附件清单。 */
    public static List<StoredAttachment> readAttachments(String json) {
        return new ArrayList<>(read(json).attachments());
    }
}
