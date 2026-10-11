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

import com.gettyio.gim.agent.domain.enums.PermissionDecision;
import lombok.Getter;

import java.util.concurrent.CompletableFuture;

/**
 * 工具调用高危确认请求（HITL）：由拦截链产生，UI 弹框，用户决策回填 future。
 *
 * @author gogym
 */
@Getter
public class ToolConfirmRequest {

    private final String requestId;
    private final String sessionId;
    private final String toolName;
    private final String toolInput;
    private final String riskSummary;

    /** UI 线程完成后写入决策 */
    private final CompletableFuture<PermissionDecision> outcome = new CompletableFuture<>();

    public ToolConfirmRequest(String requestId, String sessionId, String toolName, String toolInput, String riskSummary) {
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.toolInput = toolInput;
        this.riskSummary = riskSummary;
    }

    public void complete(PermissionDecision decision) {
        outcome.complete(decision);
    }
}
