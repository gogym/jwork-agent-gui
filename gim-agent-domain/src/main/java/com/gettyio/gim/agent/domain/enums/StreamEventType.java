package com.gettyio.gim.agent.domain.enums;

/**
 * 对话流式事件类型：AgentScope Reactor 流 → UI 渲染层的中间语义事件
 */
public enum StreamEventType {

    /** 文本增量 token */
    TEXT_DELTA,

    /** 工具调用开始 */
    TOOL_START,

    /** 工具调用结果 */
    TOOL_RESULT,

    /** 需要用户确认（HITL） */
    CONFIRM_REQUEST,

    /** 思考过程增量 */
    THINKING_DELTA,

    /** 本轮对话完成 */
    COMPLETE,

    /** 错误 */
    ERROR;
}
