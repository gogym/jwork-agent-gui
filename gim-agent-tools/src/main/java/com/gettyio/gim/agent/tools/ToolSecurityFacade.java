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

package com.gettyio.gim.agent.tools;

import com.gettyio.gim.agent.core.chat.ToolConfirmRequest;
import com.gettyio.gim.agent.domain.enums.PermissionDecision;
import com.gettyio.gim.agent.tools.guard.GuardChain;
import com.gettyio.gim.agent.tools.guard.GuardContext;
import com.gettyio.gim.agent.tools.guard.GuardDecision;
import com.gettyio.gim.agent.tools.guard.GuardResult;
import com.gettyio.gim.agent.tools.hitl.HitlConfirmHandler;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 工具门面安全编排：把 {@link GuardChain} 的结论与 HITL 确认串起来，
 * 由 {@code HarnessAgentGateway} 在收到框架 RequireUserConfirmEvent 后调用
 * （适配为 {@link com.gettyio.gim.agent.core.chat.ToolConfirmCallback}）。
 *
 * @author gogym
 */
public class ToolSecurityFacade {

    private final GuardChain guardChain;
    private final HitlConfirmHandler confirmHandler;

    public ToolSecurityFacade(GuardChain guardChain, HitlConfirmHandler confirmHandler) {
        this.guardChain = guardChain;
        this.confirmHandler = confirmHandler;
    }

    /**
     * 执行前置检查：ALLOW 直接放行，DENY 拒绝，ASK 走人工确认。
     *
     * @return 最终是否放行
     */
    public CompletableFuture<Boolean> precheck(String sessionId, String toolName, String toolInput) {
        return precheck(sessionId, toolName, toolInput, false);
    }

    /**
     * 执行前置检查（含自动通过档）：DENY 照旧拒绝；ASK 时若 {@code autoApprove}
     * 则不弹窗直接放行（审批档位「自动」，黑名单等硬拦截仍然生效）。
     */
    public CompletableFuture<Boolean> precheck(String sessionId, String toolName, String toolInput,
                                               boolean autoApprove) {
        GuardContext ctx = new GuardContext(sessionId, toolName, toolInput);
        GuardResult result = guardChain.evaluate(ctx);

        if (result.decision() == GuardDecision.ALLOW) {
            return CompletableFuture.completedFuture(true);
        }
        if (result.decision() == GuardDecision.DENY) {
            return CompletableFuture.completedFuture(false);
        }
        // ASK
        if (autoApprove) {
            return CompletableFuture.completedFuture(true);
        }
        if (confirmHandler == null) {
            // 无确认通道时，高危默认拒绝
            return CompletableFuture.completedFuture(false);
        }
        ToolConfirmRequest request = new ToolConfirmRequest(
                UUID.randomUUID().toString(), sessionId, toolName, toolInput, result.reason());
        return confirmHandler.requestConfirm(request).thenApply(decision -> {
            if (decision == PermissionDecision.ALWAYS_ALLOW) {
                guardChain.recordDecision(ctx, decision);
            }
            return decision == PermissionDecision.ALLOW || decision == PermissionDecision.ALWAYS_ALLOW;
        });
    }
}
