package com.gettyio.gim.agent.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作区分组（对标 Qoder 工作区）：绑定一个本地文件夹，作为会话的容器。
 */
@Data
public class WorkspaceGroup {

    private Long id;

    /** 业务分组 ID（UUID） */
    private String workspaceId;

    private String name;

    /** 绑定的本地文件夹绝对路径（可空） */
    private String folderPath;

    /** Segoe 图标字形码点（可空，默认文件夹图标） */
    private String icon;

    /** 主题色（#RRGGBB，可空，默认强调色） */
    private String color;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
