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

import com.gettyio.gim.agent.domain.dao.ToolPermissionRuleDao;
import com.gettyio.gim.agent.domain.entity.ToolPermissionRule;
import com.gettyio.gim.agent.domain.enums.PermissionDecision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 安全拦截责任链：按 order 依次执行策略；先查历史"总是允许"规则短路，
 * 首个非空策略结论即为最终决策。链只对框架已判为敏感的工具征求，
 * 全部沉默则维持询问（弹框确认），绝不静默放行副作用操作。
 *
 * @author gogym
 */
public class GuardChain {

    private final List<ToolGuard> guards;
    private final ToolPermissionRuleDao ruleDao; // 可为 null（无持久化时）

    public GuardChain(List<ToolGuard> guards, ToolPermissionRuleDao ruleDao) {
        List<ToolGuard> sorted = new ArrayList<>(guards);
        sorted.sort(Comparator.comparingInt(ToolGuard::order));
        this.guards = sorted;
        this.ruleDao = ruleDao;
    }

    /** 执行拦截链：先查「总是允许」规则短路；再取首个非空策略结论；全部沉默默认 ASK（绝不静默放行）。 */
    public GuardResult evaluate(GuardContext ctx) {
        // 1) 历史"总是允许"规则短路
        if (ruleDao != null) {
            Optional<ToolPermissionRule> whole = ruleDao.find(ctx.toolName(), null);
            if (whole.isPresent() && PermissionDecision.ALWAYS_ALLOW.name().equals(whole.get().getDecision())) {
                return GuardResult.allow("用户已设置总是允许该工具");
            }
        }

        // 2) 责任链首个非空结论
        for (ToolGuard guard : guards) {
            GuardResult result = guard.inspect(ctx);
            if (result != null) {
                return result;
            }
        }

        // 3) 无人异议也要弹框：进入拦截链的已是副作用工具，默认询问
        return GuardResult.ask("该操作有副作用，需用户确认");
    }

    /** 记录用户对某次 ASK 的决策，ALWAYS_ALLOW 持久化 */
    public void recordDecision(GuardContext ctx, PermissionDecision decision) {
        if (ruleDao == null || decision != PermissionDecision.ALWAYS_ALLOW) {
            return;
        }
        ruleDao.upsert(ctx.toolName(), null, decision.name());
    }
}
