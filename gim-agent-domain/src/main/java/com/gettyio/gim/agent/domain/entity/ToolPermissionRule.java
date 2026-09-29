package com.gettyio.gim.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具权限规则（持久化用户"总是允许"决策，供 core 构建 AgentScope PermissionRule）
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
