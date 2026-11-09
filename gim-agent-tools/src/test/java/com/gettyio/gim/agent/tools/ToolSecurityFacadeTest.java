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

package com.gettyio.gim.agent.tools;

import com.gettyio.gim.agent.core.chat.ConfirmOutcome;
import com.gettyio.gim.agent.core.chat.ToolConfirmRequest;
import com.gettyio.gim.agent.domain.dao.ToolPermissionRuleDao;
import com.gettyio.gim.agent.domain.entity.ToolPermissionRule;
import com.gettyio.gim.agent.domain.enums.PermissionDecision;
import com.gettyio.gim.agent.tools.guard.DangerousCommandPolicy;
import com.gettyio.gim.agent.tools.guard.GuardChain;
import com.gettyio.gim.agent.tools.guard.GuardContext;
import com.gettyio.gim.agent.tools.guard.GuardDecision;
import com.gettyio.gim.agent.tools.guard.GuardResult;
import com.gettyio.gim.agent.tools.guard.InterpreterFloorPolicy;
import com.gettyio.gim.agent.tools.guard.ToolGuard;
import com.gettyio.gim.agent.tools.hitl.HitlConfirmHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ToolSecurityFacade} 单测：审批档位的三条边界——普通询问可被「自动」档免打扰、
 * 下限类询问只能由用户本人的决策越过、黑名单在「自动」档下依旧硬拒绝。
 *
 * @author gogym
 */
class ToolSecurityFacadeTest {

    private static final String SHELL_TOOL = "execute";

    /** 组 shell 工具入参 JSON（命令里的引号/反斜杠按 JSON 规则转义）。 */
    private static String shell(String command) {
        String escaped = command.replace("\\", "\\\\").replace("\"", "\\\"");
        return "[{\"name\":\"execute\",\"input\":{\"command\":\"" + escaped + "\"}}]";
    }

    /** 弹窗次数计数器；决策固定返回「拒绝」，以便区分「走了确认」与「被自动放过」。 */
    private static final class AskStub implements HitlConfirmHandler {
        final AtomicInteger asked = new AtomicInteger();

        @Override
        public CompletableFuture<PermissionDecision> requestConfirm(ToolConfirmRequest request) {
            asked.incrementAndGet();
            return CompletableFuture.completedFuture(PermissionDecision.DENY);
        }
    }

    /** 无持久化规则的 DAO 桩。 */
    private static ToolPermissionRuleDao noRules() {
        ToolPermissionRuleDao dao = mock(ToolPermissionRuleDao.class);
        when(dao.findByTool(any())).thenReturn(List.of());
        return dao;
    }

