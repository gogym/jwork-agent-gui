package com.gettyio.gim.agent.ui.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TtsService#markdownToPlainText} 的清理规则测试：只覆盖纯函数，不触网不触 FX。
 */
class TtsServiceTest {

    @Test
    void blankInputYieldsEmpty() {
        assertEquals("", TtsService.markdownToPlainText(null));
        assertEquals("", TtsService.markdownToPlainText(""));
        assertEquals("", TtsService.markdownToPlainText("   \n  \n"));
    }

    @Test
    void plainTextPassesThrough() {
        assertEquals("你好，世界。", TtsService.markdownToPlainText("你好，世界。"));
    }

    @Test
    void fencedCodeBlocksAreDropped() {
        String md = "前文\n```java\nint a = 1;\n```\n后文";
        String plain = TtsService.markdownToPlainText(md);
        assertTrue(plain.contains("前文"));
        assertTrue(plain.contains("后文"));
        assertFalse(plain.contains("int a = 1;"));
        assertFalse(plain.contains("```"));
    }

    @Test
    void inlineCodeKeepsContent() {
        assertEquals("请运行 npm install 安装。", TtsService.markdownToPlainText("请运行 `npm install` 安装。"));
    }

    @Test
    void linksKeepTextImagesDropped() {
        assertEquals("查看文档 了解详情。", TtsService.markdownToPlainText("查看[文档](https://example.com) 了解详情。"));
        assertEquals("示意图如下", TtsService.markdownToPlainText("示意图![alt](https://img.png)如下"));
    }

    @Test
    void headingsQuotesListsAndEmphasisAreCleaned() {
        assertEquals("标题\n引用行\n列表项\n数字项", TtsService.markdownToPlainText(
                "## 标题\n> 引用行\n- 列表项\n1. 数字项"));
        assertEquals("加粗 斜体", TtsService.markdownToPlainText("**加粗** *斜体*"));
        assertEquals("下划线强调", TtsService.markdownToPlainText("__下划线强调__"));
    }

    @Test
    void horizontalRuleAndTablePipesAreRemoved() {
        String plain = TtsService.markdownToPlainText("---\n| 列A | 列B |");
        assertFalse(plain.contains("---"));
        assertFalse(plain.contains("|"));
        assertTrue(plain.contains("列A"));
    }

    @Test
    void htmlTagsAreStripped() {
        assertEquals("换行标记", TtsService.markdownToPlainText("换行<br/>标记"));
    }
}
