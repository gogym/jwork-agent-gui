package com.gettyio.gim.agent.core.chat;

import com.gettyio.gim.agent.domain.enums.PermissionDecision;
import lombok.Getter;

import java.util.concurrent.CompletableFuture;

/**
 * 工具调用高危确认请求（HITL）：由拦截链产生，UI 弹框，用户决策回填 future。
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
