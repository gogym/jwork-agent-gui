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

package com.gettyio.jwork.agent.domain.entity;

/**
 * token 用量聚合行：某个维度分组（全局/智能体/模型/会话）下的累计值。
 *
 * @param groupKey        分组键：全局为 {@code "ALL"}，其余为 agent_code / model_code / session_id（可能为 null，代表脏数据未标注）
 * @param tokensInput     输入 token 合计
 * @param tokensOutput    输出 token 合计
 * @param tokensCached    输入中命中提示缓存的 token 合计（{@code tokensInput} 的子集）
 * @param llmCalls        LLM 调用次数合计（一次模型调用计一次，含工具循环多轮）
 * @param llmElapsedMillis 模型调用累计耗时（毫秒）
 * @param messageCount    参与统计的消息条数
 * @param estCost         按消息所属模型的单价估算的成本（未配单价的消息计入 0）
 *
 * @author gogym
 */
public record UsageAggregate(String groupKey, int tokensInput, int tokensOutput, int tokensCached,
                             int llmCalls, long llmElapsedMillis, int messageCount, double estCost) {

    /** 输入 + 输出总量。 */
    public int totalTokens() {
        return tokensInput + tokensOutput;
    }

    /** 空行：某维度尚无任何消息时用它兜底（成本 0）。 */
    public static UsageAggregate empty(String groupKey) {
        return new UsageAggregate(groupKey, 0, 0, 0, 0, 0L, 0, 0.0);
    }
}
