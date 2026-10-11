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

package com.gettyio.gim.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息（由 MVP AiConversation 去租户化移植）
 *
 * @author gogym
 */
@Data
public class ChatConversation {

    private Long id;

    private String sessionId;

    private String agentCode;

    /** user / assistant / system / tool，见 ConversationRole */
    private String role;

    private String content;

    /** 本轮累计的输入 token（含多次模型调用；仅 assistant 消息写入） */
    private Integer tokensInput;

    /** 本轮累计的输出 token */
    private Integer tokensOutput;

    /** 输入中命中提示缓存的 token（{@code tokensInput} 的子集，通常按折扣计费） */
    private Integer tokensCached;

    /** 本轮最后一次模型调用的 input token 数（≈轮末上下文真实体量，上下文占用条分子；{@code tokensInput} 是整轮累加值不可当体量用） */
    private Integer lastInputTokens;

    /** 本轮成功结束的 LLM 调用次数（含工具循环多轮；仅 assistant 消息写入） */
    private Integer llmCalls;

    /** 本轮模型调用累计耗时（毫秒） */
    private Long llmElapsedMillis;

    private String modelCode;

    /** 工具调用记录 JSON 数组 */
    private String toolCalls;

    /** 扩展元数据 JSON */
    private String metadata;

    private LocalDateTime createdAt;
}
