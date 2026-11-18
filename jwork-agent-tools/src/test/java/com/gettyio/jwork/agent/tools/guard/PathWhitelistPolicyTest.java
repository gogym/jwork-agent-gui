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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link PathWhitelistPolicy} 单测：只管「越界要标注」——授权根内沉默、根外 ASK 并展示规范形路径、
 * 动态根实时生效、符号链接逃逸按真实位置判。
 *
 * @author gogym
 */
class PathWhitelistPolicyTest {

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static GuardContext write(String tool, String path) {
        String json = "[{\"name\":\"" + tool + "\",\"input\":{\"path\":" + jsonString(path) + "}}]";
        return new GuardContext("s1", tool, json);
    }

    /** 用可变快照模拟「设置页新增目录」实时生效。 */
    private static Supplier<Collection<Path>> liveRoots(List<Path> mutable) {
        return () -> new ArrayList<>(mutable);
    }

    @Test
    @DisplayName("非文件类工具不表态")
    void ignoresNonFileTool() {
        PathWhitelistPolicy policy = new PathWhitelistPolicy(List.of(Path.of(".")));
        assertNull(policy.inspect(new GuardContext("s1", "execute",
                "[{\"name\":\"execute\",\"input\":{\"command\":\"ls\"}}]")));
    }

    @Test
    @DisplayName("授权根内沉默（交链尾默认询问），根外 ASK 并给出真实路径")
    void outsideRootIsFlagged(@TempDir Path dir) throws IOException {
        Path ws = Files.createDirectories(dir.resolve("workspace"));
        Path outside = Files.createDirectories(dir.resolve("private"));
        PathWhitelistPolicy policy = new PathWhitelistPolicy(List.of(ws));

        assertNull(policy.inspect(write("write_file", Files.createFile(ws.resolve("a.txt")).toString())));

        GuardResult result = policy.inspect(write("edit_file", Files.createFile(outside.resolve("id_rsa"))
                .toString()));
        assertNotNull(result);
        assertEquals(GuardDecision.ASK, result.decision());
        assertTrue(result.reason().contains("id_rsa"), "理由要让用户看到真正写到了哪：" + result.reason());
    }

    @Test
    @DisplayName("路径不存在（新建文件）也按规范形判归属，不因解析失败而误判在内")
    void notYetCreatedLeafIsJudgedByCanonicalForm(@TempDir Path dir) throws IOException {
        Path ws = Files.createDirectories(dir.resolve("workspace"));
        PathWhitelistPolicy policy = new PathWhitelistPolicy(List.of(ws));

        assertNull(policy.inspect(write("write_file", ws.resolve("new/deep/x.md").toString())),
                "根内的新建路径不该被标成越界");

        Path outside = dir.resolve("elsewhere/x.md");
        GuardResult result = policy.inspect(write("write_file", outside.toString()));
        assertNotNull(result, "尚不存在的越界路径同样要标注");
        assertTrue(result.reason().contains("elsewhere"), result.reason());
    }

    @Test
    @DisplayName("设置页新增的目录实时生效：根列表换了不用再重启")
    void extraRootsAreReadLive(@TempDir Path dir) throws IOException {
        Path ws = Files.createDirectories(dir.resolve("workspace"));
        List<Path> extra = new ArrayList<>();
        PathWhitelistPolicy policy = new PathWhitelistPolicy(List.of(ws), liveRoots(extra));
        Path project = Files.createDirectories(dir.resolve("project"));

        assertNotNull(policy.inspect(write("write_file", project.resolve("a.txt").toString())));
        extra.add(project);
        assertNull(policy.inspect(write("write_file", project.resolve("a.txt").toString())),
                "加入授权根后同一路径应不再标注");
    }

    @Test
    @DisplayName("根内符号链接指向根外：按链接的真实位置标注越界")
    void symlinkEscapeIsFlagged(@TempDir Path dir) throws IOException {
        Path ws = Files.createDirectories(dir.resolve("workspace"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("secrets.env"), "A=1");
        assumeTrue(symlinksSupported(ws), "当前环境不能创建符号链接，跳过逃逸用例");
        Files.createSymbolicLink(ws.resolve("link"), outside);

        PathWhitelistPolicy policy = new PathWhitelistPolicy(List.of(ws));
        GuardResult result = policy.inspect(write("write_file", ws.resolve("link/secrets.env").toString()));
        assertNotNull(result, "字符串前缀比较会漏掉这一笔");
        assertTrue(result.reason().contains("outside"), "展示的是真实落点：" + result.reason());
    }

    @Test
    @DisplayName("空路径字段跳过，优先级为 20")
    void blankPathSkippedAndOrder(@TempDir Path dir) {
        PathWhitelistPolicy policy = new PathWhitelistPolicy(List.of(dir));
        assertNull(policy.inspect(write("write_file", "  ")));
        assertEquals(20, policy.order());
    }

    private static boolean symlinksSupported(Path where) {
        Path target = where.resolve("probe-target");
        Path link = where.resolve("probe-link");
        try {
            Files.createFile(target);
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(link);
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // 探测清理失败不影响判定结果
            }
        }
    }
}
