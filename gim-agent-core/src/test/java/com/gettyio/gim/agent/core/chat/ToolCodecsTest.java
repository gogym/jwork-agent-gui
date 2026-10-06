package com.gettyio.gim.agent.core.chat;

import com.gettyio.gim.agent.core.chat.ToolCallRecordCodec.ToolCallRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具编解码往返：{@link ToolCallRecordCodec}（展示用，含 output/ok/duration）与
 * {@link ToolCallsCodec}（HITL 用，含 name/input）对脏输入的宽容降级。
 */
class ToolCodecsTest {

    @Test
    @DisplayName("ToolCallRecordCodec 往返保留 name/input/output/ok/duration")
    void recordRoundTrip() {
        ToolCallRecord r = new ToolCallRecord("edit_file", "{\"path\":\"a\"}", "done", true, 15L);
        String json = ToolCallRecordCodec.encode(List.of(r));
        List<ToolCallRecord> back = ToolCallRecordCodec.decode(json);
        assertEquals(1, back.size());
        ToolCallRecord b = back.get(0);
        assertEquals("edit_file", b.name());
        assertEquals("done", b.output());
        assertTrue(b.ok());
        assertEquals(15L, b.durationMillis());
    }

    @Test
    @DisplayName("ToolCallRecordCodec 空/null 编解码降级为空")
    void recordEmptyAndDirty() {
        assertEquals("[]", ToolCallRecordCodec.encode(List.of()));
        assertEquals("[]", ToolCallRecordCodec.encode(null));
        assertTrue(ToolCallRecordCodec.decode(null).isEmpty());
        assertTrue(ToolCallRecordCodec.decode("garbage").isEmpty());
        // 成败字段缺省视为 false
        List<ToolCallRecord> partial = ToolCallRecordCodec.decode("[{\"name\":\"t\"}]");
        assertFalse(partial.get(0).ok());
    }

    @Test
    @DisplayName("ToolCallsCodec.decode 解析 name/input，取字符串入参字段")
    void toolCallsDecode() {
        String json = "[{\"name\":\"execute\",\"input\":{\"command\":\"ls\"}}]";
        List<ToolCallsCodec.ToolCallView> views = ToolCallsCodec.decode(json);
        assertEquals(1, views.size());
        assertEquals("execute", views.get(0).name());
        assertEquals("ls", views.get(0).text("command"));
    }

    @Test
    @DisplayName("ToolCallsCodec 对非数组/空白/null 宽容返回空列表")
    void toolCallsTolerant() {
        assertTrue(ToolCallsCodec.decode(null).isEmpty());
        assertTrue(ToolCallsCodec.decode("").isEmpty());
        assertTrue(ToolCallsCodec.decode("execute({})").isEmpty()); // 退化文本非 '[' 开头
    }
}
