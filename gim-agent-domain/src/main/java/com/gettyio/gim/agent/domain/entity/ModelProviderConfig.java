package com.gettyio.gim.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型提供商配置（由 MVP AiModelProviderConfig 去租户化移植）
 * <p>apiKey 字段在库中以密文存储，加解密由 domain 的 SecretCipher 负责。</p>
 */
@Data
public class ModelProviderConfig {

    private Long id;

    /** 提供商唯一编码，如 dashscope / ollama / anthropic */
    private String providerCode;

    private String providerName;

    /** API 协议，见 ApiProtocol */
    private String apiProtocol;

    private String baseUrl;

    /** 密文存储 */
    private String apiKey;

    /** 认证方式，见 AuthMethod */
    private String authMethod;

    private Integer enabled = 1;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
