package com.gettyio.gim.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 技能包（由 MVP AiSkillPackage 去租户化移植，兼容 QwenPaw 技能格式）
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
