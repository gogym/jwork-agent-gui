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

package com.gettyio.gim.agent.core.memory;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 智能体分类记忆写入工具（{@code memory_write}）：把事实按主题写入 {@code memory/<分类>.md}。
 * <p>框架的 memory_save 只写 MEMORY.md 与当日台账；本工具补上「按分类整理」的能力——
 * 写入的分类文件会被 memory_search 搜索到，并随下次沉淀自动合并进 MEMORY.md。
 * 追加写入（append-only），去重与精简交由框架的沉淀环节（与框架 memory_save 同语义）。</p>
 * <p>记忆总开关关闭时与本工具天然解耦：装配方（{@code HarnessAgentGateway}）在登记/装配
 * 环节按记忆开关跳过，与框架记忆工具同门禁。</p>
 *
 * @author gogym
 */
public class MemoryWriteTool {

    private static final Logger log = LoggerFactory.getLogger(MemoryWriteTool.class);

    /** 分类记忆文件落点：{@code <workspace>/memory/}（与每日台账同一文件夹） */
    private final Path memoryDir;

    public MemoryWriteTool(Path workspace) {
        this.memoryDir = workspace.resolve("memory");
    }

    /** 工具入口（{@code memory_write}）：分类事实追加写入 {@code memory/<分类>.md}；分类名非法或写入失败以文本返回错误，不抛异常。 */
    @Tool(
            name = "memory_write",
            description =
                    "Save facts into a named category memory file under the memory/ folder"
                            + " (e.g. name \"user-preferences\" writes memory/user-preferences.md)."
                            + " Use this instead of memory_save when the facts clearly belong to a"
                            + " specific topic or theme, so long-term memories stay organized by"
                            + " category. Category files are searchable via memory_search and are"
                            + " periodically merged into MEMORY.md by the harness.")
    public String memoryWrite(
            @ToolParam(
                            name = "name",
                            description =
                                    "Category file name, without extension (e.g. \"user-preferences\","
                                            + " \"project-notes\"). Letters, digits and CJK; other"
                                            + " characters are folded into hyphens.")
                    String name,
            @ToolParam(
                            name = "content",
                            description =
                                    "Markdown bullet list of facts for this category. Each bullet"
                                            + " should be a concise, self-contained fact. Example:\\n"
                                            + "- User prefers concise answers\\n"
                                            + "- Deploys happen on Fridays")
                    String content) {
        if (content == null || content.isBlank()) {
            return "Error: content is required";
        }
        String fileName = MemoryService.normalizeCategoryName(name);
        if (fileName == null) {
            return "Error: invalid memory file name — use letters, digits or CJK, without"
                    + " date-like names";
        }
        Path file = memoryDir.resolve(fileName);
        try {
            Files.createDirectories(memoryDir);
            Files.writeString(file, "\n" + content.strip() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("写入分类记忆文件失败: {}", file, e);
            return "Error: failed to save memory file " + fileName;
        }
        long count = content.strip().lines().filter(l -> l.stripLeading().startsWith("-")).count();
        if (count == 0) {
            count = 1;
        }
        return "Saved " + count + " memor" + (count == 1 ? "y" : "ies") + " to memory/" + fileName;
    }
}
