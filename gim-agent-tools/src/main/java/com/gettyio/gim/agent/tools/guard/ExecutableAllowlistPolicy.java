package com.gettyio.gim.agent.tools.guard;

import com.gettyio.gim.agent.core.chat.ToolCallsCodec;
import com.gettyio.gim.agent.core.chat.ToolCallsCodec.ToolCallView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Shell 可执行程序白名单：把 {@code execute} 工具能启动的外部程序收紧到一组受控的二进制。
 * <p>软件自动化（脚本驱动 Photoshop/Blender/LibreOffice 等）会真实启动外部程序，权限面比改文件更大；
 * 本策略是这一通道的硬边界——白名单外的程序<b>直接拒绝</b>，连确认弹窗都不给，因此「自动」审批档也拦得住。
 * 白名单内的程序仍会落到链尾的默认询问，交由用户逐次确认（重复打扰由「总是允许」规则短路）。</p>
 * <p>命中范围仅限框架注册的 shell 工具（{@code execute}），文件类工具不在此策略职责内。
 * 解析对管道/与或/分号/换行分段取各段首个可执行名，无法解析的畸形命令不表态（交由后续链处理），
 * 遵循「解析失败不得据此放行副作用」的一贯原则——沉默即维持链尾默认询问。</p>
 */
public class ExecutableAllowlistPolicy implements ToolGuard {

    private static final Logger log = LoggerFactory.getLogger(ExecutableAllowlistPolicy.class);

    /** 框架注册的 shell 工具名（ShellExecuteTool.NAME）。 */
    public static final String SHELL_TOOL = "execute";

    /** 命令分段符：&&、||、管道 |、分号 ;、按位与 &、换行。 */
    private static final Pattern SEGMENT_SPLIT = Pattern.compile("&&|\\|\\||;|\\||&|\\r?\\n");

    /** 形如 FOO=bar 的环境变量赋值前缀（应跳过、取其后真正的命令名）。 */
    private static final Pattern ENV_ASSIGN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=");

    /**
     * 默认白名单：shell 解释器 + 常用开发/构建 CLI + 可脚本化的办公/多媒体无头工具 + 基础 coreutils。
     * 有意<b>不</b>含网络外发工具（curl/wget）与包/软件安装器（winget/choco/scoop/msiexec），
     * 以贴合「本地优先·数据不出本机」定位；用户可按需写入扩展文件放行。
     */
    public static final Set<String> DEFAULT_ALLOWED = Set.of(
            // shell 解释器
            "cmd", "powershell", "pwsh", "bash", "sh",
            // 语言运行时与包管理（本机）
            "python", "python3", "py", "pip", "pip3", "node", "npm", "npx", "yarn", "pnpm",
            "java", "javac", "mvn", "gradle", "go", "cargo", "rustc", "dotnet",
            // 版本控制与构建
            "git", "svn", "make", "cmake",
            // 办公/多媒体可脚本化 CLI
            "soffice", "libreoffice", "blender", "photoshop", "illustrator", "inkscape", "gimp",
            "ffmpeg", "ffprobe", "magick", "identify", "pnmtopng", "pdftotext", "pdfinfo", "gs", "openssl",
            // 归档/压缩
            "tar", "zip", "unzip", "gzip", "gunzip", "7z",
            // 基础 coreutils（管道里常见）
            "echo", "printf", "pwd", "cd", "pushd", "popd", "ls", "dir", "cat", "type", "more",
            "head", "tail", "find", "findstr", "grep", "rg", "sed", "awk", "sort", "uniq", "wc",
            "cut", "tr", "tee", "xargs", "basename", "dirname", "diff", "touch", "mkdir", "cp",
            "copy", "mv", "move", "ren", "rename", "ln", "tree", "which", "where", "whoami", "date",
            "env", "set", "unset", "export", "true", "false", "test");

    private final Set<String> baseAllowed;
    /** 动态扩展白名单（如用户扩展文件里的程序名），每次评估取最新快照。 */
    private final Supplier<Collection<String>> extraAllowed;

    public ExecutableAllowlistPolicy() {
        this(DEFAULT_ALLOWED, List::of);
    }

    public ExecutableAllowlistPolicy(Set<String> baseAllowed, Supplier<Collection<String>> extraAllowed) {
        this.baseAllowed = baseAllowed;
        this.extraAllowed = extraAllowed;
    }

