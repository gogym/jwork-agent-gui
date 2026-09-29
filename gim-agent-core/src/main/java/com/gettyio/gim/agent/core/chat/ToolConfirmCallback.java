package com.gettyio.gim.agent.core.chat;

import java.util.concurrent.CompletableFuture;

/**
 * 工具执行确认回调：当 AgentScope 权限引擎对某个工具调用要求用户确认（HITL）时，
 * 网关调用本回调获取"是否放行"的异步决策。
 * <p>定义在 core，由 tools 层的 {@code ToolSecurityFacade#precheck} 适配实现，
 * 保持 core 不反向依赖 tools。</p>
 */
@FunctionalInterface
public interface ToolConfirmCallback {

    /**
     * @param sessionId 会话 ID
     * @param toolName  待确认的工具名
     * @param toolInput 工具入参的文本化描述
     * @return 异步决策：true 放行，false 拒绝
     */
    CompletableFuture<Boolean> confirm(String sessionId, String toolName, String toolInput);
}
