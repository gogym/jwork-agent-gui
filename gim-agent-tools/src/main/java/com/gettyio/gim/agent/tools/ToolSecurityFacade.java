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
 * 供 AgentScope 工具执行前置检查调用（工具注册细节在 M3 接入）。
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
        GuardContext ctx = new GuardContext(sessionId, toolName, toolInput);
        GuardResult result = guardChain.evaluate(ctx);

        if (result.decision() == GuardDecision.ALLOW) {
            return CompletableFuture.completedFuture(true);
        }
        if (result.decision() == GuardDecision.DENY) {
            return CompletableFuture.completedFuture(false);
        }
        // ASK
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
