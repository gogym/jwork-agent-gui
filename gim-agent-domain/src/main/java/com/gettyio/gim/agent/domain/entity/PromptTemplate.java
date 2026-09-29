package com.gettyio.gim.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提示词模板（由 MVP AiPromptTemplate 去租户化移植）
 */
@Data
public class PromptTemplate {

    private Long id;

    private String templateCode;

    private String templateName;

    private String content;

    private String description;

    private Integer enabled = 1;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
