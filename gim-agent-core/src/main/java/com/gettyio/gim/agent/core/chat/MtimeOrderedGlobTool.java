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

package com.gettyio.gim.agent.core.chat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;

import java.util.ArrayList;
import java.util.List;

/**
 * glob_files 的本地化替代：按最近修改排序，让截断丢掉的是最老的产物。
 *
 * <p>为什么要替代：框架 {@code FilesystemTool.globFiles} 拿 {@code AbstractFilesystem.glob} 的结果
 * 直接 {@code boundedListing} 截断，而 {@code LocalFilesystem.glob} 返回的顺序是遍历序（近似路径字典序）。
 * 于是双星通配配 xlsx 后缀命中几百个历史文件时，模型看到的是最早那批，刚生成的那份落在截断之外——
 * 「我导出了报表，去找它」这一步就会找错文件，甚至再导一份。</p>
 *
 * <p>这里只做一件确定的事：新→旧排。{@code FileInfo.modifiedAt} 是 ISO-8601 UTC 串，字典序即时间序，
 * 直接比；同一时刻改的按路径排，保证结果可复现。工具名与 {@code pattern/path/limit} 入参不变。</p>
 *
 * @author gogym
 */
public final class MtimeOrderedGlobTool {

    /** 被覆盖的工具名（与框架 FilesystemTool.globFiles 注册名一致）。 */
    public static final String NAME = "glob_files";

    /** 缺省返回条数上限（与框架一致）。 */
    public static final int DEFAULT_LIMIT = 200;

    /** 条数硬上限（与框架一致）。 */
    public static final int MAX_LIMIT = 1_000;

    /** 单条输出里带的修改时间精度：到分钟就够判断「是不是刚那份」，不必占满一行。 */
    private static final int TIMESTAMP_CHARS = 16;

    private final AbstractFilesystem filesystem;

    public MtimeOrderedGlobTool(AbstractFilesystem filesystem) {
        this.filesystem = filesystem;
    }

    @Tool(name = NAME, readOnly = true, concurrencySafe = true,
            description = "Find files matching a glob pattern (e.g. **/*.java, *.xlsx), newest"
                    + " modified first so the file you just produced stays in the result when the"
                    + " list is long. Entries are capped (default " + DEFAULT_LIMIT
                    + "; hard maximum " + MAX_LIMIT + ").")
    public String globFiles(
            RuntimeContext runtimeContext,
            @ToolParam(name = "pattern", description = "Glob pattern (e.g., **/*.java)")
                    String pattern,
            @ToolParam(name = "path", description = "Base directory to search from",
                    required = false) String path,
            @ToolParam(name = "limit", required = false,
                    description = "Max files to return (default: " + DEFAULT_LIMIT
                            + "; hard maximum: " + MAX_LIMIT + ")") Integer limit) {
        if (pattern == null || pattern.isBlank()) {
            return "Error: pattern must not be empty";
        }
        int cap = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(1, limit), MAX_LIMIT);
        GlobResult r;
        try {
            r = filesystem.glob(runtimeContext, pattern.trim(),
                    normalize(path == null || path.isBlank() ? "." : path, runtimeContext));
        } catch (SecurityException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (!r.isSuccess()) {
            return "Error: " + r.error();
        }
        List<FileInfo> files = r.matches();
        if (files == null || files.isEmpty()) {
            return "No matching files found";
        }
        List<FileInfo> ordered = new ArrayList<>(files);
        ordered.sort((a, b) -> {
            int byMtime = mtime(b).compareTo(mtime(a));
            return byMtime != 0 ? byMtime : a.path().compareTo(b.path());
        });
        StringBuilder out = new StringBuilder();
        int shown = Math.min(cap, ordered.size());
        for (int i = 0; i < shown; i++) {
            FileInfo f = ordered.get(i);
            out.append(f.path());
            if (f.isDirectory()) {
                out.append('/');
            } else {
                out.append("  (").append(f.size()).append(" bytes, modified ")
                        .append(shorten(f.modifiedAt())).append(')');
            }
            out.append('\n');
        }
        if (shown < ordered.size()) {
            out.append("[Results truncated: showing ").append(shown).append(" of ")
                    .append(ordered.size()).append(" files, newest first. Narrow the pattern/path")
                    .append(" or increase limit (hard maximum: ").append(MAX_LIMIT).append(").]");
        }
        return out.toString().stripTrailing();
    }

    /** 缺失时间戳排到最老，免得空串把结果顶到队首。 */
    private static String mtime(FileInfo f) {
        String m = f.modifiedAt();
        return m == null || m.isBlank() ? "0000-00-00T00:00:00Z" : m;
    }

    private static String shorten(String modifiedAt) {
        if (modifiedAt == null || modifiedAt.isBlank()) {
            return "unknown";
        }
        return modifiedAt.length() <= TIMESTAMP_CHARS ? modifiedAt
                : modifiedAt.substring(0, TIMESTAMP_CHARS);
    }

    /** 与框架一致的路径归一：模型常按提示里的绝对路径给 path，需剥掉当前模式的工作区前缀。 */
    private static String normalize(String path, RuntimeContext runtimeContext) {
        WorkspacePathNormalizer normalizer =
                runtimeContext == null ? null : runtimeContext.get(WorkspacePathNormalizer.class);
        return normalizer == null ? path : normalizer.normalize(path, runtimeContext);
    }
}
