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

package com.gettyio.gim.agent.ui.util;

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
}
