package com.gettyio.gim.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用级设置（键值对：默认模型、workspace 路径、安全策略开关等）
 */
@Data
public class AppSetting {

    private Long id;

    private String settingKey;

    private String settingValue;

    private String description;

    private LocalDateTime updatedAt;
}
