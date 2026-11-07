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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GuardChain} 单测：硬拦截优先于任何记忆授权、按命令/目录归组的「总是允许」、
 * 会话记忆的写入与失效、默认询问兜底。
 *
 * @author gogym
 */
class GuardChainTest {

    /** shell 调用入参（与 ToolCallsCodec 的编码格式一致）。 */
    private static String shell(String command) {
        return "[{\"name\":\"execute\",\"input\":{\"command\":\"" + command + "\"}}]";
    }

    private static GuardContext ctx(String toolInput) {
        return new GuardContext("s1", "execute", toolInput);
    }

    /** 无持久化规则的 DAO 桩。 */
    private static ToolPermissionRuleDao noRules() {
        ToolPermissionRuleDao ruleDao = mock(ToolPermissionRuleDao.class);
        when(ruleDao.findByTool(any())).thenReturn(List.of());
        return ruleDao;
    }

    /** 带决策的规则行。 */
    private static ToolPermissionRule rule(String ruleContent, PermissionDecision decision) {
        ToolPermissionRule r = new ToolPermissionRule();
        r.setToolName("execute");
        r.setRuleContent(ruleContent);
        r.setDecision(decision.name());
        return r;
    }

    /** 固定产出的策略桩。 */
    private record Stub(int order, GuardResult outcome) implements ToolGuard {
        @Override
        public GuardResult inspect(GuardContext ctx) {
            return outcome;
        }

        @Override
        public int order() {
            return order;
        }
    }

    @Test
    @DisplayName("无历史规则时按 order 取首个非空结论（低优先级先判）")
    void firstNonNullByOrderWins() {
        // 数组顺序故意倒置：高 order 在前，链应排序后让低 order（deny）先命中
        GuardChain chain = new GuardChain(List.of(
                new Stub(50, GuardResult.allow("late allow")),
                new Stub(10, GuardResult.deny("early deny"))), noRules());
        GuardResult result = chain.evaluate(ctx(shell("python a.py")));
        assertEquals(GuardDecision.DENY, result.decision());
    }

    @Test
    @DisplayName("全员沉默兜底为 ASK（副作用工具默认询问）")
    void allSilentDefaultsToAsk() {
        GuardChain chain = new GuardChain(List.of(new Stub(10, null), new Stub(20, null)), noRules());
        assertEquals(GuardDecision.ASK, chain.evaluate(ctx(shell("python a.py"))).decision());
    }

    @Test
    @DisplayName("硬拦截先于任何记忆授权：早前点「总是允许」整个工具也不该放过危险命令")
    void hardDenyBeatsRememberedAllow() {
        ToolPermissionRuleDao ruleDao = mock(ToolPermissionRuleDao.class);
        when(ruleDao.findByTool(any())).thenReturn(List.of(rule(null, PermissionDecision.ALWAYS_ALLOW)));
        GuardChain chain = new GuardChain(List.of(
                new Stub(10, GuardResult.deny("rm -rf 属黑名单"))), ruleDao);
        GuardResult result = chain.evaluate(ctx(shell("rm -rf /")));
        assertEquals(GuardDecision.DENY, result.decision());
        assertNotNull(result.reason());
    }

    @Test
    @DisplayName("工具级持久规则（rule_content 为空）放开该工具")
    void toolLevelRuleAllowsWholeTool() {
        ToolPermissionRuleDao ruleDao = mock(ToolPermissionRuleDao.class);
        when(ruleDao.findByTool(any())).thenReturn(List.of(rule(null, PermissionDecision.ALWAYS_ALLOW)));
        GuardChain chain = new GuardChain(List.of(), ruleDao);
        assertEquals(GuardDecision.ALLOW, chain.evaluate(ctx(shell("python a.py"))).decision());
    }

    @Test
    @DisplayName("命令级持久规则只放开同类程序，换程序照旧询问")
    void exeLevelRuleScopesToSameExecutable() {
        ToolPermissionRuleDao ruleDao = mock(ToolPermissionRuleDao.class);
        when(ruleDao.findByTool(any())).thenReturn(List.of(rule("exe:python", PermissionDecision.ALWAYS_ALLOW)));
        GuardChain chain = new GuardChain(List.of(), ruleDao);

        assertEquals(GuardDecision.ALLOW, chain.evaluate(ctx(shell("python a.py"))).decision());
        assertEquals(GuardDecision.ASK, chain.evaluate(ctx(shell("git status"))).decision());
        // 多程序命令要全部命中才算授权：只记住 python 不足以放过管道另一端
        assertEquals(GuardDecision.ASK, chain.evaluate(ctx(shell("python a.py | rm out.txt"))).decision());
    }

