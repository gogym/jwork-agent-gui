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
 * Agent 类型（沿用服务端 MVP 的枚举语义，去掉租户维度）
 *
 * @author gogym
 */
public enum AgentType {

    /** 单 Agent 对话 */
    SIMPLE,

    /** 工作流编排 */
    WORKFLOW,

    /** 多 Agent 协作 */
    MULTI;

    /** 按名称解析（忽略大小写、去首尾空白）；{@code null}/空白回退 {@link #SIMPLE}，未知值抛 {@link IllegalArgumentException}。 */
    public static AgentType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return SIMPLE;
        }
        for (AgentType type : values()) {
            if (type.name().equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("不支持的 Agent 类型: " + code);
    }
}
