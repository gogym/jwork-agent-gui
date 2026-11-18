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

import java.util.ArrayList;
import java.util.List;

/**
 * 在途轮的可回放事件缓冲：按推送顺序记下流事件，供视图重建同一轮。
 * <p>助手回答要等整轮跑完才落库，而整页重放只画已落库的消息，且 {@code ChatWebView.clear()/
 * renderHistory()} 会清掉前端的临时气泡表——切页或换会话再回来时，没有这份缓冲就看不到
 * 正在滚的思考与工具过程，只能等本轮结束「啪」地出现。</p>
 * <p>连续到达的正文/思考增量按类型就地合并（一轮长应答的 delta 数以千计，逐条存等于把正文再抄一份），
 * 工具、旁白、授权等离散事件保持原序，重放走与实时完全相同的分派通道。</p>
 * <p>仅 JavaFX 应用线程读写（事件在 {@code Platform.runLater} 里落地），不做并发保护。</p>
 *
 * @author gogym
 */
public final class TurnReplayBuffer {

    private final List<StreamEvent> events = new ArrayList<>();

    /** 记入一批事件（与推给前端的批次同形）。 */
    public void record(List<StreamEvent> batch) {
        if (batch == null) {
            return;
        }
        for (StreamEvent e : batch) {
            if (e == null) {
                continue;
            }
            StreamEvent tail = events.isEmpty() ? null : events.get(events.size() - 1);
            if (tail != null && sameDeltaRun(tail, e)) {
                events.set(events.size() - 1, concat(tail, e));
            } else {
                events.add(e);
            }
        }
    }

    /** 快照（重放遍历用，避免与仍在到达的事件共用同一个 List）。 */
    public List<StreamEvent> snapshot() {
        return List.copyOf(events);
    }

    /** 本轮结束（完成/失败/停止）或尚未开始：清空，避免把上一轮的过程接到下一轮气泡上。 */
    public void clear() {
        events.clear();
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }

    /** 是否可与前一条合并为同一段文本增量。 */
    private static boolean sameDeltaRun(StreamEvent tail, StreamEvent e) {
        return e.getType() == tail.getType() && isTextDelta(e.getType())
                && e.getText() != null && tail.getText() != null;
    }

    private static boolean isTextDelta(StreamEventType type) {
        return type == StreamEventType.TEXT_DELTA || type == StreamEventType.THINKING_DELTA;
    }

    private static StreamEvent concat(StreamEvent a, StreamEvent b) {
        String merged = a.getText() + b.getText();
        return a.getType() == StreamEventType.TEXT_DELTA
                ? StreamEvent.textDelta(a.getSessionId(), merged)
                : StreamEvent.thinkingDelta(a.getSessionId(), merged);
    }
}
