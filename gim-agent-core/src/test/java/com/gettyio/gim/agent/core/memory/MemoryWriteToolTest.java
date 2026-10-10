package com.gettyio.gim.agent.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryWriteTool} 单测：把事实按分类追加写入 {@code memory/<清洗名>.md}，
 * 名非法/内容为空返回错误串且不落任何文件。
 */
class MemoryWriteToolTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("memory_write：写 memory/<名>.md 且追加不覆盖，返回保存条数")
    void appendsToCategoryFile() throws IOException {
        MemoryWriteTool tool = new MemoryWriteTool(temp);

        assertEquals("Saved 1 memory to memory/用户偏好.md", tool.memoryWrite("用户偏好", "- 简洁回答"));
        Path file = temp.resolve("memory").resolve("用户偏好.md");
        assertEquals("\n- 简洁回答\n", Files.readString(file));

        assertEquals("Saved 1 memory to memory/用户偏好.md", tool.memoryWrite("用户偏好", "- 中文回复"));
        assertEquals("\n- 简洁回答\n\n- 中文回复\n", Files.readString(file), "应追加而不是覆盖");

        assertEquals("Saved 2 memories to memory/项目笔记.md", tool.memoryWrite("项目笔记", "- 周五发版\n- 分支命名 kebab-case"));
    }

    @Test
    @DisplayName("memory_write：内容为空或名非法（日期格式）返回错误串且不创建文件")
    void rejectsInvalidInput() {
        MemoryWriteTool tool = new MemoryWriteTool(temp);

        assertEquals("Error: content is required", tool.memoryWrite("用户偏好", "  "));
        assertTrue(tool.memoryWrite("2026-10-01", "- x").startsWith("Error: invalid memory file name"));
        assertFalse(Files.exists(temp.resolve("memory")), "非法输入不得落任何文件");
    }
}
