package com.gettyio.gim.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型配置（由 MVP AiModelConfig 去租户化移植）
 */
@Data
public class ModelConfig {

    private Long id;

    private String providerCode;

    /** 模型唯一编码 */
    private String modelCode;

    /** 传给 API 的模型名 */
    private String modelName;

    /** 路由优先级，越小越优先 */
    private Integer priority = 100;

    private Double costPer1kTokens;

    private Integer rateLimitRpm;

    private Integer rateLimitTpm;

    private Integer enabled = 1;

    /** 可用性状态：VALID / INVALID / UNKNOWN */
    private String status;

    private LocalDateTime lastTestAt;

    private String lastTestResult;

    /** 多模态（图片输入）支持：1=支持、0=不支持、null=未测试，由多模态测试写回 */
    private Integer supportsVision;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
