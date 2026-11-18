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
import com.gettyio.jwork.agent.domain.enums.StreamEventType;
import com.gettyio.jwork.agent.ui.chat.payload.WebChatTool;
import com.gettyio.jwork.agent.ui.i18n.I18n;
import com.gettyio.jwork.agent.ui.util.UiFormat;

import java.util.List;

/**
 * 一帧窗口内流式事件的分派器：正文、思考、工具、旁白各走各的通道，段序与落盘分段一致，
 * 收尾整页重放时同一轮不会重新排版。
 * <p>同类连续增量攒成一次回调（一帧 33ms 里可能挤进几十个思考 token，逐个交出就是前端跑几十遍重排），
 * 但类型边界处必须先交出另一类的攒量：下发顺序即事件真实顺序，否则帧内「思考→正文→思考」
 * 会被排成「思考+思考→正文」，后半段思考并进上面未封口的思考行、正文落到工具卡下方，
 * 收尾重放时整轮跳版。</p>
 *
 * @author gogym
 */
public final class ChatStreamDispatcher {

    private ChatStreamDispatcher() {
    }

    /** 分派出口：id 由调用方绑定，实现侧只关心内容。 */
    public interface Sink {

        void text(String text);

        void thinking(String text);

        void toolStart(WebChatTool tool);

        void toolEnd(WebChatTool tool);

        void notice(String text);
    }

    /** 按事件真实顺序分派一帧；空批次与缺字段的脏事件直接跳过。 */
    public static void dispatch(List<StreamEvent> batch, Sink sink) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        StringBuilder frame = new StringBuilder();
        StringBuilder think = new StringBuilder();
        for (StreamEvent e : batch) {
            if (e == null) {
                continue;
            }
            StreamEventType type = e.getType();
            if (type == StreamEventType.THINKING_DELTA) {
                // 思考会封掉前端的在途正文块：先把攒下的正文交出去，思考行才开在正确位置
                flushFrame(frame, sink);
                if (e.getText() != null) {
                    think.append(e.getText());
                }
                continue;
            }
            // 其余事件在前端都会结束当前思考段：先把攒下的增量交出去，段序才与落盘分段一致
            flushThinking(think, sink);
            if (type == StreamEventType.TEXT_DELTA && e.getText() != null) {
                frame.append(e.getText());
                continue;
            }
            // 工具/旁白会开新过程块：攒下的正文先落地，才不会被排到工具卡下方
            flushFrame(frame, sink);
            if (type == StreamEventType.TOOL_START && e.getToolName() != null && e.getToolInput() != null) {
                // 带真实入参的这次 TOOL_START 才是「即将执行」：先在过程区亮一行在途记录，长耗时工具不至于整块静默
                sink.toolStart(new WebChatTool(e.getToolName(), e.getToolInput(), null, false, 0L));
            } else if (type == StreamEventType.TOOL_END && e.getToolName() != null) {
                sink.toolEnd(new WebChatTool(e.getToolName(), e.getToolInput(),
                        e.getToolOutput(), e.isToolOk(), e.getToolDurationMillis()));
            } else if (type == StreamEventType.CONFIRM_REQUEST && e.getToolName() != null) {
                // 授权请求只在实时呈现（弹窗当场已处理完），不并入助手正文落盘
                sink.notice(I18n.t("chat.toolConfirm", e.getToolName()));
            } else if (type == StreamEventType.NOTICE && e.getToolName() != null) {
                // 运行体旁白（步数触顶自动续跑、子代理启停等）：随分段落盘，重放不再丢
                sink.notice(UiFormat.notice(e));
            }
        }
        flushThinking(think, sink);
        flushFrame(frame, sink);
    }

    /** 交出一段攒下的思考增量；空段不发回调。 */
    private static void flushThinking(StringBuilder think, Sink sink) {
        if (think.length() > 0) {
            sink.thinking(think.toString());
            think.setLength(0);
        }
    }

    /** 交出一段攒下的正文增量；空段不发回调。 */
    private static void flushFrame(StringBuilder frame, Sink sink) {
        if (frame.length() > 0) {
            sink.text(frame.toString());
            frame.setLength(0);
        }
    }
}