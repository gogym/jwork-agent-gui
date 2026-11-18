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

package com.gettyio.jwork.agent.ui.util;

import com.gettyio.jwork.agent.core.chat.StreamEvent;
import com.gettyio.jwork.agent.ui.i18n.I18n;

import java.util.Locale;

/**
 * UI 层通用格式化工具：跨页面共享、不依赖场景图与窗口状态的纯函数格式化。
 * <p>字节大小统一由 {@link #humanSize(long)} 输出「B / KB / MB / GB」四档；
 * 数值固定使用 {@link Locale#ROOT}，保证小数点形态在各语言环境下一致，
 * 避免同一数值因区域设置不同而显示不一致。</p>
 *
 * @author gogym
 */
public final class UiFormat {

    private UiFormat() {
    }

    /**
     * 字节数转人类可读大小：小于 1 KB 显示「N B」，其余按 KB / MB / GB 保留一位小数。
     * <p>附件芯片、聊天消息载荷、知识库文档列表等多处共用同一口径。</p>
     */
    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
    }

    /**
     * 运行体提示（{@code NOTICE} 事件）渲染：core 侧没有资源包，只能发「编码 + 逗号分隔参数」，
     * 文案在此按 {@code chat.<编码>} 取译文填充；编码为空则原样显示参数。
     */
    public static String notice(StreamEvent event) {
        return noticeText(event.getToolName(), event.getText());
    }

    /**
     * 同上，但入参取自落盘的时序分段而非事件：历史重放按当前语言重新解析文案，
     * 换语言后旧会话的旁白跟着换（落盘的只是码与参数）。
     */
    public static String noticeText(String code, String payload) {
        String args0 = payload == null ? "" : payload;
        if (code == null || code.isBlank()) {
            return args0;
        }
        Object[] args = args0.isEmpty() ? new Object[0] : args0.split(",");
        return I18n.t("chat." + code, args);
    }
}