    @Override
    public GuardResult inspect(GuardContext ctx) {
        // 仅约束 shell 执行工具，其余（含文件类）不表态
        if (!SHELL_TOOL.equalsIgnoreCase(ctx.toolName())) {
            return null;
        }
        Set<String> allowed = allowedSnapshot();
        Set<String> offending = new LinkedHashSet<>();
        for (ToolCallView call : ToolCallsCodec.decode(ctx.toolInput())) {
            String command = call.text("command");
            collectOffending(command, allowed, offending);
        }
        if (offending.isEmpty()) {
            // 全部在白名单内：不表态，交给链尾默认询问（用户仍会逐次确认）
            return null;
        }
        String reason = "以下程序不在允许启动的白名单内，已拒绝：" + String.join("、", offending)
                + "；如确需使用，请把程序名加入 ~/.gim-agent/shell-allowlist.txt";
        log.warn("shell 白名单拒绝: session={}, blocked={}", ctx.sessionId(), offending);
        return GuardResult.deny(reason);
    }

    /** 合并静态白名单与动态扩展快照，全部归一为小写基名。 */
    private Set<String> allowedSnapshot() {
        Set<String> merged = new LinkedHashSet<>(baseAllowed);
        for (String extra : extraAllowed.get()) {
            String norm = normalize(extra);
            if (norm != null) {
                merged.add(norm);
            }
        }
        return merged;
    }

    /** 拆解命令串，把各分段首个不在白名单的可执行名收集起来。 */
    private void collectOffending(String command, Set<String> allowed, Set<String> out) {
        if (command == null || command.isBlank()) {
            return;
        }
        for (String seg : SEGMENT_SPLIT.split(command)) {
            String exe = firstExecutable(seg);
            if (exe != null && !allowed.contains(exe)) {
                out.add(exe);
            }
        }
    }

    /**
     * 取一段命令里首个真正的可执行名：跳过前导的环境变量赋值（FOO=bar），
     * 去引号、取路径基名、去可执行扩展名、转小写。取不到（纯赋值/空段）返回 {@code null} 表示不判定。
     */
    private static String firstExecutable(String segment) {
        String s = segment.strip();
        String[] tokens = s.split("\\s+");
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            if (ENV_ASSIGN.matcher(token).find()) {
                continue; // 环境变量赋值前缀，看下一个 token
            }
            return normalize(token);
        }
        return null;
    }

    /** 归一化单个程序名：去首尾引号、取文件名基名、去扩展名、转小写；空则返回 {@code null}。 */
    private static String normalize(String rawToken) {
        if (rawToken == null) {
            return null;
        }
        String token = rawToken.strip();
        // 去掉包裹的成对引号
        if (token.length() >= 2 && (token.startsWith("\"") && token.endsWith("\"")
                || token.startsWith("'") && token.endsWith("'"))) {
            token = token.substring(1, token.length() - 1).strip();
        }
        if (token.isEmpty()) {
            return null;
        }
        // 取路径基名（兼容 Windows 反斜杠与 POSIX 正斜杠；绝对/相对都可）
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        if (slash >= 0) {
            token = token.substring(slash + 1);
        }
        // 去掉可执行扩展名
        int dot = token.lastIndexOf('.');
        if (dot > 0) {
            String ext = token.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (ext.equals("exe") || ext.equals("bat") || ext.equals("cmd") || ext.equals("com")) {
                token = token.substring(0, dot);
            }
        }
        token = token.toLowerCase(Locale.ROOT);
        return token.isEmpty() ? null : token;
    }

    /**
     * 从用户扩展文件读取额外白名单程序名：每行一个，忽略空行与 {@code #} 注释。
     * 文件不存在或读失败均视为无扩展（不影响静态白名单）。
     */
    public static Supplier<Collection<String>> fileSupplier(Path allowlistFile) {
        return () -> {
            if (allowlistFile == null || !Files.isRegularFile(allowlistFile)) {
                return List.of();
            }
            try {
                return Files.readAllLines(allowlistFile, StandardCharsets.UTF_8).stream()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .toList();
            } catch (IOException e) {
                log.warn("读取 shell 白名单扩展文件失败: {}", allowlistFile, e);
                return List.of();
            }
        };
    }

    @Override
    public int order() {
        // 排在破坏性命令黑名单（10）之后、文件路径白名单（20）之前
        return 15;
    }
}
