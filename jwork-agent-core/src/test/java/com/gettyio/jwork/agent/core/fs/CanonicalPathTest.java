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

package com.gettyio.jwork.agent.core.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link CanonicalPath} 单测：已存在路径、尚不存在的叶子、{@code ..} 折叠、符号链接逃逸与
 * 多根归属判定。
 * <p>符号链接用例先探测本机能否建链：Windows 上非开发者模式/非管理员会抛异常，
 * 此时用 {@code assumeTrue} 跳过而不是判失败——环境限制不该让测试变红。</p>
 *
 * @author gogym
 */
class CanonicalPathTest {

    /** 本机此刻能否创建符号链接（探测一次，失败即跳过相关用例）。 */
    private static boolean symlinksSupported(Path where) throws IOException {
        Path target = where.resolve("probe-target");
        Path link = where.resolve("probe-link");
        try {
            Files.createFile(target);
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(target);
        }
    }

    @Test
    @DisplayName("已存在路径的规范形就是 toRealPath")
    void existingPathUsesRealPath(@TempDir Path dir) throws IOException {
        assertEquals(dir.toRealPath(), CanonicalPath.real(dir));
    }

    @Test
    @DisplayName("尚未创建的叶子：最近已存在祖先取规范形，缺失段原样补回")
    void missingLeafRebuildsFromExistingAncestor(@TempDir Path dir) throws IOException {
        Path expected = dir.toRealPath().resolve("out").resolve("new.txt");
        assertEquals(expected, CanonicalPath.real(dir.resolve("out/new.txt")));
    }

    @Test
    @DisplayName("字符串里的 .. 与 . 被折叠掉，不会留下越界的样子")
    void dotSegmentsCollapse(@TempDir Path dir) throws IOException {
        Path base = Files.createDirectory(dir.resolve("sub"));
        Path dotted = base.resolve("../notes.txt");
        assertEquals(dir.toRealPath().resolve("notes.txt"), CanonicalPath.real(dotted));
    }

    @Test
    @DisplayName("入参为 null 时返回 null，不抛")
    void nullIsTolerated() {
        assertNull(CanonicalPath.real(null));
        assertNull(CanonicalPath.withinAny(null, List.of(Path.of("."))));
        assertFalse(CanonicalPath.within(Path.of("."), null));
    }

    @Test
    @DisplayName("父目录逃逸判为越界，根内路径判为在内")
    void parentEscapeIsRejected(@TempDir Path dir) throws IOException {
        Path workspace = Files.createDirectory(dir.resolve("workspace"));
        assertTrue(CanonicalPath.within(workspace.resolve("a/b.txt"), workspace));
        assertTrue(CanonicalPath.within(workspace, workspace), "根本身算在内");
        assertFalse(CanonicalPath.within(workspace.resolve("../outside.txt"), workspace));
    }

    @Test
    @DisplayName("符号链接指向根外：归一化前缀会说「在内」，规范形说「不在」")
    void symlinkEscapeIsRejected(@TempDir Path dir) throws IOException {
        Path workspace = Files.createDirectory(dir.resolve("workspace"));
        Path outside = Files.createDirectory(dir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "x");
        assumeTrue(symlinksSupported(workspace), "当前环境不能创建符号链接，跳过逃逸用例");
        Files.createSymbolicLink(workspace.resolve("link"), outside);

        Path viaLink = workspace.resolve("link/secret.txt");
        // 旧口径（toAbsolutePath().normalize() + startsWith）正是在这一行上放行的
        assertTrue(viaLink.toAbsolutePath().normalize().startsWith(workspace.toAbsolutePath().normalize()));
        assertFalse(CanonicalPath.within(viaLink, workspace), "跟着链接走就看到根外了");
        assertTrue(CanonicalPath.within(viaLink, outside), "对真实目标目录则应在内");
    }

    @Test
    @DisplayName("withinAny 命中哪个根就返回规范形，全不命中返回 null")
    void withinAnyScansEveryRoot(@TempDir Path dir) throws IOException {
        Path workspace = Files.createDirectory(dir.resolve("workspace"));
        Path allowed = Files.createDirectory(dir.resolve("allowed"));
        Path elsewhere = Files.createDirectory(dir.resolve("elsewhere"));

        assertEquals(allowed.toRealPath().resolve("tool.exe"),
                CanonicalPath.withinAny(allowed.resolve("tool.exe"), List.of(workspace, allowed)));
        assertNull(CanonicalPath.withinAny(elsewhere.resolve("a.txt"), List.of(workspace, allowed)));
        assertNull(CanonicalPath.withinAny(workspace.resolve("a.txt"), List.of()));
    }

    @Test
    @DisplayName("根列表里的相对写法与根内链接都能对上")
    void relativeRootsAndLinkedRootsMatch(@TempDir Path dir) throws IOException {
        Path workspace = Files.createDirectory(dir.resolve("workspace"));
        assumeTrue(symlinksSupported(workspace), "当前环境不能创建符号链接，跳过链接根用例");
        Files.createSymbolicLink(dir.resolve("ws-link"), workspace);
        Path canonicalDir = workspace.toRealPath();

        assertEquals(canonicalDir.resolve("a.txt"),
                CanonicalPath.withinAny(canonicalDir.resolve("a.txt"), List.of(dir.resolve("ws-link"))));
    }
}
