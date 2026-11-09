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
 * 解释器下限策略：shell 调用只要动用了解释器（{@code cmd}/{@code powershell}/{@code bash}/
 * {@code python}/{@code node}/{@code git} 等，见 {@link CommandPayloads#INTERPRETERS}），就钉在
 * <b>必须人工过目</b>这一档——「自动」审批档不得替用户放过它。
 *
 * <p>为什么白名单内还要拦：这些程序是日常干活必需，删不得；但它们每一个都能把任意代码塞进同一条
 * 命令里（{@code cmd /c "…"}、{@code python -c "import os; …"}）。自动档若照放，
 * 「白名单」就只剩一层名字过滤，实际边界由模型自己决定。</p>
 *
 * <p>本策略只钉档、不否决：产出 {@link GuardResult#askFloored} 而非 DENY，因此用户逐次确认、
 * 「本会话允许」或「总是允许」都照常生效——越过下限的必须是用户的明确决策，而不是一个全局开关。
 * 解释器命令一旦按程序名被记住，粒度仍然偏粗，故 {@link RuleKeys} 对解释器改用命令指纹。</p>
 *
 * @author gogym
 */
public class InterpreterFloorPolicy implements ToolGuard {

    /** shell 工具动用解释器 → 钉在人工确认档；其余一律不表态。 */
    @Override
    public GuardResult inspect(GuardContext ctx) {
        if (!ExecutableAllowlistPolicy.involvesInterpreter(ctx.toolName(), ctx.toolInput())) {
            return null;
        }
        return GuardResult.askFloored("命令将由解释器执行，实际动作不止于程序名，需用户确认");
    }

    @Override
    public int order() {
        // 危险黑名单（10）之后、程序白名单（15）之前：黑名单命中的直接拒绝不必再钉档，
        // 白名单越界的同样直接拒绝；只有两者都沉默的解释器调用才落到本策略。
        return 12;
    }
}
