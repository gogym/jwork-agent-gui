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

package com.gettyio.gim.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具权限规则（持久化用户"总是允许"决策，供 core 构建 AgentScope PermissionRule）
 *
 * @author gogym
 */
@Data
public class ToolPermissionRule {

    private Long id;

    /** 工具名，如 write_file / shell */
    private String toolName;

    /** 规则内容（可为路径前缀或命令匹配），null 表示整个工具 */
    private String ruleContent;

    /** 决策，见 PermissionDecision */
    private String decision;

    private LocalDateTime createdAt;
}
