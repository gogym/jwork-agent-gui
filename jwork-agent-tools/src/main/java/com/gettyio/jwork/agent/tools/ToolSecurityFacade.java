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

package com.gettyio.jwork.agent.tools;

import com.gettyio.jwork.agent.core.chat.ConfirmOutcome;
import com.gettyio.jwork.agent.core.chat.ToolConfirmRequest;
import com.gettyio.jwork.agent.domain.enums.PermissionDecision;
import com.gettyio.jwork.agent.tools.guard.GuardChain;
import com.gettyio.jwork.agent.tools.guard.GuardContext;
import com.gettyio.jwork.agent.tools.guard.GuardDecision;
import com.gettyio.jwork.agent.tools.guard.GuardResult;
import com.gettyio.jwork.agent.tools.hitl.HitlConfirmHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 工具门面安全编排：把 {@link GuardChain} 的结论与 HITL 确认串起来，
 * 由 {@code HarnessAgentGateway} 在收到框架 RequireUserConfirmEvent 后调用
 * （适配为 {@link com.gettyio.jwork.agent.core.chat.ToolConfirmCallback}）。
 *
 * @author gogym
 */
public class ToolSecurityFacade {

    private static final Logger log = LoggerFactory.getLogger(ToolSecurityFacade.class);

    private final GuardChain guardChain;
    private final HitlConfirmHandler confirmHandler;

    public ToolSecurityFacade(GuardChain guardChain, HitlConfirmHandler confirmHandler) {
        this.guardChain = guardChain;
        this.confirmHandler = confirmHandler;
    }

    /**
     * 执行前置检查：ALLOW 直接放行，DENY 带理由拒绝，ASK 走人工确认。
     * <p>拒绝理由不能丢——它是模型唯一能看到的「为什么不行」。</p>
     */
    public CompletableFuture<ConfirmOutcome> check(String sessionId, String toolName, String toolInput) {
        return check(sessionId, toolName, toolInput, false);
    }

    /**
     * 执行前置检查（含自动通过档）：DENY 照旧带理由拒绝；ASK 时若 {@code autoApprove}
     * 则不弹窗直接放行（审批档位「自动」，黑名单等硬拦截仍然生效）。
     *
     * <p>自动档有一道下限：结论带 {@code floored} 的调用（解释器命令、CRITICAL 级风险）不因档位
     * 而免确认——用户点了「自动」是要少打断，不是要把任意代码的执行权交出去。这类调用仍可被
     * 用户本人的决策越过（确认弹窗里选「本会话允许／总是允许」即写入记忆），只是开关不行。</p>
     */
    public CompletableFuture<ConfirmOutcome> check(String sessionId, String toolName, String toolInput,
                                                  boolean autoApprove) {
        GuardContext ctx = new GuardContext(sessionId, toolName, toolInput);
        GuardResult result = guardChain.evaluate(ctx);

        if (result.decision() == GuardDecision.ALLOW) {
            return CompletableFuture.completedFuture(ConfirmOutcome.allow());
        }
        if (result.decision() == GuardDecision.DENY) {
            return CompletableFuture.completedFuture(ConfirmOutcome.deny(result.reason()));
        }
        // ASK
        if (autoApprove && !result.floored()) {
            return CompletableFuture.completedFuture(ConfirmOutcome.allow());
        }
        if (autoApprove) {
            log.info("自动通过档下限生效：高危类调用仍需确认（{}）", result.reason());
        }
        if (confirmHandler == null) {
            // 无确认通道时，高危默认拒绝
            return CompletableFuture.completedFuture(
                    ConfirmOutcome.deny("界面未提供确认通道，高风险操作默认拒绝"));
        }
        ToolConfirmRequest request = new ToolConfirmRequest(
                UUID.randomUUID().toString(), sessionId, toolName, toolInput, result.reason());
        return confirmHandler.requestConfirm(request).thenApply(decision -> {
            if (decision == PermissionDecision.ALWAYS_ALLOW || decision == PermissionDecision.SESSION_ALLOW) {
                // 「本会话允许」只进内存，「总是允许」额外落库——两者都当次放行
                guardChain.recordDecision(ctx, decision);
                return ConfirmOutcome.allow();
            }
            return decision == PermissionDecision.ALLOW
                    ? ConfirmOutcome.allow()
                    : ConfirmOutcome.deny(result.reason() == null ? null
                            : "用户拒绝了该操作（风险提示：" + result.reason() + "）");
        });
    }

    /** 会话销毁：丢弃其「本会话允许」记忆（持久规则由设置页撤销）。 */
    public void clearSession(String sessionId) {
        guardChain.clearSession(sessionId);
    }
}
