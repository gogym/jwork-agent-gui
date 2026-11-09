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
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GuardChain} 单测：硬拦截优先于任何记忆授权、按命令/指纹/目录归组的「总是允许」、
 * 会话记忆的写入与失效、默认询问兜底、批内逐调用裁决、工具级通配的有效期。
 *
 * @author gogym
 */
class GuardChainTest {

    /** shell 调用入参：单个调用也要包成数组——ToolCallsCodec 只认 JSON 数组（与 encode 产物同形）。 */
    private static String shell(String command) {
        return batch(call("execute", "command", command));
    }

    /** 混发批次：一次确认里同时出现只读工具与 shell。 */
    private static String batch(String... callJson) {
        return "[" + String.join(",", callJson) + "]";
    }

    /** 单个调用的 JSON 文本（未包数组，交由 {@link #batch} 或 {@link #shell} 包装）。 */
    private static String call(String name, String key, String value) {
        return "{\"name\":\"" + name + "\",\"input\":{\"" + key + "\":\"" + json(value) + "\"}}";
    }

    /** 手写 JSON 的转义（先转反斜杠再转引号）：命令里的引号不转义会让整段解析失败，测的就不是同一条路径了。 */
    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
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
        return rule(ruleContent, decision, null);
    }

    /** 带到期时间的规则行（工具级通配用 {@code ruleContent = null}）。 */
    private static ToolPermissionRule rule(String ruleContent, PermissionDecision decision, LocalDateTime expiresAt) {
        ToolPermissionRule r = new ToolPermissionRule();
        r.setToolName("execute");
        r.setRuleContent(ruleContent);
        r.setDecision(decision.name());
        r.setExpiresAt(expiresAt);
        return r;
    }

    /** 仍在有效期内的工具级通配行。 */
    private static ToolPermissionRule liveWildcard() {
        return rule(null, PermissionDecision.ALWAYS_ALLOW, LocalDateTime.now().plusHours(1));
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

    /** 只按工具名设门槛的策略桩：模拟真实策略「非 shell 不表态」的行为。 */
    private record NameGatedStub(String expectedTool, GuardResult outcome) implements ToolGuard {
        @Override
        public GuardResult inspect(GuardContext ctx) {
            return expectedTool.equalsIgnoreCase(ctx.toolName()) ? outcome : null;
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
    @DisplayName("有效期内的工具级持久规则（rule_content 为空）放开该工具")
    void toolLevelRuleAllowsWholeTool() {
        ToolPermissionRuleDao ruleDao = mock(ToolPermissionRuleDao.class);
        when(ruleDao.findByTool(any())).thenReturn(List.of(liveWildcard()));
        GuardChain chain = new GuardChain(List.of(), ruleDao);
        assertEquals(GuardDecision.ALLOW, chain.evaluate(ctx(shell("python a.py"))).decision());
    }

    @Test
    @DisplayName("工具级通配过期后不再放过：重新询问，理由点名是历史授权")
    void expiredToolLevelWildcardStopsWorking() {
        ToolPermissionRuleDao ruleDao = mock(ToolPermissionRuleDao.class);
        when(ruleDao.findByTool(any())).thenReturn(List.of(
                rule(null, PermissionDecision.ALWAYS_ALLOW, LocalDateTime.now().minusSeconds(1))));
        GuardChain chain = new GuardChain(List.of(), ruleDao);
        assertEquals(GuardDecision.ASK, chain.evaluate(ctx(shell("python a.py"))).decision());
    }

    @Test
    @DisplayName("老库里没有到期时间的通配行按已过期处理：那批授权曾一次交出整个 execute")
    void legacyWildcardWithoutExpiryIsNotHonored() {
        ToolPermissionRuleDao ruleDao = mock(ToolPermissionRuleDao.class);
        when(ruleDao.findByTool(any())).thenReturn(List.of(rule(null, PermissionDecision.ALWAYS_ALLOW)));
        GuardChain chain = new GuardChain(List.of(), ruleDao);
        assertEquals(GuardDecision.ASK, chain.evaluate(ctx(shell("python a.py"))).decision());
        // 窄规则不受影响：它们本来就不靠到期时间兜粒度
        when(ruleDao.findByTool(any())).thenReturn(List.of(rule("exe:python", PermissionDecision.ALWAYS_ALLOW)));
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
    @DisplayName("总是允许按程序名落库，不落整个工具（窄规则不带到期时间）")
    void alwaysAllowPersistsPerExecutableKey() {
        ToolPermissionRuleDao ruleDao = noRules();
        GuardChain chain = new GuardChain(List.of(), ruleDao);
        GuardContext c = ctx(shell("python a.py"));

        chain.recordDecision(c, PermissionDecision.ALWAYS_ALLOW);
        verify(ruleDao).upsert(eq("execute"), eq("exe:python"),
                eq(PermissionDecision.ALWAYS_ALLOW.name()), isNull());
        assertEquals(GuardDecision.ALLOW, chain.evaluate(c).decision());
    }

    @Test
    @DisplayName("夹带解释器载荷的命令按整条命令指纹记忆：换一段代码就重新询问")
    void interpreterPayloadIsRememberedByFingerprint() {
        ToolPermissionRuleDao ruleDao = noRules();
        GuardChain chain = new GuardChain(List.of(), ruleDao);
        String command = "python -c \"print(1)\"";
        GuardContext c = ctx(shell(command));

        chain.recordDecision(c, PermissionDecision.ALWAYS_ALLOW);
        verify(ruleDao).upsert(eq("execute"), eq("fp:" + RuleKeys.fingerprint(command)),
                eq(PermissionDecision.ALWAYS_ALLOW.name()), isNull());

        // 同程序的另一段代码：exe:python 从未落库，因此照旧询问
        assertEquals(GuardDecision.ASK, chain.evaluate(ctx(shell("python -c \"os.system('rm -rf /')\""))).decision());
        assertEquals(GuardDecision.ALLOW, chain.evaluate(c).decision(), "原样重放同一条命令才放过");
    }

    @Test
    @DisplayName("本会话允许只进内存：同会话放过、clearSession 后重新询问、不写库")
    void sessionAllowIsMemoryOnly() {
        ToolPermissionRuleDao ruleDao = noRules();
        GuardChain chain = new GuardChain(List.of(), ruleDao);
        GuardContext c = ctx(shell("python a.py"));

        chain.recordDecision(c, PermissionDecision.SESSION_ALLOW);
        verify(ruleDao, never()).upsert(any(), any(), any(), any());
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
    @DisplayName("shell 调用解析不出命令时按指纹记忆，绝不落整个工具级通配")
    void unparsableShellCallNeverFallsBackToToolLevel() {
        ToolPermissionRuleDao ruleDao = noRules();
        GuardChain chain = new GuardChain(List.of(), ruleDao);
        GuardContext c = ctx("not json");

        chain.recordDecision(c, PermissionDecision.ALWAYS_ALLOW);
        verify(ruleDao).upsert(eq("execute"), eq("fp:" + RuleKeys.fingerprint("not json")),
                eq(PermissionDecision.ALWAYS_ALLOW.name()), isNull());
        assertEquals(GuardDecision.ALLOW, chain.evaluate(c).decision());
        // 别的调用文本不同、指纹不同：不会被这条记忆放过
        assertEquals(GuardDecision.ASK, chain.evaluate(ctx("also not json")).decision());
    }

    @Test
    @DisplayName("既非 shell 也非文件类的工具才允许工具级通配，且带有效期")
    void nonSideEffectToolUsesExpiringToolLevelWildcard() {
        ToolPermissionRuleDao ruleDao = noRules();
        GuardChain chain = new GuardChain(List.of(), ruleDao);
        GuardContext c = new GuardContext("s1", "browser_navigate",
                batch(call("browser_navigate", "url", "example.com")));

        chain.recordDecision(c, PermissionDecision.ALWAYS_ALLOW);
        ArgumentCaptor<LocalDateTime> time = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(ruleDao).upsert(eq("browser_navigate"), isNull(),
                eq(PermissionDecision.ALWAYS_ALLOW.name()), time.capture());
        LocalDateTime written = time.getValue();
        assertNotNull(written, "工具级通配必须带到期时间");
        LocalDateTime now = LocalDateTime.now();
        assertTrue(written.isAfter(now) && written.isBefore(now.plus(GuardChain.TOOL_LEVEL_TTL).plusMinutes(1)),
                "到期时间应落在有效期档内，实际 " + written);
    }

    @Test
    @DisplayName("批内第 2 个调用也被策略检查：混发批次不会让 shell 藏在只读工具后面")
    void secondCallInBatchIsAlsoAdjudicated() {
        GuardChain chain = new GuardChain(List.of(
                new NameGatedStub("execute", GuardResult.deny("命令命中黑名单"))), noRules());
        GuardContext c = ctx(batch(call("read_file", "path", "a.txt"), call("execute", "command", "rm -rf /")));

        GuardResult result = chain.evaluate(c);
        assertEquals(GuardDecision.DENY, result.decision(), "只看第一个调用名的话这批会被整个放过");
        assertNotNull(result.reason());
        assertTrue(result.reason().contains("execute"), "拒绝理由要点名是哪个调用：" + result.reason());
    }

    @Test
    @DisplayName("批内任一个调用未被记住就整批询问；全部记住才放过")
    void batchNeedsEveryCallRemembered() {
        ToolPermissionRuleDao ruleDao = noRules();
        GuardChain chain = new GuardChain(List.of(), ruleDao);
        GuardContext c = ctx(batch(call("execute", "command", "python a.py"),
                call("write_file", "path", "data/out/r.xlsx")));

        assertEquals(GuardDecision.ASK, chain.evaluate(c).decision());
        chain.recordDecision(c, PermissionDecision.SESSION_ALLOW);
        assertEquals(GuardDecision.ALLOW, chain.evaluate(c).decision(), "两个调用各自的键都进了会话记忆");

        // 批里换一个目录：那个调用没被记住，整批回到询问
        GuardContext other = ctx(batch(call("execute", "command", "python a.py"),
                call("write_file", "path", "data/secret/r.xlsx")));
        assertEquals(GuardDecision.ASK, chain.evaluate(other).decision());
    }

    @Test
    @DisplayName("混发批次的记忆按各自工具名落库：shell 记窄键，其他工具记带有效期的通配")
    void batchRemembersEachCallUnderItsOwnTool() {
        ToolPermissionRuleDao ruleDao = noRules();
        GuardChain chain = new GuardChain(List.of(), ruleDao);
        chain.recordDecision(ctx(batch(call("web_fetch", "url", "https://example.com"),
                call("execute", "command", "git status"))), PermissionDecision.ALWAYS_ALLOW);

        verify(ruleDao).upsert(eq("execute"), eq("exe:git"),
                eq(PermissionDecision.ALWAYS_ALLOW.name()), isNull());
        // shell 的那条授权绝不能顺着批次的上下文记到 web_fetch 头上变成永久通配：通配必须带到期时间
        verify(ruleDao).upsert(eq("web_fetch"), isNull(),
                eq(PermissionDecision.ALWAYS_ALLOW.name()), any(LocalDateTime.class));
        verify(ruleDao, never()).upsert(eq("web_fetch"), any(), any(), isNull());
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
        verify(ruleDao, never()).upsert(any(), any(), any(), any());
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
