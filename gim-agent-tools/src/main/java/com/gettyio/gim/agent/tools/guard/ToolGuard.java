package com.gettyio.gim.agent.tools.guard;

/**
 * 安全拦截策略：责任链上的一环，针对单次工具调用给出决策。
 */
public interface ToolGuard {

    /**
     * 检查工具调用。返回 null 表示本环节不表态，交由链上下一个环节判断。
     *
     * @param ctx 调用上下文
     * @return 决策结果
     */
    GuardResult inspect(GuardContext ctx);

    /**
     * 链中优先级，越小越先执行
     */
    default int order() {
        return 100;
    }
}
