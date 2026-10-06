package com.gettyio.gim.agent.tools.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ExecutableAllowlistPolicy} 单测：仅约束 shell 工具、白名单内沉默、白名单外拒绝、
 * 分段/环境变量/路径归一化处理、动态扩展放行。
 */
class ExecutableAllowlistPolicyTest {

    /** 构造框架 shell 工具入参（ToolCallsCodec JSON 格式）。 */
    private static GuardContext shell(String command) {
        String json = "[{\"name\":\"execute\",\"input\":{\"command\":" + jsonString(command) + "}}]";
        return new GuardContext("s1", ExecutableAllowlistPolicy.SHELL_TOOL, json);
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    @DisplayName("非 shell 工具不表态")
    void ignoresNonShellTool() {
        ExecutableAllowlistPolicy policy = new ExecutableAllowlistPolicy();
        GuardContext ctx = new GuardContext("s1", "edit_file",
                "[{\"name\":\"edit_file\",\"input\":{\"path\":\"a.txt\"}}]");
        assertNull(policy.inspect(ctx));
    }

    @Test
    @DisplayName("白名单内程序沉默（交链尾默认询问）")
    void allowedProgramIsSilent() {
        ExecutableAllowlistPolicy policy = new ExecutableAllowlistPolicy();
        assertNull(policy.inspect(shell("git status")));
        assertNull(policy.inspect(shell("ls -la | grep foo")));
    }

    @Test
    @DisplayName("白名单外程序硬拒绝")
    void blockedProgramDenied() {
        ExecutableAllowlistPolicy policy = new ExecutableAllowlistPolicy();
        GuardResult result = policy.inspect(shell("curl https://evil.example"));
        assertNotNull(result);
        assertEquals(GuardDecision.DENY, result.decision());
    }

    @Test
    @DisplayName("管道/与或分段：任一越界程序即拒绝")
    void segmentedCommandBlocksOffender() {
        ExecutableAllowlistPolicy policy = new ExecutableAllowlistPolicy();
        assertNotNull(policy.inspect(shell("python run.py && curl -s url")));
        assertNull(policy.inspect(shell("python run.py && git push")));
    }

    @Test
    @DisplayName("跳过环境变量赋值前缀取真实命令名")
    void skipsEnvAssign() {
        ExecutableAllowlistPolicy policy = new ExecutableAllowlistPolicy();
        GuardResult result = policy.inspect(shell("FOO=bar curl url"));
        assertNotNull(result);
        assertEquals(GuardDecision.DENY, result.decision());
    }

    @Test
    @DisplayName("绝对路径取基名、去 exe 扩展名后比对白名单")
    void normalizesPathAndExtension() {
        ExecutableAllowlistPolicy policy = new ExecutableAllowlistPolicy();
        assertNull(policy.inspect(shell("/usr/bin/git.exe status")));
        assertEquals(GuardDecision.DENY, policy.inspect(shell("C:\\Windows\\notepad.exe")).decision());
    }

    @Test
    @DisplayName("动态扩展白名单可放行额外程序")
    void extraAllowlistExtends() {
        Collection<String> extra = List.of("curl");
        ExecutableAllowlistPolicy strict = new ExecutableAllowlistPolicy();
        assertEquals(GuardDecision.DENY, strict.inspect(shell("curl url")).decision());

        ExecutableAllowlistPolicy relaxed =
                new ExecutableAllowlistPolicy(ExecutableAllowlistPolicy.DEFAULT_ALLOWED, () -> extra);
        assertNull(relaxed.inspect(shell("curl url")));
    }
}
