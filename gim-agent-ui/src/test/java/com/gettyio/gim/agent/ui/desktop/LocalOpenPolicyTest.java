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

package com.gettyio.gim.agent.ui.desktop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link LocalOpenPolicy} 单测：授权根归属、可执行扩展名门槛与外链协议白名单。
 * <p>只测判定用的纯函数（{@code allowedPath}/{@code openableFile}/{@code openableDirectory}/
 * {@code isBrowsableLink}）——{@code openFile}/{@code browse} 那层会真的拉起资源管理器或浏览器，
 * 单元测试里不该有副作用。</p>
 *
 * @author gogym
 */
class LocalOpenPolicyTest {

    @TempDir
    Path dir;

    @AfterEach
    void unconfigure() {
        LocalOpenPolicy.configure(null);
    }

    /** 装配授权根快照（策略要的是实时供应商，测试里用固定快照即可）。 */
    private static void allow(Path... roots) {
        List<Path> snapshot = List.of(roots);
        LocalOpenPolicy.configure(() -> snapshot);
    }

    private Path workspace() throws IOException {
        return Files.createDirectories(dir.resolve("workspace"));
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

    @Test
    @DisplayName("未装配授权根时一律拒绝：缺配置不能默认放开")
    void unconfiguredRefusesEverything() throws IOException {
        Path file = Files.createFile(workspace().resolve("a.txt"));
        LocalOpenPolicy.configure(null);
        assertNull(LocalOpenPolicy.allowedPath(file));
        assertNull(LocalOpenPolicy.openableFile(file));
        assertNull(LocalOpenPolicy.openableDirectory(file.getParent()));
    }

    @Test
    @DisplayName("根内文件可打开，返回规范形；根外文件拒绝")
    void insideRootOpensOutsideRefused() throws IOException {
        Path ws = workspace();
        Path other = Files.createDirectories(dir.resolve("private"));
        Path inside = Files.createFile(ws.resolve("report.xlsx"));
        Path outside = Files.createFile(other.resolve("id_rsa"));
        allow(ws);

        assertEquals(inside.toRealPath(), LocalOpenPolicy.openableFile(inside));
        assertNull(LocalOpenPolicy.openableFile(outside));
        assertNull(LocalOpenPolicy.openableFile(outside.toRealPath()));
    }

    @Test
    @DisplayName("多个授权根同时生效（工作区 + allowed + 智能体项目目录）")
    void severalRootsAllCount() throws IOException {
        Path ws = workspace();
        Path allowed = Files.createDirectories(dir.resolve("allowed"));
        Path project = Files.createDirectories(dir.resolve("project"));
        allow(ws, allowed, project);

        assertNotNull(LocalOpenPolicy.allowedPath(Files.createFile(project.resolve("Main.java"))));
        assertNotNull(LocalOpenPolicy.allowedPath(Files.createFile(allowed.resolve("tool.exe"))));
        assertNull(LocalOpenPolicy.allowedPath(Files.createFile(dir.resolve("elsewhere.txt"))));
    }

    @Test
    @DisplayName("打开即执行那类扩展名不交系统：文本仍可读")
    void executableLikeExtensionsRefused() throws IOException {
        Path ws = workspace();
        allow(ws);

        for (String name : List.of("setup.bat", "run.CMD", "task.ps1", "macro.vbs", "app.exe",
                "tool.jar", "notes.py", "link.lnk", "mount.iso", "settings.reg")) {
            assertTrue(LocalOpenPolicy.isExecutableLike(name), name + " 应属可执行类");
            assertNull(LocalOpenPolicy.openableFile(Files.createFile(ws.resolve(name))), name + " 不该交系统打开");
        }
        for (String name : List.of("x.html", "x.txt", "x.md", "x.csv", "archive.tar.gz", "no-extension")) {
            assertFalse(LocalOpenPolicy.isExecutableLike(name), name + " 不该被当成可执行");
        }
    }

    @Test
    @DisplayName("目录不能当文件打开、文件不能当目录定位、不存在的都不行")
    void typeAndExistenceChecked() throws IOException {
        Path ws = workspace();
        Path file = Files.createFile(ws.resolve("a.txt"));
        allow(ws);

        assertNull(LocalOpenPolicy.openableFile(ws), "目录不是可打开文件");
        assertNull(LocalOpenPolicy.openableDirectory(file), "文件不是目录");
        assertNull(LocalOpenPolicy.openableFile(ws.resolve("gone.txt")), "不存在的文件不打开");
        assertEquals(ws.toRealPath(), LocalOpenPolicy.openableDirectory(ws));
    }

    @Test
    @DisplayName("根内符号链接指向根外：按规范形拒绝")
    void symlinkOutsideRootRefused() throws IOException {
        Path ws = workspace();
        Path privateDir = Files.createDirectories(dir.resolve("private"));
        Files.writeString(privateDir.resolve("secret.txt"), "x");
        assumeTrue(symlinksSupported(ws), "当前环境不能创建符号链接，跳过逃逸用例");
        Files.createSymbolicLink(ws.resolve("link"), privateDir);
        allow(ws);

        Path viaLink = ws.resolve("link/secret.txt");
        assertTrue(viaLink.toRealPath().startsWith(privateDir.toRealPath()), "确实在根外");
        assertNull(LocalOpenPolicy.allowedPath(viaLink), "规范形判定看得见链接指向根外");
        assertNull(LocalOpenPolicy.openableFile(viaLink));
        assertNull(LocalOpenPolicy.openableDirectory(ws.resolve("link")));
    }

    @Test
    @DisplayName("外链只放过 http(s) 且有主机名")
    void onlyHttpLinksAreBrowsable() {
        LocalOpenPolicy.configure(null);
        assertTrue(LocalOpenPolicy.isBrowsableLink("https://example.com/a?b=1"));
        assertTrue(LocalOpenPolicy.isBrowsableLink(" http://example.com "));
        for (String bad : List.of("file:///C:/Users/me/Desktop/x.txt", "javascript:alert(1)",
                "data:text/html,<script>alert(1)</script>", "mailto:someone@example.com",
                "search://foo", "cmd:///C:/Windows/system32/cmd.exe", "a/relative/path.md",
                "C:\\Windows\\notepad.exe", "", "   ", "http://", "not a uri at all")) {
            assertFalse(LocalOpenPolicy.isBrowsableLink(bad), "不该放开: " + bad);
        }
        assertFalse(LocalOpenPolicy.isBrowsableLink(null));
    }
}
