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
 * 工具调用安全确认决策，持久化用户"总是允许"选择
 *
 * @author gogym
 */
public enum PermissionDecision {

    /** 允许本次 */
    ALLOW,

    /** 总是允许该工具（写库，后续不再询问） */
    ALWAYS_ALLOW,

    /** 拒绝 */
    DENY;

    /** 按名称解析（忽略大小写、去首尾空白）；{@code null}/空白回退 {@link #DENY}（保守拒绝），未知值抛 {@link IllegalArgumentException}。 */
    public static PermissionDecision fromCode(String code) {
        if (code == null || code.isBlank()) {
            return DENY;
        }
        for (PermissionDecision decision : values()) {
            if (decision.name().equalsIgnoreCase(code.trim())) {
                return decision;
            }
        }
        throw new IllegalArgumentException("不支持的确认决策: " + code);
    }
}
