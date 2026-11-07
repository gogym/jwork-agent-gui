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
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖写工具：{@link OverwritableWriteFileTool} 对已有文件的三档行为——默认拒绝（与框架内置一致）、
 * {@code overwrite=true} 才改写、非法路径只回错误文本而不抛异常。
 * <p>用框架真实 {@code LocalFilesystem} 作后端，验的就是「同名注册后行为不劣于内置实现」这条前提。</p>
 *
 * @author gogym
 */
class OverwritableWriteFileToolTest {

    @TempDir
    Path ws;

    private OverwritableWriteFileTool tool() {
        return new OverwritableWriteFileTool(new LocalFilesystem(ws));
    }

    @Test
    @DisplayName("新文件照常创建，与框架内置语义一致")
    void createsNewFile() throws IOException {
        String r = tool().writeFile(RuntimeContext.empty(), "build.py", "print(1)", null);

        assertEquals("Written to build.py", r);
        assertEquals("print(1)", Files.readString(ws.resolve("build.py"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("已存在且未声明 overwrite：保持框架的拒绝提示，旧内容不动")
    void refusesWithoutOverwrite() throws IOException {
        OverwritableWriteFileTool tool = tool();
        tool.writeFile(RuntimeContext.empty(), "report.md", "第一版", null);

        String r = tool.writeFile(RuntimeContext.empty(), "report.md", "第二版", false);

        assertTrue(r.startsWith("Error:"), () -> "应拒绝覆盖，实际: " + r);
        assertTrue(r.contains("already exists"), () -> "应给出换名/编辑指引，实际: " + r);
        assertEquals("第一版", Files.readString(ws.resolve("report.md"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("overwrite=true 才整体替换")
    void overwritesWhenForced() throws IOException {
        OverwritableWriteFileTool tool = tool();
        tool.writeFile(RuntimeContext.empty(), "report.md", "第一版很长很长", null);

        String r = tool.writeFile(RuntimeContext.empty(), "report.md", "第二版", true);

        assertEquals("Overwrote report.md", r);
        assertEquals("第二版", Files.readString(ws.resolve("report.md"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("非法路径返回错误文本，不抛异常打断推理循环")
    void reportsBadPath() throws IOException {
        OverwritableWriteFileTool tool = tool();
        assertTrue(tool.writeFile(RuntimeContext.empty(), "   ", "x", true).contains("empty"));

        Files.writeString(ws.resolve("note.txt"), "x", StandardCharsets.UTF_8);
        String r = tool.writeFile(RuntimeContext.empty(), "note.txt/inner.md", "y", true);

        assertTrue(r.startsWith("Error:"), () -> "父路径是文件时应回错误文本，实际: " + r);
    }
}
