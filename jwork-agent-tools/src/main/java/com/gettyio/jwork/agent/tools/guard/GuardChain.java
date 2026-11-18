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

package com.gettyio.jwork.agent.tools.guard;

import com.gettyio.jwork.agent.core.chat.ToolCallsCodec;
import com.gettyio.jwork.agent.core.chat.ToolCallsCodec.ToolCallView;
import com.gettyio.jwork.agent.domain.dao.ToolPermissionRuleDao;
import com.gettyio.jwork.agent.domain.entity.ToolPermissionRule;
import com.gettyio.jwork.agent.domain.enums.PermissionDecision;

import java.time.Duration;
import java.time.LocalDateTime;
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
 * <p>一次确认里可能混发多个工具调用（{@code [read_file, execute]}）。整批只带第一个调用的工具名，
 * 而策略都用名字设门槛（谁查黑名单、谁查程序白名单、谁标路径），于是第 2 个以后的调用从前根本没人查。
 * 现在<b>逐调用评估再合并</b>：任一个被拒即整批拒绝（理由点名是哪一个），全部被记住才算被记住。</p>
 * <p>记忆按 {@link RuleKeys} 归组到命令程序 / 命令指纹 / 文件目录这一级：点一次「总是允许」只放开同类操作，
 * 不会把整个 shell 或整棵目录树交出去——长任务因此不必逐次确认，风险也不外溢。
 * 只有既非 shell 也非文件类的工具（MCP、浏览器等）才会落工具级通配，那类授权 24 小时后自动失效。</p>
 *
 * @author gogym
 */
public class GuardChain {

    /**
     * 工具级通配授权的有效期。
     * <p>通配意味着「这个工具往后都做」，而 MCP／浏览器工具的动作千差万别（发信、删库、下单都算一个工具），
     * 一次点击不该永久生效；到期后重新确认一次即可续期，代价是一次打断。</p>
     */
    static final Duration TOOL_LEVEL_TTL = Duration.ofHours(24);

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

    /**
     * 执行拦截链：批内逐调用评估后合并。
     * <p>合并口径按最坏情况取：DENY 优先于一切；否则只要有哪个调用还没被授权就整批 ASK——
     * 确认框本来就是对整批弹一次，放过一半、拦下一半反而会让模型收到自相矛盾的结果。</p>
     */
    public GuardResult evaluate(GuardContext ctx) {
        List<GuardContext> parts = split(ctx);
        if (parts.size() <= 1) {
            return evaluateOne(ctx);
        }
        List<String> askReasons = new ArrayList<>();
        boolean floored = false;
        boolean allRemembered = true;
        for (GuardContext part : parts) {
            GuardResult result = evaluateOne(part);
            if (result.decision() == GuardDecision.DENY) {
                return GuardResult.deny(label(part, result.reason()));
            }
            if (result.decision() == GuardDecision.ALLOW) {
                continue;
            }
            allRemembered = false;
            floored |= result.floored();
            askReasons.add(label(part, result.reason()));
        }
        if (allRemembered) {
            return GuardResult.allow("批内每个调用都已在授权范围内");
        }
        String reason = askReasons.isEmpty() ? "该操作有副作用，需用户确认" : String.join("；", askReasons);
        return floored ? GuardResult.askFloored(reason) : GuardResult.ask(reason);
    }

