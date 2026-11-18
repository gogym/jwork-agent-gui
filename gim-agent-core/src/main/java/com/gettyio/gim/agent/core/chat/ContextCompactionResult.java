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

package com.gettyio.gim.agent.core.chat;

/**
 * 一次「压缩当前会话」的执行结果。
 *
 * @param compacted       是否实际执行了压缩；{@code false} 表示会话过短或上下文为空，无需压缩
 * @param estimatedTokens 压缩后上下文的 token 粗估（框架计数器口径，仅供 UI 即时展示）
 * @param localFallback   是否走了本地降级压缩（模型不可用时无摘要归档）；UI 据此区分回显文案
 *
 * @author gogym
 */
public record ContextCompactionResult(boolean compacted, int estimatedTokens, boolean localFallback) {

    /** 未压缩（会话过短 / 上下文为空 / 不支持）的通用结果。 */
    public static ContextCompactionResult skipped() {
        return new ContextCompactionResult(false, 0, false);
    }
}
