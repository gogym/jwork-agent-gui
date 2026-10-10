package com.gettyio.gim.agent.ui.chat;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkspacePanel} 的纯函数测试：扩展名分派、路径安全、大小/二进制判定。
 * <p>只覆盖不触 FX 的静态方法，不起面板、不读真实文件。</p>
 */
class WorkspacePanelTest {

    @Test
    void classifyByExtension() {
        assertEquals(WorkspacePanel.RenderKind.MARKDOWN, WorkspacePanel.classify("README.md"));
        assertEquals(WorkspacePanel.RenderKind.MARKDOWN, WorkspacePanel.classify("NOTE.MARKDOWN"));
        assertEquals(WorkspacePanel.RenderKind.HTML, WorkspacePanel.classify("page.html"));
        assertEquals(WorkspacePanel.RenderKind.HTML, WorkspacePanel.classify("icon.SVG"));
        assertEquals(WorkspacePanel.RenderKind.IMAGE, WorkspacePanel.classify("shot.png"));
        assertEquals(WorkspacePanel.RenderKind.IMAGE, WorkspacePanel.classify("photo.JPEG"));
        assertEquals(WorkspacePanel.RenderKind.DOC, WorkspacePanel.classify("report.pdf"));
        assertEquals(WorkspacePanel.RenderKind.DOC, WorkspacePanel.classify("data.xlsx"));
        assertEquals(WorkspacePanel.RenderKind.TEXT, WorkspacePanel.classify("main.py"));
        assertEquals(WorkspacePanel.RenderKind.TEXT, WorkspacePanel.classify("noext"));
        assertEquals(WorkspacePanel.RenderKind.TEXT, WorkspacePanel.classify(null));
    }

    @Test
    void withinAcceptsDescendantsAndRejectsEscapes() {
        Path root = Paths.get(System.getProperty("user.dir")).resolve("ws").toAbsolutePath().normalize();
        assertTrue(WorkspacePanel.isWithin(root, root.resolve("a/b.txt")));
        assertTrue(WorkspacePanel.isWithin(root, root));
        // ../ 归一化后越出根：拒绝
        assertFalse(WorkspacePanel.isWithin(root, root.resolve("../secret.txt").normalize()));
        assertFalse(WorkspacePanel.isWithin(root, Paths.get("C:/Windows/win.ini")));
    }

    @Test
    void sizeCapIsStrictlyGreater() {
        assertFalse(WorkspacePanel.tooLarge(100, 100));
        assertTrue(WorkspacePanel.tooLarge(101, 100));
    }

    @Test
    void binarySniffDetectsNulByte() {
        assertFalse(WorkspacePanel.looksBinary("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(WorkspacePanel.looksBinary(new byte[]{'a', 'b', 0, 'c'}));
        assertFalse(WorkspacePanel.looksBinary(null));
    }

    @Test
    void sessionDirResolvesInsideRootOnly() {
        Path root = Paths.get(System.getProperty("user.dir")).resolve("ws").toAbsolutePath().normalize();
        assertEquals(root.resolve("615a4b0a"), WorkspacePanel.sessionDirOf(root, "615a4b0a"));
        // 空会话 ID 不跟随
        assertNull(WorkspacePanel.sessionDirOf(root, null));
        assertNull(WorkspacePanel.sessionDirOf(root, "  "));
        // 越界会话 ID（../ 或多段上跳）归一化后越出根：拒绝
        assertNull(WorkspacePanel.sessionDirOf(root, "../escape"));
        assertNull(WorkspacePanel.sessionDirOf(root, "a/../../escape"));
        // 绝对路径形态的 ID 同样拒绝（resolve 绝对路径直接替换前缀）
        assertNull(WorkspacePanel.sessionDirOf(root, root.resolveSibling("outside").toString()));
    }

    @Test
    void fileNodeDisplayNamePrefersLabelOverride() {
        Path p = Paths.get("615a4b0a-session-dir");
        assertEquals("615a4b0a-session-dir", new WorkspacePanel.FileNode(p, true).displayName());
        assertEquals("当前会话", new WorkspacePanel.FileNode(p, true, "当前会话").displayName());
    }
}
