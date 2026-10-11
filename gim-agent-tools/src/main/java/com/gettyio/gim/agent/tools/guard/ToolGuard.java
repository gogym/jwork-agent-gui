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

/**
 * 安全拦截策略：责任链上的一环，针对单次工具调用给出决策。
 *
 * @author gogym
 */
public interface ToolGuard {

    /**
     * 检查工具调用。返回 null 表示本环节不表态，交由链上下一个环节判断。
     *
     * @param ctx 调用上下文
     * @return 决策结果
     */
    GuardResult inspect(GuardContext ctx);

    /**
     * 链中优先级，越小越先执行
     */
    default int order() {
        return 100;
    }
}
