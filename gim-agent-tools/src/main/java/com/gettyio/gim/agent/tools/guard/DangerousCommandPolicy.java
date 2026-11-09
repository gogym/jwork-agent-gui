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

import com.gettyio.gim.agent.core.chat.ToolCallsCodec;
import com.gettyio.gim.agent.core.chat.ToolCallsCodec.ToolCallView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 危险命令黑名单：命中即拒绝，防止破坏性 shell 操作。
 * <p>规则集不再写死在代码里——通过 {@link Supplier} 每次评估取最新快照，使设置「安全」页的
 * 增删改与逐条启停<b>无需重启或重建 Spring bean 即实时生效</b>。内置默认集见 {@link #DEFAULT_RULES}
 * （迁移自旧版硬编码黑名单，补齐严重度/分组/描述元数据），供首次播种与「重置」还原。</p>
 * <p>非法正则（用户手输错误）静默跳过，绝不因一条坏规则抛异常中断整条链；编译结果按正则缓存复用。</p>
 *
 * @author gogym
 */
public class DangerousCommandPolicy implements ToolGuard {

    private static final Logger log = LoggerFactory.getLogger(DangerousCommandPolicy.class);

    /** 内置默认规则：迁移自旧版硬编码黑名单，附展示元数据；「重置」即还原这份。 */
    public static final List<DangerousRule> DEFAULT_RULES = List.of(
            new DangerousRule("rm_rf", "\\brm\\s+-[rf]{1,2}\\b",
                    "检测可能导致数据丢失的 rm -rf 命令",
                    DangerousRule.SEVERITY_CRITICAL, "数据破坏", true),
            new DangerousRule("del_force", "\\bdel\\s+/[fsq]\\b",
                    "检测可能导致数据丢失的 del /f /s /q 命令",
                    DangerousRule.SEVERITY_HIGH, "数据破坏", true),
            new DangerousRule("rmdir_s", "\\brmdir\\s+/s\\b",
                    "检测递归删除目录的 rmdir /s 命令",
                    DangerousRule.SEVERITY_HIGH, "数据破坏", true),
            new DangerousRule("rd_s", "\\brd\\s+/s\\b",
                    "检测递归删除目录的 rd /s /q 命令",
                    DangerousRule.SEVERITY_HIGH, "数据破坏", true),
            new DangerousRule("remove_item_recurse", "(?i)\\bremove-item\\b[^\\r\\n]*-recurse",
                    "检测递归删除的 PowerShell Remove-Item -Recurse 命令",
                    DangerousRule.SEVERITY_HIGH, "数据破坏", true),
            new DangerousRule("git_clean_force", "(?i)\\bgit\\s+clean\\b[^\\r\\n]*-{1,2}[a-z]*f",
                    "检测清除未跟踪文件的 git clean -f/-fdx 命令",
                    DangerousRule.SEVERITY_HIGH, "数据破坏", true),
            new DangerousRule("format", "\\bformat\\b",
                    "检测格式化磁盘的 format 命令",
                    DangerousRule.SEVERITY_CRITICAL, "数据破坏", true),
            new DangerousRule("mkfs", "\\bmkfs\\b",
                    "检测重建文件系统的 mkfs 命令",
                    DangerousRule.SEVERITY_CRITICAL, "数据破坏", true),
            new DangerousRule("net_user", "\\bnet\\s+user\\b",
                    "检测改动系统账户的 net user 命令",
                    DangerousRule.SEVERITY_HIGH, "账号与权限", true),
            new DangerousRule("reg_delete", "\\breg\\s+delete\\b",
                    "检测删除注册表项的 reg delete 命令",
                    DangerousRule.SEVERITY_HIGH, "系统篡改", true),
            new DangerousRule("diskpart", "\\bdiskpart\\b",
                    "检测操作磁盘分区的 diskpart 命令",
                    DangerousRule.SEVERITY_CRITICAL, "数据破坏", true),
            new DangerousRule("fork_bomb", ":\\(\\)\\s*\\{.*\\};\\s*:",
                    "检测 Bash Fork 炸弹等耗尽资源的进程创建",
                    DangerousRule.SEVERITY_CRITICAL, "资源滥用", true),
            new DangerousRule("shutdown", "\\bshutdown\\b",
                    "检测关机/重启主机的 shutdown 命令",
                    DangerousRule.SEVERITY_HIGH, "系统中断", true),
            new DangerousRule("dd_raw_device", "\\bdd\\s+if=.*of=/dev/",
                    "检测向裸设备写入的 dd 命令",
                    DangerousRule.SEVERITY_CRITICAL, "数据破坏", true),
            new DangerousRule("chmod_777_root", "\\bchmod\\s+-R\\s+777\\s+/",
                    "检测全局放开权限的 chmod -R 777 /",
                    DangerousRule.SEVERITY_HIGH, "账号与权限", true));

    /** 非法正则的占位编译结果：永不匹配，避免每次评估重复抛异常。 */
    private static final Pattern INVALID = Pattern.compile("(?!)");

    private final Supplier<List<DangerousRule>> rulesSupplier;
    private final Map<String, Pattern> compiled = new ConcurrentHashMap<>();

    public DangerousCommandPolicy(Supplier<List<DangerousRule>> rulesSupplier) {
        this.rulesSupplier = rulesSupplier;
    }

    /** 无外部来源时退回内置默认（便于独立测试）。 */
    public DangerousCommandPolicy() {
        this(DangerousCommandPolicy::currentDefaults);
    }

    /** 取当前生效规则快照：优先读库，读失败/未播种时回退内置默认。 */
    private static List<DangerousRule> currentDefaults() {
        return DEFAULT_RULES;
    }

    /**
     * 按当前规则快照逐条正则匹配工具入参；命中任一危险模式即拒绝，否则不表态（返回 {@code null}）。
     *
     * <p>匹配文本不止入参原文：解释器载荷（{@code cmd /c "…"}、{@code bash -c '…'}、
     * {@code powershell -EncodedCommand <base64>}）会被 {@link CommandPayloads} 逐层展开后一并比对，
     * 否则黑名单只能看见解释器的名字，看不见它要干的活。</p>
     *
     * <p>黑名单的结论是 DENY，而 {@link GuardChain} 让 DENY 先于一切记忆授权与「自动」审批档返回，
     * 因此 CRITICAL 规则不可能被自动放行——严重度元数据决定的是 UI 徽标与排序，不是放行与否。</p>
     */
    @Override
    public GuardResult inspect(GuardContext ctx) {
        List<String> haystack = haystacks(ctx);
        if (haystack.isEmpty()) {
            return null;
        }
        for (DangerousRule rule : rulesSupplier.get()) {
            if (rule == null || !rule.enabled() || rule.pattern() == null || rule.pattern().isBlank()) {
                continue;
            }
            Pattern p = compile(rule.pattern());
            if (p == null) {
                continue;
            }
            for (int i = 0; i < haystack.size(); i++) {
                if (!p.matcher(haystack.get(i)).find()) {
                    continue;
                }
                String label = rule.description() == null || rule.description().isBlank()
                        ? rule.pattern() : rule.description();
                // i>0：命中的是解释器载荷里的脚本，而不是入参原文
                boolean nested = i > 0;
                if (nested) {
                    log.warn("危险命令黑名单命中解释器载荷: rule={} session={}", rule.id(), ctx.sessionId());
                }
                return GuardResult.deny("命中危险命令黑名单：" + label
                        + (nested ? "（载荷来自解释器再执行的那段脚本）" : ""));
            }
        }
        return null;
    }

    /** 待比对文本：入参原文打底，shell 工具再补上各命令字段与其展开出的解释器载荷。 */
    private static List<String> haystacks(GuardContext ctx) {
        String input = ctx.toolInput();
        if (input == null || input.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(input);
        if (!ExecutableAllowlistPolicy.SHELL_TOOL.equalsIgnoreCase(ctx.toolName())) {
            return out;
        }
        for (ToolCallView call : ToolCallsCodec.decode(input)) {
            String command = call.text("command");
            if (command == null || command.isBlank() || out.contains(command)) {
                continue;
            }
            out.add(command);
            out.addAll(CommandPayloads.expand(command));
        }
        return out;
    }

    /** 编译并缓存正则；语法错误返回 {@code null} 表示跳过该规则。 */
    private Pattern compile(String regex) {
        Pattern cached = compiled.get(regex);
        if (cached != null) {
            return cached == INVALID ? null : cached;
        }
        Pattern p;
        try {
            p = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            p = INVALID;
        }
        compiled.put(regex, p);
        return p == INVALID ? null : p;
    }

    @Override
    public int order() {
        return 10;
    }
}
