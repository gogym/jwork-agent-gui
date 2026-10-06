package com.gettyio.gim.agent.tools.guard;

import com.gettyio.gim.agent.domain.dao.ToolPermissionRuleDao;
import com.gettyio.gim.agent.domain.entity.ToolPermissionRule;
import com.gettyio.gim.agent.domain.enums.PermissionDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GuardChain} 单测：按 order 排序、首个非空结论短路、历史总是允许短路、默认询问兜底、决策记录。
 */
class GuardChainTest {

    private static final GuardContext CTX = new GuardContext("s1", "execute", "who-what");

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
        ToolPermissionRuleDao ruleDao = mock(ToolPermissionRuleDao.class);
        when(ruleDao.find(any(), any())).thenReturn(Optional.empty());
        // 数组顺序故意倒置：高 order 在前，链应排序后让低 order（deny）先命中
        GuardChain chain = new GuardChain(List.of(
                new Stub(50, GuardResult.allow("late allow")),
                new Stub(10, GuardResult.deny("early deny"))), ruleDao);
        GuardResult result = chain.evaluate(CTX);
        assertEquals(GuardDecision.DENY, result.decision());
    }

    @Test
    @DisplayName("全员沉默兜底为 ASK（副作用工具默认询问）")
    void allSilentDefaultsToAsk() {
        ToolPermissionRuleDao ruleDao = mock(ToolPermissionRuleDao.class);
        when(ruleDao.find(any(), any())).thenReturn(Optional.empty());
        GuardChain chain = new GuardChain(List.of(
                new Stub(10, null), new Stub(20, null)), ruleDao);
        assertEquals(GuardDecision.ASK, chain.evaluate(CTX).decision());
    }

    @Test
    @DisplayName("历史总是允许规则短路，优先于策略链")
    void alwaysAllowRuleShortCircuits() {
        ToolPermissionRuleDao ruleDao = mock(ToolPermissionRuleDao.class);
        ToolPermissionRule rule = new ToolPermissionRule();
        rule.setDecision(PermissionDecision.ALWAYS_ALLOW.name());
        when(ruleDao.find(eq("execute"), any())).thenReturn(Optional.of(rule));
        GuardChain chain = new GuardChain(List.of(
                new Stub(10, GuardResult.deny("should not reach"))), ruleDao);
        GuardResult result = chain.evaluate(CTX);
        assertEquals(GuardDecision.ALLOW, result.decision());
    }

    @Test
    @DisplayName("仅 ALWAYS_ALLOW 落库，其余决策不写")
    void recordsOnlyAlwaysAllow() {
        ToolPermissionRuleDao ruleDao = mock(ToolPermissionRuleDao.class);
        GuardChain chain = new GuardChain(List.of(), ruleDao);
        chain.recordDecision(CTX, PermissionDecision.ALWAYS_ALLOW);
        verify(ruleDao).upsert(eq("execute"), eq(null), eq(PermissionDecision.ALWAYS_ALLOW.name()));

        chain.recordDecision(CTX, PermissionDecision.DENY);
        verify(ruleDao, never()).upsert(eq("execute"), eq(null), eq(PermissionDecision.DENY.name()));
    }

    @Test
    @DisplayName("ruleDao 为 null 时不抛异常（无持久化场景）")
    void nullRuleDaoTolerated() {
        GuardChain chain = new GuardChain(List.of(new Stub(10, null)), null);
        assertEquals(GuardDecision.ASK, chain.evaluate(CTX).decision());
        chain.recordDecision(CTX, PermissionDecision.ALWAYS_ALLOW);
    }
}
