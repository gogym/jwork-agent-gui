package com.gettyio.gim.agent.tools.guard;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 危险命令黑名单：命中即拒绝，防止破坏性 shell 操作。
 * <p>规则表可按需在设置界面扩展。</p>
 */
public class DangerousCommandPolicy implements ToolGuard {

    private final List<Pattern> denyPatterns;

    public DangerousCommandPolicy() {
        this(java.util.stream.Stream.of(
                "\\brm\\s+-[rf]{1,2}\\b",          // rm -rf / rm -fr
                "\\bdel\\s+/[fsq]\\b",             // del /f /s /q
                "\\brmdir\\s+/s\\b",               // rmdir /s
                "\\bformat\\b",                    // format
                "\\bmkfs\\b",                      // mkfs
                "\\bnet\\s+user\\b",               // net user
                "\\breg\\s+delete\\b",             // reg delete
                "\\bdiskpart\\b",                  // diskpart
                ":\\(\\)\\s*\\{.*\\};\\s*:",        // fork bomb :(){ :|:& };:
                "\\bshutdown\\b",                  // shutdown
                "\\bdd\\s+if=.*of=/dev/",          // dd 写裸设备
                "\\bchmod\\s+-R\\s+777\\s+/"        // 全局改权限
        ).map(Pattern::compile).toList());
    }

    public DangerousCommandPolicy(List<Pattern> denyPatterns) {
        this.denyPatterns = denyPatterns;
    }

    @Override
    public GuardResult inspect(GuardContext ctx) {
        String input = ctx.toolInput();
        if (input == null || input.isBlank()) {
            return null;
        }
        for (Pattern p : denyPatterns) {
            if (p.matcher(input).find()) {
                return GuardResult.deny("命中危险命令黑名单：" + p.pattern());
            }
        }
        return null;
    }

    @Override
    public int order() {
        return 10;
    }
}
