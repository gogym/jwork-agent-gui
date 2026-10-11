package com.gettyio.gim.agent.domain.enums;

/**
 * 对话流式事件类型：AgentScope Reactor 流 → UI 渲染层的中间语义事件
 */
public enum StreamEventType {

    /** 文本增量 token */
    TEXT_DELTA,

    /** 工具调用开始 */
    TOOL_START,

    /** 工具调用结束（携带输出摘要、成败状态与耗时） */
    TOOL_END,

    /** 需要用户确认（HITL） */
    CONFIRM_REQUEST,

    /** 思考过程增量 */
    THINKING_DELTA,

    /** 本轮 token 用量汇总（携带累计的输入/输出/缓存 token） */
    USAGE,

    /** 本轮对话完成 */
    COMPLETE,

    /** 错误 */
    ERROR;
}
