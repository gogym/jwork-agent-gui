package com.gettyio.gim.agent.core.chat;

import com.gettyio.gim.agent.core.chat.MessageMetadataCodec.MessageMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MessageMetadataCodec} 往返单测：图片/文件混合清单、中断标记、思考过程、空态省略、脏数据容错。
 */
class MessageMetadataCodecTest {

    @Test
    @DisplayName("无附件、未中断且无思考：write 返回 null（列留空）")
    void emptyWritesNull() {
        assertNull(MessageMetadataCodec.write(List.of(), false, null));
        assertNull(MessageMetadataCodec.write(null, false, null));
        // 空白思考同样视为无内容
        assertNull(MessageMetadataCodec.write(List.of(), false, "   "));
    }

    @Test
    @DisplayName("仅中断：read 还原 interrupted=true 无附件")
    void interruptedOnly() {
        String json = MessageMetadataCodec.write(List.of(), true, null);
        assertTrue(json.contains("interrupted"));
        MessageMetadata meta = MessageMetadataCodec.read(json);
        assertTrue(meta.interrupted());
        assertTrue(meta.attachments().isEmpty());
        assertNull(meta.thinking());
    }

    @Test
    @DisplayName("思考过程往返：thinking 完整保留（含换行），与中断标记共存")
    void thinkingRoundTrip() {
        String text = "先看报错信息，\n再定位到源码 47 行。";
        String json = MessageMetadataCodec.write(List.of(), true, text);
        assertNotNull(json);
        assertTrue(json.contains("thinking"));
        MessageMetadata meta = MessageMetadataCodec.read(json);
        assertEquals(text, meta.thinking());
        assertTrue(meta.interrupted());
        assertTrue(meta.attachments().isEmpty());
    }

    @Test
    @DisplayName("图片与文件混合清单往返：kind/sizeBytes 完整保留")
    void mixedAttachmentsRoundTrip() {
        StoredAttachment img = new StoredAttachment("a.png", "image/png", "s1/a.png",
                StoredAttachment.KIND_IMAGE, 1024L);
        StoredAttachment file = new StoredAttachment("b.pdf", "application/pdf", "s1/b.pdf",
                StoredAttachment.KIND_FILE, 2048L);
        String json = MessageMetadataCodec.write(List.of(img, file), false, null);

        MessageMetadata meta = MessageMetadataCodec.read(json);
        assertFalse(meta.interrupted());
        List<StoredAttachment> atts = meta.attachments();
        assertEquals(2, atts.size());
        StoredAttachment read = atts.stream().filter(StoredAttachment::isFile).findFirst().orElseThrow();
        assertEquals("b.pdf", read.name());
        assertEquals(2048L, read.sizeBytes());
        assertTrue(read.isFile());
    }

    @Test
    @DisplayName("脏数据解析失败按空元数据处理，不抛")
    void dirtyJsonFallsBackToEmpty() {
        MessageMetadata meta = MessageMetadataCodec.read("{not-json");
        assertFalse(meta.interrupted());
        assertTrue(meta.attachments().isEmpty());
        assertNull(meta.thinking());
    }

    @Test
    @DisplayName("三参构造默认按图片 kind（历史 metadata 兼容）")
    void legacyThreeArgDefaultsToImage() {
        StoredAttachment legacy = new StoredAttachment("c.jpg", "image/jpeg", "s1/c.jpg");
        assertFalse(legacy.isFile());
        assertEquals(StoredAttachment.KIND_IMAGE, legacy.kind());
    }
}
