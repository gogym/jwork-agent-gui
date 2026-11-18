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
import com.gettyio.jwork.agent.core.fs.CanonicalPath;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「总是允许」的规则键：把一次工具调用归组到<b>可被记忆的最小合理单位</b>上。
 * <ul>
 *   <li>Shell（{@code execute}）→ 按可执行程序，如 {@code exe:python}：记住跑脚本不再问，换成 {@code exe:del} 照问；</li>
 *   <li>Shell 且命令里<b>夹带解释器载荷</b>（{@code python -c}、{@code cmd /c}、{@code -EncodedCommand}）
 *       → 按整条命令的指纹 {@code fp:...}：这类调用承载的是任意代码，按程序名记忆等于一次点击交出整个解释器；</li>
 *   <li>文件类工具 → 按所在目录，如 {@code dir:C:\work\out}：同目录的后续写入不再逐次打断；</li>
 *   <li>解析不出程序名/目录的 shell 与文件调用 → 仍按指纹，<b>绝不退回工具级</b>：
 *       {@code execute} 的工具级键等于把整个 shell 交给模型，一次误点就永久生效；</li>
 *   <li>其余工具（MCP、浏览器等）→ 空列表，按整个工具归组，由 {@link GuardChain} 写入时附带有效期。</li>
 * </ul>
 * <p>键集合语义是「<b>全部命中</b>才算已授权」：一条命令里既有 python 又有别的程序时，
 * 只记住 python 不足以跳过确认。宁可多问一次，也不因半个键匹配就放行整个副作用操作。</p>
 *
 * @author gogym
 */
public final class RuleKeys {

    /** 工具级键（会话内存集合里代表「整个工具都允许」，等价于持久化的 {@code rule_content IS NULL}） */
    public static final String TOOL_LEVEL = "*";

    private static final String EXE_PREFIX = "exe:";
    private static final String DIR_PREFIX = "dir:";
    /** 命令指纹前缀：承载任意代码的调用只按这一条命令被记住。 */
    private static final String FP_PREFIX = "fp:";

    /** 指纹取 SHA-256 的前 16 个十六进制位（64 位）：只用于本机规则比对，够判别且人可读。 */
    private static final int FP_HEX_CHARS = 16;

    /** 文件类工具的入参字段候选（框架各工具命名不完全统一）。 */
    private static final List<String> PATH_FIELDS = List.of("path", "file_path", "filename", "target");

    private RuleKeys() {
    }

    /** 本次调用需要被记住的键（去重保序）；空列表表示按整个工具归组（仅非 shell/文件工具会如此）。 */
    public static List<String> forCall(String toolName, String toolInput) {
        String tool = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        boolean shell = isShell(tool);
        boolean file = !shell && isFileTool(tool);
        List<ToolCallView> calls = ToolCallsCodec.decode(toolInput);
        Set<String> keys = new LinkedHashSet<>();
        if (shell) {
            for (ToolCallView call : calls) {
                String command = call.text("command");
                if (hasInterpreterPayload(command)) {
                    keys.add(FP_PREFIX + fingerprint(command));
                    continue;
                }
                for (String exe : ExecutableAllowlistPolicy.executablesOf(command)) {
                    keys.add(EXE_PREFIX + exe);
                }
            }
        } else if (file) {
            for (ToolCallView call : calls) {
                String dir = parentDirOf(call);
                if (dir != null) {
                    keys.add(DIR_PREFIX + dir);
                }
            }
        }
        if (keys.isEmpty() && (shell || file)) {
            keys.add(FP_PREFIX + fingerprint(toolInput));
        }
        return keys.stream().sorted().toList();
    }

    /** 键集合是否被 {@code remembered} 全覆盖；空键集只认工具级标记。 */
    public static boolean covered(List<String> keys, Set<String> remembered) {
        if (keys.isEmpty()) {
            return remembered.contains(TOOL_LEVEL);
        }
        return remembered.containsAll(keys);
    }

    /**
     * 命令里是否夹带了由解释器再执行一遍的脚本载荷。
     * <p>判据用的是黑名单与白名单共用的载荷展开器：同一套解释器标志表，避免「检查看得见的载荷，
     * 记忆看不见」这种两边口径不一致的漏网。</p>
     */
    private static boolean hasInterpreterPayload(String command) {
        return command != null && !command.isBlank() && !CommandPayloads.expand(command).isEmpty();
    }

    /** 归一化文本指纹（折叠空白）；{@code null} 与空白输入归一到同一个值，不会误配真实命令。 */
    static String fingerprint(String text) {
        String normalized = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // JLS 要求每个实现都提供 SHA-256；真缺了也不能退回工具级通配，让这条记忆直接炸出来
            throw new IllegalStateException("缺少 SHA-256 实现，无法生成命令指纹", e);
        }
        return HexFormat.of().formatHex(digest).substring(0, FP_HEX_CHARS);
    }

    private static boolean isShell(String tool) {
        return ExecutableAllowlistPolicy.SHELL_TOOL.equalsIgnoreCase(tool) || tool.contains("shell");
    }

    private static boolean isFileTool(String tool) {
        return tool.contains("file") || tool.contains("write") || tool.contains("edit");
    }

    /**
     * 取本次调用涉及路径的父目录（规范形，符号链接已跟随）；解析不出返回 null。
     * <p>规范形很关键：{@code dir:} 键是「这个目录以内的写入以后都不再问」，若按字符串归一，
     * 目录里一个指向别处的链接就能让「问过的目录」和「真正写进去的目录」不是同一个地方。</p>
     */
    private static String parentDirOf(ToolCallView call) {
        for (String field : PATH_FIELDS) {
            String raw = call.text(field);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                Path parent = CanonicalPath.real(Paths.get(raw)).getParent();
                return parent == null ? null : parent.toString();
            } catch (InvalidPathException e) {
                return null;
            }
        }
        return null;
    }
}