    @Test
    @DisplayName("总是允许按程序名落库，不落整个工具")
    void alwaysAllowPersistsPerExecutableKey() {
        ToolPermissionRuleDao ruleDao = noRules();
        GuardChain chain = new GuardChain(List.of(), ruleDao);
        GuardContext c = ctx(shell("python a.py"));

        chain.recordDecision(c, PermissionDecision.ALWAYS_ALLOW);
        verify(ruleDao).upsert(eq("execute"), eq("exe:python"), eq(PermissionDecision.ALWAYS_ALLOW.name()));
        assertEquals(GuardDecision.ALLOW, chain.evaluate(c).decision());
    }

    @Test
    @DisplayName("本会话允许只进内存：同会话放过、clearSession 后重新询问、不写库")
    void sessionAllowIsMemoryOnly() {
        ToolPermissionRuleDao ruleDao = noRules();
        GuardChain chain = new GuardChain(List.of(), ruleDao);
        GuardContext c = ctx(shell("python a.py"));

        chain.recordDecision(c, PermissionDecision.SESSION_ALLOW);
        verify(ruleDao, never()).upsert(any(), any(), any());
        assertEquals(GuardDecision.ALLOW, chain.evaluate(c).decision());

        chain.clearSession("s1");
        assertEquals(GuardDecision.ASK, chain.evaluate(c).decision());
    }

    @Test
    @DisplayName("会话记忆按会话隔离：别的会话没被授权")
    void sessionAllowDoesNotLeakAcrossSessions() {
        GuardChain chain = new GuardChain(List.of(), noRules());
        chain.recordDecision(new GuardContext("s1", "execute", shell("python a.py")),
                PermissionDecision.SESSION_ALLOW);
        GuardResult other = chain.evaluate(new GuardContext("s2", "execute", shell("python a.py")));
        assertEquals(GuardDecision.ASK, other.decision());
    }

    @Test
    @DisplayName("解析不出命令的调用退回工具级归组")
    void unparsableCallFallsBackToToolLevel() {
        ToolPermissionRuleDao ruleDao = noRules();
        GuardChain chain = new GuardChain(List.of(), ruleDao);
        GuardContext c = ctx("not json");

        chain.recordDecision(c, PermissionDecision.ALWAYS_ALLOW);
        verify(ruleDao).upsert(eq("execute"), eq(null), eq(PermissionDecision.ALWAYS_ALLOW.name()));
        assertEquals(GuardDecision.ALLOW, chain.evaluate(c).decision());
    }

    @Test
    @DisplayName("文件类工具按目录归组：同目录放过，换目录询问")
    void fileToolRemembersParentDir() {
        GuardChain chain = new GuardChain(List.of(), noRules());
        GuardContext inDir = new GuardContext("s1", "write_file",
                "[{\"name\":\"write_file\",\"input\":{\"path\":\"data/out/r.xlsx\"}}]");
        chain.recordDecision(inDir, PermissionDecision.SESSION_ALLOW);

        assertEquals(GuardDecision.ALLOW, chain.evaluate(inDir).decision());
        GuardContext elsewhere = new GuardContext("s1", "write_file",
                "[{\"name\":\"write_file\",\"input\":{\"path\":\"data/secret/k.xlsx\"}}]");
        assertEquals(GuardDecision.ASK, chain.evaluate(elsewhere).decision());
    }

    @Test
    @DisplayName("拒绝决策不写入任何记忆")
    void denyRecordsNothing() {
        ToolPermissionRuleDao ruleDao = noRules();
        GuardChain chain = new GuardChain(List.of(), ruleDao);
        GuardContext c = ctx(shell("python a.py"));
        chain.recordDecision(c, PermissionDecision.DENY);
        chain.recordDecision(c, PermissionDecision.ALLOW);
        verify(ruleDao, never()).upsert(any(), any(), any());
        assertEquals(GuardDecision.ASK, chain.evaluate(c).decision());
    }

    @Test
    @DisplayName("ruleDao 为 null 时不抛异常（无持久化场景），会话记忆仍生效")
    void nullRuleDaoTolerated() {
        GuardChain chain = new GuardChain(List.of(new Stub(10, null)), null);
        GuardContext c = ctx(shell("python a.py"));
        assertEquals(GuardDecision.ASK, chain.evaluate(c).decision());
        chain.recordDecision(c, PermissionDecision.ALWAYS_ALLOW);
        assertEquals(GuardDecision.ALLOW, chain.evaluate(c).decision());
    }
}
