package com.gettyio.gim.agent.ui.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ArtifactPreviewWindow} 的静态判定与文档组装测试：只覆盖纯函数，不触 FX 不起窗口。
 */
class ArtifactPreviewWindowTest {

    @Test
    void htmlFamilyAndSvgArePreviewable() {
        assertTrue(ArtifactPreviewWindow.isPreviewable("html"));
        assertTrue(ArtifactPreviewWindow.isPreviewable("HTML"));
        assertTrue(ArtifactPreviewWindow.isPreviewable("htm"));
        assertTrue(ArtifactPreviewWindow.isPreviewable("xhtml"));
        assertTrue(ArtifactPreviewWindow.isPreviewable("svg"));
        assertTrue(ArtifactPreviewWindow.isPreviewable("  svg "));
    }

    @Test
    void otherLanguagesAreNotPreviewable() {
        assertFalse(ArtifactPreviewWindow.isPreviewable(null));
        assertFalse(ArtifactPreviewWindow.isPreviewable(""));
        assertFalse(ArtifactPreviewWindow.isPreviewable("   "));
        assertFalse(ArtifactPreviewWindow.isPreviewable("java"));
        assertFalse(ArtifactPreviewWindow.isPreviewable("python"));
        assertFalse(ArtifactPreviewWindow.isPreviewable("xml"));
    }

    @Test
    void htmlPassesThroughUnchanged() {
        String html = "<div>你好</div>";
        assertSame(html, ArtifactPreviewWindow.buildHtml(html, "html"));
    }

    @Test
    void svgWrappedInCenteredSkeleton() {
        String svg = "<svg><circle r=\"10\"/></svg>";
        String doc = ArtifactPreviewWindow.buildHtml(svg, "svg");
        assertTrue(doc.contains("<!DOCTYPE html>"));
        assertTrue(doc.contains(svg));
        assertTrue(doc.toLowerCase().contains("display:flex"));
    }
}
