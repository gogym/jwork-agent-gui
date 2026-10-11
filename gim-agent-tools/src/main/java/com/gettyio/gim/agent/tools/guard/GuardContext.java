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

package com.gettyio.gim.agent.tools.guard;

import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 工具调用检查上下文
 *
 * @author gogym
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
