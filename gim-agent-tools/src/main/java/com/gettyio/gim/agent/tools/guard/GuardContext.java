package com.gettyio.gim.agent.tools.guard;

import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 工具调用检查上下文
 */
@Getter
@Accessors(fluent = true)
@ToString
public class GuardContext {

    private final String sessionId;
    private final String toolName;
    /** 工具入参的文本化描述（如文件路径、命令行） */
    private final String toolInput;

    public GuardContext(String sessionId, String toolName, String toolInput) {
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.toolInput = toolInput;
    }
}
