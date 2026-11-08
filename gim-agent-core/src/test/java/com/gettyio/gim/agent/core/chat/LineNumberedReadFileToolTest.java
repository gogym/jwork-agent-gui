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
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行号读取工具：{@link LineNumberedReadFileTool} 相对框架内置 read_file 的四条增益——
 * 真带行号、单次输出有顶且能续读、二进制不再 Base64 灌进上下文、图片按模型能力附多模态块。
 * <p>后端用框架真实 {@code LocalFilesystem}，验的是「同名注册后行为不劣于内置实现」这条前提。</p>
 *
 * @author gogym
 */
class LineNumberedReadFileToolTest {

    @TempDir
    Path ws;

    private LineNumberedReadFileTool tool(boolean vision) {
        return new LineNumberedReadFileTool(new LocalFilesystem(ws), ws, () -> vision);
    }

    private static String text(ToolResultBlock r) {
        return r.getOutput().stream()
                .map(b -> b instanceof TextBlock t ? t.getText() : "")
                .collect(Collectors.joining());
    }

    @Test
    @DisplayName("文本按行号返回，尾部带总行数")
    void numbersLines() throws IOException {
        Files.writeString(ws.resolve("a.py"), "alpha\nbeta\ngamma\n", StandardCharsets.UTF_8);

        String out = text(tool(true).readFile(RuntimeContext.empty(), "a.py", null, null));

        assertTrue(out.contains("1\talpha"), () -> "应带行号，实际: " + out);
        assertTrue(out.contains("3\tgamma"), () -> "尾行换行不该多算一行，实际: " + out);
        assertFalse(out.contains("4\t"), () -> "行号不该超出真实行数，实际: " + out);
    }

    @Test
    @DisplayName("超限截断并给出续读 offset；接着读能拿到剩下的行")
    void truncatesWithResumeHint() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            sb.append("line").append(i).append('\n');
        }
        Files.writeString(ws.resolve("many.txt"), sb.toString(), StandardCharsets.UTF_8);
        LineNumberedReadFileTool tool = tool(true);

        String first = text(tool.readFile(RuntimeContext.empty(), "many.txt", 0, 10));
        assertTrue(first.contains("Continue with offset=10"), () -> "应给续读位置，实际: " + first);
        assertTrue(first.contains("of 50"), () -> "应报总行数，实际: " + first);
        assertFalse(first.contains("line11"), () -> "limit 应生效，实际: " + first);

        String second = text(tool.readFile(RuntimeContext.empty(), "many.txt", 10, 10));
        assertTrue(second.contains("11\tline11"), () -> "offset 是 0 基，实际: " + second);
    }

    @Test
    @DisplayName("缺 offset 时按默认行数上限截，而不是整份返回")
    void appliesDefaultLimit() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= LineNumberedReadFileTool.DEFAULT_LINE_LIMIT + 5; i++) {
            sb.append(i).append('\n');
        }
        Files.writeString(ws.resolve("big.txt"), sb.toString(), StandardCharsets.UTF_8);

        String out = text(tool(true).readFile(RuntimeContext.empty(), "big.txt", null, null));

        assertTrue(out.contains("Continue with offset=" + LineNumberedReadFileTool.DEFAULT_LINE_LIMIT),
                () -> "默认上限应生效，实际尾部: " + out.substring(out.length() - 120));
    }

    @Test
    @DisplayName("二进制只报大小与出路，Base64 不进上下文")
    void refusesBinaryDump() throws IOException {
        byte[] blob = new byte[4096];
        blob[0] = 'M';
        blob[100] = 0;
        Files.write(ws.resolve("thing.dat"), blob);

        String out = text(tool(true).readFile(RuntimeContext.empty(), "thing.dat", null, null));

        assertTrue(out.startsWith("Binary file"), () -> "应说明是二进制，实际: " + out);
        assertTrue(out.contains("4096 bytes"), () -> "应给出大小，实际: " + out);
        assertFalse(out.contains("AAAB"), () -> "不该出现 Base64 片段，实际: " + out);
    }

    @Test
    @DisplayName("表格不读全文：转为 inspect_file 指引并附工作表概览")
    void routesSpreadsheetToInspector() throws IOException {
        Files.writeString(ws.resolve("t.csv"), "name,amount\na,1\nb,2\n", StandardCharsets.UTF_8);

        String out = text(tool(true).readFile(RuntimeContext.empty(), "t.csv", null, null));

        assertTrue(out.contains("inspect_file"), () -> "应指向表格工具，实际: " + out);
        assertTrue(out.contains("name"), () -> "应带回表头概览，实际: " + out);
    }

    @Test
    @DisplayName("模型能看时图片附多模态块，不能看时只给文字说明")
    void attachesImageOnlyWhenModelCanSee() throws IOException {
        byte[] png = new byte[64];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';
        Files.write(ws.resolve("chart.png"), png);

        ToolResultBlock seen = tool(true).readFile(RuntimeContext.empty(), "chart.png", null, null);
        assertEquals(2, seen.getOutput().size(), "文本说明 + 一个图片块");
        ImageBlock image = (ImageBlock) seen.getOutput().get(1);
        assertEquals("image/png", ((Base64Source) image.getSource()).getMediaType());
        assertEquals(Base64.getEncoder().encodeToString(png),
                ((Base64Source) image.getSource()).getData());

        String blind = text(tool(false).readFile(RuntimeContext.empty(), "chart.png", null, null));
        assertTrue(blind.contains("cannot receive images"), () -> "应说明看不见，实际: " + blind);
    }

    @Test
    @DisplayName("不存在的文件回错误文本，不抛异常打断推理循环")
    void reportsMissingFile() {
        String out = text(tool(true).readFile(RuntimeContext.empty(), "nope.txt", null, null));

        assertTrue(out.startsWith("Error:"), () -> "实际: " + out);
    }

    @Test
    @DisplayName("空文件沿用框架的系统提醒；超长单行给出按 execute 处理的出路")
    void handlesEmptyAndLongSingleLine() throws IOException {
        Files.writeString(ws.resolve("empty.md"), "", StandardCharsets.UTF_8);
        assertTrue(text(tool(true).readFile(RuntimeContext.empty(), "empty.md", null, null))
                .contains("empty contents"));

        Files.writeString(ws.resolve("min.js"),
                "x".repeat(LineNumberedReadFileTool.MAX_OUTPUT_CHARS + 100), StandardCharsets.UTF_8);
        String out = text(tool(true).readFile(RuntimeContext.empty(), "min.js", null, null));
        assertTrue(out.contains("longer than"), () -> "超长单行应另给出路，实际尾部: "
                + out.substring(out.length() - 80));
    }
}
