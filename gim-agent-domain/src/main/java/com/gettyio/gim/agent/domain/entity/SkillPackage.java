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
 * 技能包（由 MVP AiSkillPackage 去租户化移植，兼容 QwenPaw 技能格式）
 *
 * @author gogym
 */
@Data
public class SkillPackage {

    private Long id;

    /** 技能唯一编码（与 workspace/skills 目录名一致） */
    private String skillCode;

    private String skillName;

    private String description;

    /** 版本 */
    private String version;

    /** SKILL.md 相对路径 */
    private String entryPath;

    /** 来源：BUILTIN / INSTALLED / MARKETPLACE */
    private String source;

    /** 依赖的工具/权限声明 JSON 数组 */
    private String requiresJson;

    private Integer enabled = 1;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
