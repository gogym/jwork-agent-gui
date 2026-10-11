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

package com.gettyio.gim.agent.domain.enums;

/**
 * 对话流式事件类型：AgentScope Reactor 流 → UI 渲染层的中间语义事件
 *
 * @author gogym
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
