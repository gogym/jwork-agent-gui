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

package com.gettyio.jwork.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务的一次执行记录（对应 task_runs 表）。
 *
 * @author gogym
 */
@Data
public class TaskRun {

    private Long id;

    private String taskCode;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** 是否成功 */
    private boolean success;

    /** 成功摘要（应答前若干字） */
    private String summary;

    /** 失败错误信息 */
    private String error;

    /** 本次执行写入的会话 */
    private String sessionId;
}
