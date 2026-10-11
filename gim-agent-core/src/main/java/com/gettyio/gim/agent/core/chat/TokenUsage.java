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
 * 一轮对话累计的模型用量（把 AgentScope 每次模型调用的 {@code ChatUsage} 累加后的结果）。
 * <p>除 token 外还记录本轮触发的 LLM 调用次数与累计耗时，供「LLM 调用统计」使用：
 * 一次 {@code ModelCallEndEvent} 计一次调用，其 {@code ChatUsage.getTime()}（秒）累加为耗时。</p>
 *
 * @param inputTokens   输入 token 合计
 * @param outputTokens  输出 token 合计
 * @param cachedTokens  输入中命中提示缓存的 token 合计（{@code inputTokens} 的子集）
 * @param calls         本轮成功结束的 LLM 调用次数
 * @param elapsedMillis 本轮模型调用累计耗时（毫秒，各次 {@code getTime()} 秒 × 1000 取整之和）
 * @param lastInputTokens 本轮最后一次模型调用的 input token 数（≈轮末上下文真实提示词体量，
 *                        上下文占用条以其为准；因工具循环多次调用时 {@code inputTokens} 是累加值，不可当体量用）；未记录为 0
 *
 * @author gogym
 */
public record TokenUsage(int inputTokens, int outputTokens, int cachedTokens,
                         int calls, long elapsedMillis, int lastInputTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0, 0L, 0);

    /** 是否一次用量都没记录（用于落库前判空，避免给消息写一堆 0）。 */
    public boolean isEmpty() {
        return inputTokens == 0 && outputTokens == 0 && cachedTokens == 0
                && calls == 0 && elapsedMillis == 0;
    }

    /**
     * 与另一次用量相加（跨 HITL 续跑时合并多段累加器）。
     * <p>{@code lastInputTokens} 取较新一侧的非零值——它代表「最后一次调用」，不做求和。</p>
     */
    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cachedTokens + other.cachedTokens,
                calls + other.calls,
                elapsedMillis + other.elapsedMillis,
                other.lastInputTokens != 0 ? other.lastInputTokens : lastInputTokens);
    }

    /** 输入 + 输出总量。 */
    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
