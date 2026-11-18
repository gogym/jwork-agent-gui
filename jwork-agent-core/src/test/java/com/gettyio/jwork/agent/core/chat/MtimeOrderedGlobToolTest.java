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

package com.gettyio.jwork.agent.core.chat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件通配工具：{@link MtimeOrderedGlobTool} 只验一件事——最近改的排在前面，
 * 因为框架按遍历序截断，长列表里刚生成的产物会被截到看不见。
 *
 * @author gogym
 */
class MtimeOrderedGlobToolTest {

    @TempDir
    Path ws;

    private MtimeOrderedGlobTool tool() {
        return new MtimeOrderedGlobTool(new LocalFilesystem(ws));
    }

    private void touch(String name, long epochSeconds) throws IOException {
        Path file = ws.resolve(name);
        Files.writeString(file, "x", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(epochSeconds)));
    }

    @Test
    @DisplayName("按修改时间新→旧排，且每行带上时间便于确认是不是刚那份")
    void ordersNewestFirst() throws IOException {
        touch("old.xlsx", 1_600_000_000L);
        touch("middle.xlsx", 1_700_000_000L);
        touch("fresh.xlsx", 1_800_000_000L);

        String out = tool().globFiles(RuntimeContext.empty(), "*.xlsx", null, null);

        int fresh = out.indexOf("fresh.xlsx");
        int middle = out.indexOf("middle.xlsx");
        int old = out.indexOf("old.xlsx");
        assertTrue(fresh >= 0 && middle > fresh && old > middle,
                () -> "应按新→旧排序，实际: " + out);
        assertTrue(out.contains("modified"), () -> "应带修改时间，实际: " + out);
    }

    @Test
    @DisplayName("limit 生效：截断的是最老的一批，并说明总数")
    void truncatesOldest() throws IOException {
        for (int i = 1; i <= 5; i++) {
            touch("f" + i + ".md", 1_600_000_000L + i);
        }

        String out = tool().globFiles(RuntimeContext.empty(), "*.md", null, 2);

        assertTrue(out.contains("f5.md"), () -> "最新的必须留下，实际: " + out);
        assertTrue(out.contains("f4.md"), () -> "实际: " + out);
        assertTrue(!out.contains("f3.md") && !out.contains("f1.md"),
                () -> "limit=2 该丢掉最老的，实际: " + out);
        assertTrue(out.contains("of 5 files, newest first"), () -> "实际: " + out);
    }

    @Test
    @DisplayName("非法 limit 回落缺省；空匹配与空 pattern 各回各的话")
    void guardsInputs() throws IOException {
        assertEquals("No matching files found",
                tool().globFiles(RuntimeContext.empty(), "*.zip", null, null));
        assertTrue(tool().globFiles(RuntimeContext.empty(), "  ", null, null)
                .startsWith("Error:"));
        touch("a.log", 1_700_000_000L);
        assertTrue(tool().globFiles(RuntimeContext.empty(), "*.log", null, -3)
                .contains("a.log"));
    }
}
