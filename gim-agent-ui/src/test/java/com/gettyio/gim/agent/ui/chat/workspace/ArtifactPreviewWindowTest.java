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

package com.gettyio.gim.agent.ui.chat.workspace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ArtifactPreviewWindow} 的静态判定与文档组装测试：只覆盖纯函数，不触 FX 不起窗口。
 *
 * @author gogym
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

    /** 白名单即唯一事实源：语言与扩展名共用一份，前端预览钮也从这里取。 */
    @Test
    void previewLangsIsTheSingleSource() {
        assertEquals(List.of("html", "htm", "xhtml", "svg"), ArtifactPreviewWindow.PREVIEW_LANGS);
        for (String lang : ArtifactPreviewWindow.PREVIEW_LANGS) {
            assertTrue(ArtifactPreviewWindow.isPreviewable(lang), lang);
            assertTrue(ArtifactPreviewWindow.isPreviewableFile("out." + lang), lang);
        }
    }

    @Test
    void filePredicateIgnoresCasePathAndDotFiles() {
        assertTrue(ArtifactPreviewWindow.isPreviewableFile("D:/report/月度汇总.HTML"));
        assertTrue(ArtifactPreviewWindow.isPreviewableFile("D:/report/图表.svg"));
        assertFalse(ArtifactPreviewWindow.isPreviewableFile("报表.xlsx"));
        assertFalse(ArtifactPreviewWindow.isPreviewableFile("notes.md"));
        // 点开头是文件名而非扩展名；点收尾没有扩展名
        assertFalse(ArtifactPreviewWindow.isPreviewableFile(".html"));
        assertFalse(ArtifactPreviewWindow.isPreviewableFile("html."));
        assertFalse(ArtifactPreviewWindow.isPreviewableFile("html"));
        assertFalse(ArtifactPreviewWindow.isPreviewableFile(null));
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
