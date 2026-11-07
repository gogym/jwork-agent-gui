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

import java.util.concurrent.CompletableFuture;

/**
 * 工具执行确认回调：当 AgentScope 权限引擎对某个工具调用要求用户确认（HITL）时，
 * 网关调用本回调获取放行裁决（含拒绝理由）。
 * <p>定义在 core，由 tools 层的 {@code ToolSecurityFacade#check} 适配实现，
 * 保持 core 不反向依赖 tools。</p>
 *
 * @author gogym
 */
@FunctionalInterface
public interface ToolConfirmCallback {

    /**
     * @param sessionId    会话 ID
     * @param toolName     待确认的工具名
     * @param toolInput    工具入参的结构化 JSON（{@link ToolCallsCodec#encode}）
     * @param autoApprove  自动通过档：拦截链照常评估（黑名单仍 DENY），但 ASK 不弹窗直接放行
     * @return 异步裁决：放行，或带理由的拒绝（理由回喂给模型）
     */
    CompletableFuture<ConfirmOutcome> confirm(String sessionId, String toolName, String toolInput, boolean autoApprove);
}
