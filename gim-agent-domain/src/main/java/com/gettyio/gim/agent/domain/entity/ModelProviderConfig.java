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
 * 模型提供商配置（由 MVP AiModelProviderConfig 去租户化移植）
 * <p>apiKey 字段在库中以密文存储，加解密由 domain 的 SecretCipher 负责。</p>
 *
 * @author gogym
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
