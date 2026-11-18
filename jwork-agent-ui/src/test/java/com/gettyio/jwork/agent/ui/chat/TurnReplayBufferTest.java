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

package com.gettyio.jwork.agent.ui.chat;

import com.gettyio.jwork.agent.core.chat.StreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TurnReplayBuffer} 纯逻辑单测：在途轮事件缓冲要在「切页再回来」时重建出与实时同形的过程，
 * 因此校验增量合并只发生在相邻同类型文本之间、离散事件保持原序、快照与后续到达隔离、换轮清空。
 *
 * @author gogym
 */
class TurnReplayBufferTest {

    private static final String SID = "s-1";

    @Test
    @DisplayName("相邻同类型增量就地合并：正文与思考各自成段，互不折叠")
    void mergesAdjacentDeltasOfSameType() {
        TurnReplayBuffer buf = new TurnReplayBuffer();
        buf.record(List.of(StreamEvent.textDelta(SID, "退款"), StreamEvent.textDelta(SID, "订单")));
        buf.record(List.of(StreamEvent.textDelta(SID, "差异统计完成")));
        buf.record(List.of(StreamEvent.thinkingDelta(SID, "先看"), StreamEvent.thinkingDelta(SID, "看明细表")));

        List<StreamEvent> got = buf.snapshot();
        assertEquals(2, got.size(), "同类型连续增量各自压成一条");
        assertEquals("TEXT_DELTA", got.get(0).getType().name());
        assertEquals("退款订单差异统计完成", got.get(0).getText());
        assertEquals("THINKING_DELTA", got.get(1).getType().name());
        assertEquals("先看看明细表", got.get(1).getText());
    }

    @Test
    @DisplayName("离散事件打断合并：工具/旁白保序，事件字段不被抹平")
    void discreteEventsBreakTheRunAndKeepOrder() {
        TurnReplayBuffer buf = new TurnReplayBuffer();
        buf.record(List.of(
                StreamEvent.thinkingDelta(SID, "查一下"),
                StreamEvent.toolStart(SID, "grep_files", "{\"pattern\":\"refund\"}"),
                StreamEvent.toolEnd(SID, "grep_files", "{\"pattern\":\"refund\"}", "3 hits", 120L, true),
                StreamEvent.notice(SID, "continue_steps", "24,30"),
                StreamEvent.textDelta(SID, "结果"),
                StreamEvent.textDelta(SID, "如下")));

        List<StreamEvent> got = buf.snapshot();
        assertEquals(5, got.size(), "相邻的两段正文合并，其余按到达顺序各留一条");
        assertEquals("THINKING_DELTA", got.get(0).getType().name());
        assertEquals("TOOL_START", got.get(1).getType().name());
        assertEquals("grep_files", got.get(1).getToolName());
        assertEquals("TOOL_END", got.get(2).getType().name());
        assertEquals(120L, got.get(2).getToolDurationMillis());
        assertTrue(got.get(2).isToolOk());
        assertEquals("NOTICE", got.get(3).getType().name());
        assertEquals("continue_steps", got.get(3).getToolName());
        assertEquals("结果如下", got.get(4).getText());
    }

    @Test
    @DisplayName("快照隔离：snapshot 之后再到的增量不回改已发出的那份事件")
    void snapshotIsDetachedCopy() {
        TurnReplayBuffer buf = new TurnReplayBuffer();
        buf.record(List.of(StreamEvent.textDelta(SID, "A")));
        List<StreamEvent> snap = buf.snapshot();
        buf.record(List.of(StreamEvent.textDelta(SID, "B")));

        List<StreamEvent> now = buf.snapshot();
        assertNotSame(now, snap);
        assertEquals(1, snap.size());
        assertEquals("A", snap.get(0).getText(), "合并是换掉缓冲里的对象，不能就地改写已交给重放方的事件");
        assertEquals(1, now.size());
        assertEquals("AB", now.get(0).getText());
        assertNotSame(snap.get(0), now.get(0));
        assertThrows(UnsupportedOperationException.class, () -> snap.add(StreamEvent.complete(SID)));
    }

    @Test
    @DisplayName("换轮清空：上一轮的过程不会接到新一轮气泡上")
    void clearResetsForNextTurn() {
        TurnReplayBuffer buf = new TurnReplayBuffer();
        buf.record(List.of(StreamEvent.textDelta(SID, "上一轮"), StreamEvent.complete(SID)));
        assertEquals(2, buf.snapshot().size(), "COMPLETE 也照记，重放时事件类型对得上");

        buf.clear();
        assertTrue(buf.isEmpty());
        assertTrue(buf.snapshot().isEmpty());

        buf.record(List.of(StreamEvent.thinkingDelta(SID, "新一轮")));
        assertEquals(1, buf.snapshot().size());
        assertEquals("新一轮", buf.snapshot().get(0).getText());
    }

    @Test
    @DisplayName("脏输入不炸：空批次与批内 null 直接跳过")
    void ignoresNullBatchAndNullItems() {
        TurnReplayBuffer buf = new TurnReplayBuffer();
        buf.record(null);
        assertTrue(buf.isEmpty());

        buf.record(Arrays.asList(StreamEvent.textDelta(SID, "只有"), null, StreamEvent.textDelta(SID, "这两段")));
        assertEquals(1, buf.snapshot().size());
        assertEquals("只有这两段", buf.snapshot().get(0).getText());
    }
}
