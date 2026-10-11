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
 * 模型配置（由 MVP AiModelConfig 去租户化移植）
 *
 * @author gogym
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

    /** 每 1K 输入 token 单价（成本估算用，留空表示未配置） */
    private Double costPer1kInput;

    /** 每 1K 输出 token 单价 */
    private Double costPer1kOutput;

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
