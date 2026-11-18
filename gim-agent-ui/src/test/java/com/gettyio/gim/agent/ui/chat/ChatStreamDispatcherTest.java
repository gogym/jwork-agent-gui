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

package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.core.chat.StreamEvent;
import com.gettyio.gim.agent.ui.chat.payload.WebChatTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatStreamDispatcher} 纯逻辑单测：帧内分派顺序必须等于事件真实顺序
 * （前端靠正文/工具事件封思考行、开过程块，乱序会让思考并段、正文落错位置），
 * 同时同类连续增量仍要合并成一次回调。
 *
 * @author gogym
 */
class ChatStreamDispatcherTest {

    private static final String SID = "s-1";

    /** 记录回调顺序与内容：前缀标通道，工具只记名字与成败。 */
    private static final class RecordingSink implements ChatStreamDispatcher.Sink {

        final List<String> calls = new ArrayList<>();

        @Override
        public void text(String text) {
            calls.add("text:" + text);
        }

        @Override
        public void thinking(String text) {
            calls.add("think:" + text);
        }

        @Override
        public void toolStart(WebChatTool tool) {
            calls.add("start:" + tool.name());
        }

        @Override
        public void toolEnd(WebChatTool tool) {
            calls.add("end:" + tool.name() + (tool.ok() ? "" : "!"));
        }

        @Override
        public void notice(String text) {
            calls.add("notice");
        }
    }

    @Test
    @DisplayName("帧内思考→正文→思考：按真实顺序三段交出，后半段思考不并进上面未封口的思考行")
    void interleavedThinkingAndTextKeepTrueOrder() {
        RecordingSink sink = new RecordingSink();
        ChatStreamDispatcher.dispatch(List.of(
                StreamEvent.thinkingDelta(SID, "先查环境"),
                StreamEvent.textDelta(SID, "开始生成"),
                StreamEvent.thinkingDelta(SID, "校验列位")), sink);

        assertEquals(List.of("think:先查环境", "text:开始生成", "think:校验列位"), sink.calls);
    }

    @Test
    @DisplayName("帧内正文先于工具：正文先落地，才不会被排到工具卡下方")
    void textBeforeToolEventsIsFlushedFirst() {
        RecordingSink sink = new RecordingSink();
        ChatStreamDispatcher.dispatch(List.of(
                StreamEvent.textDelta(SID, "脚本已写好"),
                StreamEvent.toolStart(SID, "execute", "{\"command\":\"python\"}"),
                StreamEvent.toolEnd(SID, "execute", "{\"command\":\"python\"}", "ok", 120L, true),
                StreamEvent.thinkingDelta(SID, "核对数字")), sink);

        assertEquals(List.of("text:脚本已写好", "start:execute", "end:execute", "think:核对数字"), sink.calls);
    }

    @Test
    @DisplayName("同类连续增量合并：几十个 token 攒成一次回调，类型边界才断开")
    void adjacentDeltasOfSameTypeMergeIntoOneCall() {
        RecordingSink sink = new RecordingSink();
        ChatStreamDispatcher.dispatch(List.of(
                StreamEvent.thinkingDelta(SID, "先看"),
                StreamEvent.thinkingDelta(SID, "看明细"),
                StreamEvent.textDelta(SID, "结果"),
                StreamEvent.textDelta(SID, "如下")), sink);

        assertEquals(List.of("think:先看看明细", "text:结果如下"), sink.calls);
    }

    @Test
    @DisplayName("工具事件打断思考：前后两段思考各自成行，在途工具行保序")
    void toolEventsSplitThinkingRuns() {
        RecordingSink sink = new RecordingSink();
        ChatStreamDispatcher.dispatch(List.of(
                StreamEvent.thinkingDelta(SID, "想一下"),
                StreamEvent.toolStart(SID, "edit_file", "{\"path\":\"a.py\"}"),
                StreamEvent.thinkingDelta(SID, "再想一下"),
                StreamEvent.toolEnd(SID, "edit_file", "{\"path\":\"a.py\"}", "done", 80L, true)), sink);

        assertEquals(List.of("think:想一下", "start:edit_file", "think:再想一下", "end:edit_file"), sink.calls);
    }

    @Test
    @DisplayName("旁白与授权请求走 notice 通道且保序，不混进正文")
    void noticeAndConfirmKeepOrder() {
        RecordingSink sink = new RecordingSink();
        ChatStreamDispatcher.dispatch(List.of(
                StreamEvent.textDelta(SID, "继续"),
                StreamEvent.notice(SID, "continue_steps", "24,30"),
                StreamEvent.confirmRequest(SID, "execute", "{}"),
                StreamEvent.textDelta(SID, "执行")), sink);

        assertEquals(List.of("text:继续", "notice", "notice", "text:执行"), sink.calls);
    }

    @Test
    @DisplayName("脏输入不炸：空批次、批内 null、缺入参的 TOOL_START 与无文本增量直接跳过")
    void ignoresDirtyEvents() {
        RecordingSink sink = new RecordingSink();
        ChatStreamDispatcher.dispatch(null, sink);
        ChatStreamDispatcher.dispatch(List.of(), sink);
        assertTrue(sink.calls.isEmpty());

        ChatStreamDispatcher.dispatch(Arrays.asList(
                null,
                StreamEvent.thinkingDelta(SID, null),
                StreamEvent.textDelta(SID, null),
                StreamEvent.toolStart(SID, "execute", null),
                StreamEvent.textDelta(SID, "有效")), sink);
        assertEquals(List.of("text:有效"), sink.calls);
    }
}