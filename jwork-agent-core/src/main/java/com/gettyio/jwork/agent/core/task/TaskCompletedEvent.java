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

package com.gettyio.jwork.agent.core.task;

import lombok.Getter;
import lombok.ToString;

/**
 * 定时任务执行完成事件：由 scheduler 投递，UI 侧以系统通知呈现。
 *
 * @author gogym
 */
@Getter
@ToString
public class TaskCompletedEvent {

    private final String taskCode;
    private final String taskName;
    private final String sessionId;
    /** 执行结果摘要（助手应答首段或错误信息） */
    private final String summary;
    private final boolean success;

    public TaskCompletedEvent(String taskCode, String taskName, String sessionId, String summary, boolean success) {
        this.taskCode = taskCode;
        this.taskName = taskName;
        this.sessionId = sessionId;
        this.summary = summary;
        this.success = success;
    }
}
