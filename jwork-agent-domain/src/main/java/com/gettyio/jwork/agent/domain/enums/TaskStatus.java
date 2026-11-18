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
 * 定时任务状态
 *
 * @author gogym
 */
public enum TaskStatus {

    /** 就绪，等待触发 */
    READY,

    /** 暂停 */
    PAUSED,

    /** 已完成（一次性任务） */
    FINISHED,

    /** 执行失败 */
    FAILED;

    /** 按名称解析（忽略大小写、去首尾空白）；{@code null}/空白回退 {@link #READY}，未知值抛 {@link IllegalArgumentException}。 */
    public static TaskStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return READY;
        }
        for (TaskStatus status : values()) {
            if (status.name().equalsIgnoreCase(code.trim())) {
                return status;
            }
        }
        throw new IllegalArgumentException("不支持的任务状态: " + code);
    }
}
