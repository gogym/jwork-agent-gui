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

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「总是允许」的规则键：把一次工具调用归组到<b>可被记忆的最小合理单位</b>上。
 * <ul>
 *   <li>Shell（{@code execute}）→ 按可执行程序，如 {@code exe:python}：记住跑脚本不再问，换成 {@code exe:del} 照问；</li>
 *   <li>文件类工具 → 按所在目录，如 {@code dir:C:\work\out}：同目录的后续写入不再逐次打断；</li>
 *   <li>其余（解析不出/非副作用工具）→ 空列表，按整个工具归组。</li>
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

    /** 文件类工具的入参字段候选（框架各工具命名不完全统一）。 */
    private static final List<String> PATH_FIELDS = List.of("path", "file_path", "filename", "target");

    private RuleKeys() {
    }

    /** 本次调用需要被记住的键（去重保序）；空列表表示按整个工具归组。 */
    public static List<String> forCall(String toolName, String toolInput) {
        List<ToolCallView> calls = ToolCallsCodec.decode(toolInput);
        if (calls.isEmpty()) {
            return List.of();
        }
        String tool = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        Set<String> keys = new LinkedHashSet<>();
        if (isShell(tool)) {
            for (ToolCallView call : calls) {
                for (String exe : ExecutableAllowlistPolicy.executablesOf(call.text("command"))) {
                    keys.add(EXE_PREFIX + exe);
                }
            }
        } else if (isFileTool(tool)) {
            for (ToolCallView call : calls) {
                String dir = parentDirOf(call);
                if (dir != null) {
                    keys.add(DIR_PREFIX + dir);
                }
            }
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

    private static boolean isShell(String tool) {
        return ExecutableAllowlistPolicy.SHELL_TOOL.equalsIgnoreCase(tool) || tool.contains("shell");
    }

    private static boolean isFileTool(String tool) {
        return tool.contains("file") || tool.contains("write") || tool.contains("edit");
    }

    /** 取本次调用涉及路径的父目录（绝对、规范化）；解析不出返回 null（不因此放行，只是退回工具级）。 */
    private static String parentDirOf(ToolCallView call) {
        for (String field : PATH_FIELDS) {
            String raw = call.text(field);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                Path abs = Paths.get(raw).toAbsolutePath().normalize();
                Path parent = abs.getParent();
                return parent == null ? null : parent.toString();
            } catch (InvalidPathException e) {
                return null;
            }
        }
        return null;
    }
}
