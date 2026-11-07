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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 安全拦截责任链：按 order 依次执行策略；<b>硬拒绝（黑名单、白名单外程序）永远优先</b>，
 * 无拒绝时才认「总是允许」记忆（会话内 + 持久化），再取首个非空策略结论；
 * 全部沉默则维持询问（弹框确认），绝不静默放行副作用操作。
 * <p>记忆按 {@link RuleKeys} 归组到命令程序 / 文件目录这一级：点一次「总是允许」只放开同类操作，
 * 不会把整个 shell 或整棵目录树交出去——长任务因此不必逐次确认，风险也不外溢。</p>
 *
 * @author gogym
 */
public class GuardChain {

    private final List<ToolGuard> guards;
    private final ToolPermissionRuleDao ruleDao; // 可为 null（无持久化时）

    /** 会话级记忆（不落库）：sessionId → 已允许的规则键；会话关闭即清除。 */
    private final Map<String, Set<String>> sessionAllowed = new ConcurrentHashMap<>();

    public GuardChain(List<ToolGuard> guards, ToolPermissionRuleDao ruleDao) {
        List<ToolGuard> sorted = new ArrayList<>(guards);
        sorted.sort(Comparator.comparingInt(ToolGuard::order));
        this.guards = sorted;
        this.ruleDao = ruleDao;
    }

    /** 执行拦截链：DENY 最优先；再认「总是允许」记忆；全部沉默默认 ASK（绝不静默放行）。 */
    public GuardResult evaluate(GuardContext ctx) {
        GuardResult first = null;
        for (ToolGuard guard : guards) {
            GuardResult result = guard.inspect(ctx);
            if (result == null) {
                continue;
            }
            // 硬拦截先于任何记忆授权：用户早前点的「总是允许」不该把 rm -rf 也放过去
            if (result.decision() == GuardDecision.DENY) {
                return result;
            }
            if (first == null) {
                first = result;
            }
        }
        if (first != null && first.decision() == GuardDecision.ALLOW) {
            return first;
        }

        String remembered = rememberedReason(ctx);
        if (remembered != null) {
            return GuardResult.allow(remembered);
        }
        // 无人异议也要弹框：进入拦截链的已是副作用工具，默认询问
        return first != null ? first : GuardResult.ask("该操作有副作用，需用户确认");
    }

    /** 本次调用是否已被「总是允许」覆盖；命中返回理由，未命中返回 null。 */
    private String rememberedReason(GuardContext ctx) {
        List<String> keys = RuleKeys.forCall(ctx.toolName(), ctx.toolInput());
        if (ctx.sessionId() != null
                && RuleKeys.covered(keys, sessionAllowed.getOrDefault(ctx.sessionId(), Set.of()))) {
            return "本次会话内用户已允许同类操作";
        }
        if (ruleDao == null) {
            return null;
        }
        Set<String> persisted = new HashSet<>();
        for (ToolPermissionRule rule : ruleDao.findByTool(ctx.toolName())) {
            if (rule == null || !PermissionDecision.ALWAYS_ALLOW.name().equals(rule.getDecision())) {
                continue;
            }
            if (rule.getRuleContent() == null || rule.getRuleContent().isBlank()) {
                return "用户已设置总是允许该工具";
            }
            persisted.add(rule.getRuleContent());
        }
        return RuleKeys.covered(keys, persisted) ? "用户已设置总是允许该类操作" : null;
    }

    /**
     * 记录用户对某次 ASK 的决策：{@code SESSION_ALLOW} 只进会话内存，{@code ALWAYS_ALLOW}
     * 额外按 {@link RuleKeys} 归组的级别落库（能归组就不放开整个工具）。
     */
    public void recordDecision(GuardContext ctx, PermissionDecision decision) {
        boolean remember = decision == PermissionDecision.ALWAYS_ALLOW
                || decision == PermissionDecision.SESSION_ALLOW;
        if (!remember) {
            return;
        }
        List<String> keys = RuleKeys.forCall(ctx.toolName(), ctx.toolInput());
        Set<String> remembered = keys.isEmpty() ? Set.of(RuleKeys.TOOL_LEVEL) : Set.copyOf(keys);
        if (ctx.sessionId() != null) {
            sessionAllowed.computeIfAbsent(ctx.sessionId(), k -> ConcurrentHashMap.newKeySet())
                    .addAll(remembered);
        }
        if (decision != PermissionDecision.ALWAYS_ALLOW || ruleDao == null) {
            return;
        }
        for (String key : remembered) {
            ruleDao.upsert(ctx.toolName(), RuleKeys.TOOL_LEVEL.equals(key) ? null : key, decision.name());
        }
    }

    /** 会话结束：丢弃其内存态授权（持久规则由设置页「授权管理」撤销）。 */
    public void clearSession(String sessionId) {
        if (sessionId != null) {
            sessionAllowed.remove(sessionId);
        }
    }
}