    /** 单个调用的裁决：DENY 最优先；再认「总是允许」记忆；全部沉默默认 ASK（绝不静默放行）。 */
    private GuardResult evaluateOne(GuardContext ctx) {
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

    /**
     * 把整批入参拆成「一调用一上下文」。
     * <p>解不出结构化调用（老格式文本、编解码退化）时原样返回单元素：维持整体评估，
     * 各策略此时都会因为读不出内容而沉默，最终落到默认询问——失败方向是拦，不是放。</p>
     */
    private static List<GuardContext> split(GuardContext ctx) {
        List<ToolCallView> calls = ToolCallsCodec.decode(ctx.toolInput());
        if (calls.size() <= 1) {
            return List.of(ctx);
        }
        List<GuardContext> parts = new ArrayList<>(calls.size());
        for (ToolCallView call : calls) {
            String name = call.name() == null || "null".equals(call.name()) || call.name().isBlank()
                    ? ctx.toolName() : call.name();
            parts.add(new GuardContext(ctx.sessionId(), name, ToolCallsCodec.encodeSingle(call)));
        }
        return parts;
    }

    /** 批内理由前缀调用名，用户才知道一次弹框里究竟哪一步有风险。 */
    private static String label(GuardContext part, String reason) {
        String text = reason == null ? "" : reason;
        return part.toolName() == null ? text : part.toolName() + "：" + text;
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
        LocalDateTime now = LocalDateTime.now();
        Set<String> persisted = new HashSet<>();
        for (ToolPermissionRule rule : ruleDao.findByTool(ctx.toolName())) {
            if (rule == null || !PermissionDecision.ALWAYS_ALLOW.name().equals(rule.getDecision())) {
                continue;
            }
            if (rule.getRuleContent() == null || rule.getRuleContent().isBlank()) {
                // 工具级通配：只在有效期内认账
                if (!expired(rule, now)) {
                    return "用户已设置总是允许该工具（有效期内）";
                }
                continue;
            }
            persisted.add(rule.getRuleContent());
        }
        return RuleKeys.covered(keys, persisted) ? "用户已设置总是允许该类操作" : null;
    }

    /**
     * 通配授权是否已失效。
     * <p>没写到期时间的行一律视为过期：那是本版本之前留下的历史通配，当时一次点击就把整个工具
     * （包括 {@code execute}）永久交了出去。重新确认一次会写入带到期时间的行，此后正常续期。</p>
     */
    private static boolean expired(ToolPermissionRule rule, LocalDateTime now) {
        LocalDateTime expiresAt = rule.getExpiresAt();
        return expiresAt == null || !expiresAt.isAfter(now);
    }

    /**
     * 记录用户对某次 ASK 的决策：{@code SESSION_ALLOW} 只进会话内存，{@code ALWAYS_ALLOW}
     * 额外按 {@link RuleKeys} 归组的级别落库（能归组就不放开整个工具；归不出组的通配行带有效期）。
     */
    public void recordDecision(GuardContext ctx, PermissionDecision decision) {
        boolean remember = decision == PermissionDecision.ALWAYS_ALLOW
                || decision == PermissionDecision.SESSION_ALLOW;
        if (!remember) {
            return;
        }
        // 逐调用记录：每个调用都在自己的工具名下留键，混发批次不会把 A 的风险记到 B 头上
        for (GuardContext part : split(ctx)) {
            recordPart(part, decision);
        }
    }

    private void recordPart(GuardContext part, PermissionDecision decision) {
        List<String> keys = RuleKeys.forCall(part.toolName(), part.toolInput());
        Set<String> remembered = keys.isEmpty() ? Set.of(RuleKeys.TOOL_LEVEL) : Set.copyOf(keys);
        if (part.sessionId() != null) {
            sessionAllowed.computeIfAbsent(part.sessionId(), k -> ConcurrentHashMap.newKeySet())
                    .addAll(remembered);
        }
        if (decision != PermissionDecision.ALWAYS_ALLOW || ruleDao == null) {
            return;
        }
        for (String key : remembered) {
            boolean toolLevel = RuleKeys.TOOL_LEVEL.equals(key);
            ruleDao.upsert(part.toolName(), toolLevel ? null : key, decision.name(),
                    toolLevel ? LocalDateTime.now().plus(TOOL_LEVEL_TTL) : null);
        }
    }

    /** 会话结束：丢弃其内存态授权（持久规则由设置页「授权管理」撤销）。 */
    public void clearSession(String sessionId) {
        if (sessionId != null) {
            sessionAllowed.remove(sessionId);
        }
    }
}
