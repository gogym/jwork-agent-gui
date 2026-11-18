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

package com.gettyio.jwork.agent.domain.enums;

/**
 * 自动化任务输出模式
 *
 * @author gogym
 */
public enum TaskOutputMode {

    /** 合并输出：所有执行结果汇聚到同一个会话（task-{code}） */
    MERGED,

    /** 独立输出：每次执行创建独立会话（task-{code}-yyyyMMdd-HHmmss） */
    SEPARATE;

    /** 按名称解析（忽略大小写、去首尾空白）；{@code null}/空白回退 {@link #MERGED}，未知值抛 {@link IllegalArgumentException}。 */
    public static TaskOutputMode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return MERGED;
        }
        for (TaskOutputMode mode : values()) {
            if (mode.name().equalsIgnoreCase(code.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("不支持的任务输出模式: " + code);
    }
}
