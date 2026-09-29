package com.gettyio.gim.agent.tools.guard;

/**
 * 单次工具调用的安全检查结论
 */
public enum GuardDecision {

    /** 放行 */
    ALLOW,

    /** 拒绝 */
    DENY,

    /** 需人工确认 */
    ASK;
}
