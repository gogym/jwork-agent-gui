package com.gettyio.gim.agent.tools.hitl;

import com.gettyio.gim.agent.core.chat.ToolConfirmRequest;
import com.gettyio.gim.agent.domain.enums.PermissionDecision;

import java.util.concurrent.CompletableFuture;

/**
 * 人工确认处理器：由 UI 层实现，弹出确认框并异步回填用户决策。
 */
public interface HitlConfirmHandler {

    /**
     * 发起确认，返回异步决策（允许/拒绝/总是允许）
     */
    CompletableFuture<PermissionDecision> requestConfirm(ToolConfirmRequest request);
}
