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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DangerousCommandPolicy} 单测：命中拒绝、良性放行、停用规则跳过、非法正则不致中断。
 * <p>纯逻辑，无 Spring/JavaFX 依赖。</p>
 *
 * @author gogym
 */
class DangerousCommandPolicyTest {

    private static GuardContext ctx(String input) {
        return new GuardContext("s1", "execute", input);
    }

    private static DangerousRule rule(String id, String pattern, boolean enabled) {
        return new DangerousRule(id, pattern, "desc-" + id,
                DangerousRule.SEVERITY_HIGH, "数据破坏", enabled);
    }

    @Test
    @DisplayName("默认黑名单命中 rm -rf 硬拒绝")
    void deniesDangerousCommand() {
        DangerousCommandPolicy policy = new DangerousCommandPolicy();
        GuardResult result = policy.inspect(ctx("rm -rf /home/user/data"));
        assertNotNull(result);
        assertEquals(GuardDecision.DENY, result.decision());
    }

    @Test
    @DisplayName("良性命令不表态（返回 null 交链后续）")
    void benignCommandIsSilent() {
        DangerousCommandPolicy policy = new DangerousCommandPolicy();
        assertNull(policy.inspect(ctx("ls -la /tmp")));
    }

    @Test
    @DisplayName("空/空白输入不表态")
    void blankInputIsSilent() {
        DangerousCommandPolicy policy = new DangerousCommandPolicy();
        assertNull(policy.inspect(ctx(null)));
        assertNull(policy.inspect(ctx("   ")));
    }

    @Test
    @DisplayName("补齐的递归删除形态：rd /s、Remove-Item -Recurse、git clean -fdx")
    void newlyFilledBlacklistHits() {
        DangerousCommandPolicy policy = new DangerousCommandPolicy();
        assertEquals(GuardDecision.DENY, policy.inspect(ctx("rd /s /q C:\\Users\\dev\\Documents")).decision());
        assertEquals(GuardDecision.DENY,
                policy.inspect(ctx("Remove-Item -Recurse -Force .\\out")).decision());
        assertEquals(GuardDecision.DENY,
                policy.inspect(ctx("remove-item .\\out -recurse")).decision(), "大小写与参数顺序都要命中");
        assertEquals(GuardDecision.DENY, policy.inspect(ctx("git clean -fdx")).decision());
        // 预览用的 git clean -nd 不该命中
        assertNull(policy.inspect(ctx("git clean -nd")));
    }

    @Test
    @DisplayName("解释器载荷解码后仍被拦：base64 的 -EncodedCommand 不再是盲区")
    void interpreterPayloadIsUnwrappedBeforeMatching() {
        DangerousCommandPolicy policy = new DangerousCommandPolicy();
        String b64 = java.util.Base64.getEncoder().encodeToString(
                "rd /s /q D:\\backup".getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        // 入参原文里没有 "rd /s"，只有解码出来的载荷里有
        GuardContext hidden = jsonCtx("powershell -NoProfile -EncodedCommand " + b64);
        assertFalse(java.util.regex.Pattern.compile("rd\\s+/s").matcher(hidden.toolInput()).find(),
                "前置条件：原文确实看不含危险片段");
        GuardResult result = policy.inspect(hidden);
        assertNotNull(result);
        assertEquals(GuardDecision.DENY, result.decision());
        assertTrue(result.reason().contains("载荷"), result.reason());
    }

    /** shell 工具入参（ToolCallsCodec 的 JSON 形态），命令文本经编码后原文不可读。 */
    private static GuardContext jsonCtx(String command) {
        String escaped = command.replace("\\", "\\\\").replace("\"", "\\\"");
        return new GuardContext("s1", "execute",
                "[{\"name\":\"execute\",\"input\":{\"command\":\"" + escaped + "\"}}]");
    }

    @Test
    @DisplayName("停用规则被跳过，即便模式本会命中也不拒绝")
    void disabledRuleSkipped() {
        Supplier<List<DangerousRule>> supplier = () -> List.of(rule("rm_rf", "\\brm\\s+-[rf]{1,2}\\b", false));
        DangerousCommandPolicy policy = new DangerousCommandPolicy(supplier);
        assertNull(policy.inspect(ctx("rm -rf /")), "规则已停用不应命中");
    }

    @Test
    @DisplayName("非法正则静默跳过，不抛异常且不影响其他规则评估")
    void invalidRegexSkippedWithoutThrowing() {
        Supplier<List<DangerousRule>> supplier = () -> List.of(
                rule("bad", "[unclosed", true),
                rule("format", "\\bformat\\b", true));
        DangerousCommandPolicy policy = new DangerousCommandPolicy(supplier);
        // 坏规则不炸；好规则照常命中
        GuardResult result = policy.inspect(ctx("format c:"));
        assertNotNull(result);
        assertEquals(GuardDecision.DENY, result.decision());
        // 只含坏规则时：良性输入也不表态（不抛）
        assertNull(new DangerousCommandPolicy(() -> List.of(rule("bad", "[unclosed", true)))
                .inspect(ctx("echo hi")));
    }
}
