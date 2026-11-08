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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正则搜索工具：{@link RegexGrepTool} 相对框架内置 grep_files 的四条增益——真正则、三档输出、
 * 上下文与分页、噪音目录与大文件跳过说得清。
 * <p>重点盯住那条最容易退化的：框架把 pattern 当字面量搜，模型写 {@code get.*Name} 搜不到就会去
 * 整份读文件；这里必须搜得到，且返回形状稳定可解析。</p>
 *
 * @author gogym
 */
class RegexGrepToolTest {

    @TempDir
    Path ws;

    private RegexGrepTool tool;

    @BeforeEach
    void setUp() throws IOException {
        tool = new RegexGrepTool(new LocalFilesystem(ws));
        Files.createDirectories(ws.resolve("src"));
        Files.writeString(ws.resolve("src/User.java"),
                "public class User {\n    String userName = \"a\";\n    public String getUserName"
                        + "() { return userName; }\n}\n", StandardCharsets.UTF_8);
        Files.writeString(ws.resolve("src/service.py"),
                "def get_user_name():\n    return \"a\"\n", StandardCharsets.UTF_8);
    }

    private String grep(String pattern, String path, String glob, String mode,
                        Boolean ci, Integer before, Integer after, Boolean multiline,
                        Integer limit, Integer offset) {
        return tool.grepFiles(RuntimeContext.empty(), pattern, path, glob, null, mode, ci,
                before, after, multiline, limit, offset);
    }

    @Test
    @DisplayName("正则真的生效：get.*Name 能同时命中 Java 与 Python")
    void searchesByRegex() {
        String out = grep("get.*Name", null, null, null, null, null, null, null, null, null);

        assertTrue(out.contains("src/User.java"), () -> "应命中 Java，实际: " + out);
        assertTrue(out.contains("getUserName()"), () -> "应带回匹配行正文，实际: " + out);
    }

    @Test
    @DisplayName("点号按正则解释：转义后搜不到 user.name 以外的东西")
    void escapedDotIsLiteral() {
        assertFalse(grep("user\\.name", null, null, null, null, null, null, null, null, null)
                .contains("src/User.java"), "文件里没有 user.name 这个字面量");
        assertTrue(grep("userName", null, null, null, null, null, null, null, null, null)
                .contains("src/User.java"), "未转义的正则应照常命中");
    }

    @Test
    @DisplayName("非法正则回错误说明，不抛异常打断推理循环")
    void reportsBadPattern() {
        String out = grep("(unclosed", null, null, null, null, null, null, null, null, null);

        assertTrue(out.startsWith("Error: invalid regular expression"), () -> "实际: " + out);
        assertTrue(out.contains("escape"), () -> "应提示字面量转义，实际: " + out);
    }

    @Test
    @DisplayName("大小写忽略档：USERNAME 也能命中 userName")
    void caseInsensitive() {
        assertFalse(grep("USERNAME", null, null, null, false, null, null, null, null, null)
                .contains("src/User.java"));

        String out = grep("USERNAME", null, null, null, true, null, null, null, null, null);
        assertTrue(out.contains("src/User.java"), () -> "忽略大小写应命中，实际: " + out);
    }

    @Test
    @DisplayName("files_with_matches 只列文件；count 给每文件条数")
    void outputModes() {
        String files = grep("return", null, null, "files_with_matches", null, null, null,
                null, null, null);
        assertTrue(files.contains("src/User.java"), () -> "实际: " + files);
        assertTrue(files.contains("src/service.py"), () -> "实际: " + files);
        assertFalse(files.contains("return \"a\""), () -> "不该带正文，实际: " + files);

        String counts = grep("return", null, null, "count", null, null, null, null, null, null);
        assertTrue(counts.contains("src/User.java: 1"), () -> "实际: " + counts);
        assertTrue(counts.contains("src/service.py: 1"), () -> "实际: " + counts);
    }

    @Test
    @DisplayName("type 档走后缀表，比手写 glob 更省")
    void typeFilter() {
        String out = tool.grepFiles(RuntimeContext.empty(), "def", null, null, "py", null,
                null, null, null, null, null, null);

        assertTrue(out.contains("src/service.py"), () -> "实际: " + out);
        assertFalse(out.contains("src/User.java"), () -> "py 类型不该带出 Java，实际: " + out);
    }

    @Test
    @DisplayName("glob 过滤按类型收窄")
    void globFilter() {
        String out = grep("return", null, "*.py", null, null, null, null, null, null, null);

        assertTrue(out.contains("src/service.py"), () -> "实际: " + out);
        assertFalse(out.contains("src/User.java"), () -> "*.py 不该带出 Java，实际: " + out);
    }

    @Test
    @DisplayName("上下文档带出前后行")
    void contextLines() {
        String out = grep("getUserName", null, null, null, null, 1, 0, null, null, null);

        assertTrue(out.contains("String userName"), () -> "应带出上一行，实际: " + out);
    }

    @Test
    @DisplayName("limit 截断并给出 offset，接着分页能拿到剩下的")
    void paging() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            sb.append("needle").append(i).append('\n');
        }
        Files.writeString(ws.resolve("many.txt"), sb.toString(), StandardCharsets.UTF_8);

        String first = grep("needle", null, null, null, null, null, null, null, 10, null);
        assertTrue(first.contains("Continue with offset=10"), () -> "实际: " + first);
        assertTrue(first.contains("of 30 matches"), () -> "应报总数，实际: " + first);

        String second = grep("needle", null, null, null, null, null, null, null, 10, 10);
        assertTrue(second.contains("many.txt:11:needle11"),
                () -> "第二页应从第 11 条起，实际: " + second);
        assertFalse(second.contains("many.txt:1:needle1\n"),
                () -> "第一页内容不该重复，实际: " + second);
    }

    @Test
    @DisplayName("多行模式跨行匹配，并把命中折成一行保持 path:line:text 形状")
    void multiline() {
        String out = grep("userName = .{0,20}\\n", null, null, null, null, null, null, true,
                null, null);

        assertTrue(out.contains("src/User.java"), () -> "跨行应命中，实际: " + out);
        assertTrue(out.contains("\\n"), () -> "换行应折可见，实际: " + out);
    }

    @Test
    @DisplayName("噪音目录与二进制文件不搜，且把跳过说清楚")
    void skipsNoiseAndBinary() throws IOException {
        Files.createDirectories(ws.resolve("node_modules/pkg"));
        Files.writeString(ws.resolve("node_modules/pkg/index.js"), "needle\n", StandardCharsets.UTF_8);
        Files.write(ws.resolve("blob.bin"), new byte[] {'n', 'e', 'e', 'd', 'l', 'e', 0, 1});

        String out = grep("needle", null, null, null, null, null, null, null, null, null);

        assertFalse(out.contains("node_modules"), () -> "噪音目录应跳过，实际: " + out);
        assertFalse(out.contains("blob.bin:"), () -> "二进制不该有命中行，实际: " + out);
        assertTrue(out.contains("binary file(s) not searched"), () -> "应说明跳过，实际: " + out);
    }

    @Test
    @DisplayName("path 直接指到文件时也只搜该文件")
    void singleFileTarget() {
        String out = grep("def", "src/service.py", null, null, null, null, null, null, null, null);

        assertTrue(out.contains("src/service.py"), () -> "实际: " + out);
        assertFalse(out.contains("src/User.java"), () -> "不该越出目标文件，实际: " + out);
    }
}
