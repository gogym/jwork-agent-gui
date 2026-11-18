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
 * Agent 定义（由 MVP AiAgent 去租户化移植）
 *
 * @author gogym
 */
@Data
public class AgentDefinition {

    private Long id;

    /** Agent 唯一编码 */
    private String agentCode;

    private String agentName;

    /** 类型：SIMPLE / WORKFLOW / MULTI，见 AgentType */
    private String agentType;

    private String description;

    /** 系统指令（人格），对应 workspace AGENTS.md 的数据库镜像 */
    private String systemPrompt;

    /** 绑定的模型编码 */
    private String modelCode;

    /** 绑定的提供商编码 */
    private String providerCode;

    private String modelName;

    /** 技能清单 JSON 数组 */
    private String skillsJson;

    /** MCP 服务编码列表 JSON 数组 */
    private String mcpServersJson;

    /** 工作区目录（相对 ~/.jwork/workspace） */
    private String workspaceDir;

    /** 授权项目目录（绝对路径）；智能体可在其中读写文件，非空即启用 projectWritable 文件系统 */
    private String projectPath;

    private Double temperature = 0.7;

    private Integer maxTokens = 2000;

    private Integer enabled = 1;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