    private static ToolPermissionRule alwaysAllow(String ruleContent) {
        ToolPermissionRule r = new ToolPermissionRule();
        r.setToolName(SHELL_TOOL);
        r.setRuleContent(ruleContent);
        r.setDecision(PermissionDecision.ALWAYS_ALLOW.name());
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
    @DisplayName("自动档放过普通副作用询问（不弹窗）")
    void autoTierSkipsOrdinaryAsk() {
        AskStub ask = new AskStub();
        ToolSecurityFacade facade = new ToolSecurityFacade(new GuardChain(List.of(), noRules()), ask);

        ConfirmOutcome outcome = facade.check("s1", "write_file", shell("a.txt"), true).join();
        assertTrue(outcome.allowed());
        assertEquals(0, ask.asked.get(), "自动档不应弹窗");
    }

    @Test
    @DisplayName("下限类询问（解释器）不被自动档放过，仍走确认")
    void flooredAskSurvivesAutoTier() {
        AskStub ask = new AskStub();
        GuardChain chain = new GuardChain(List.of(new Stub(12, GuardResult.askFloored("解释器命令"))), noRules());
        ToolSecurityFacade facade = new ToolSecurityFacade(chain, ask);

        ConfirmOutcome outcome = facade.check("s1", SHELL_TOOL, shell("cmd /c dir"), true).join();
        assertEquals(1, ask.asked.get(), "下限类调用必须弹窗，哪怕处于自动档");
        assertFalse(outcome.allowed(), "用户拒绝后不得放行");
    }

    @Test
    @DisplayName("非下限的询问在自动档下不打扰用户（同一链、仅标记不同）")
    void ordinaryAskStillAutoApprovedOnSameChain() {
        AskStub ask = new AskStub();
        GuardChain chain = new GuardChain(List.of(new Stub(12, GuardResult.ask("一般风险提示"))), noRules());
        ToolSecurityFacade facade = new ToolSecurityFacade(chain, ask);

        assertTrue(facade.check("s1", SHELL_TOOL, shell("mvn -q package"), true).join().allowed());
        assertEquals(0, ask.asked.get());
    }

    @Test
    @DisplayName("用户本人的「总是允许」可以越过下限档：记忆授权优先，不再打扰")
    void rememberedUserDecisionClearsFloor() {
        AskStub ask = new AskStub();
        ToolPermissionRuleDao dao = mock(ToolPermissionRuleDao.class);
        when(dao.findByTool(any())).thenReturn(List.of(alwaysAllow("exe:python")));
        GuardChain chain = new GuardChain(List.of(new InterpreterFloorPolicy()), dao);
        ToolSecurityFacade facade = new ToolSecurityFacade(chain, ask);

        assertTrue(facade.check("s1", SHELL_TOOL, shell("python a.py"), true).join().allowed());
        assertEquals(0, ask.asked.get(), "已记住同类操作，不必再问");

        // 换成没记住的程序：下限生效，自动档不得代答
        assertFalse(facade.check("s1", SHELL_TOOL, shell("git push"), true).join().allowed());
        assertEquals(1, ask.asked.get());
    }

    @Test
    @DisplayName("解释器下限策略：脚本解释器与 git 钉档，纯工具命令不表态")
    void interpreterFloorDetectsOnlyInterpreters() {
        InterpreterFloorPolicy floor = new InterpreterFloorPolicy();
        GuardContext cmd = new GuardContext("s1", SHELL_TOOL, shell("cmd /c dir"));
        GuardContext py = new GuardContext("s1", SHELL_TOOL, shell("python -c \"print(1)\""));
        GuardContext git = new GuardContext("s1", SHELL_TOOL, shell("git status"));
        GuardContext mvn = new GuardContext("s1", SHELL_TOOL, shell("mvn -q package"));
        GuardContext file = new GuardContext("s1", "write_file", shell("a.txt"));

        assertTrue(floor.inspect(cmd).floored());
        assertTrue(floor.inspect(py).floored());
        assertTrue(floor.inspect(git).floored(), "git 可经配置调用任意外部程序，同属下限");
        assertEquals(GuardDecision.ASK, floor.inspect(cmd).decision());
        assertNull(floor.inspect(mvn), "纯构建命令不钉档（不表态，由链尾默认询问）");
        assertNull(floor.inspect(file), "非 shell 工具不表态");
    }

    @Test
    @DisplayName("黑名单命中在自动档下仍是硬拒绝，且根本不走确认弹窗")
    void criticalBlacklistBeatsAutoTier() {
        AskStub ask = new AskStub();
        GuardChain chain = new GuardChain(List.of(
                new DangerousCommandPolicy(), new InterpreterFloorPolicy()), noRules());
        ToolSecurityFacade facade = new ToolSecurityFacade(chain, ask);

        ConfirmOutcome outcome = facade.check("s1", SHELL_TOOL, shell("rm -rf /data"), true).join();
        assertFalse(outcome.allowed());
        assertTrue(outcome.reason().contains("危险命令黑名单"), outcome.reason());
        assertEquals(0, ask.asked.get(), "硬拒绝不该先弹窗");
    }
}
