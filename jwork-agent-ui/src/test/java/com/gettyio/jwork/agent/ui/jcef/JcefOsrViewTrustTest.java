package com.gettyio.jwork.agent.ui.jcef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页面装载/桥调用可信性判定的纯函数单测：这三个方法是聊天区「内容不得把顶层框架换成别的文档、
 * 别的文档不得调用 Java 桥」闸门的判据，不依赖 JCEF 运行时，可在无桌面的 CI 上跑。
 */
class JcefOsrViewTrustTest {

    private static final String SHELL =
            "file:///C:/Users/dev/AppData/Local/Temp/jwork/cef-web/chat.html";

    @Test
    @DisplayName("同一文档：忽略 # 片段，页内锚点跳转不算异源导航")
    void anchorNavigationIsTheSameDocument() {
        assertTrue(JcefOsrView.sameDocument(SHELL, SHELL + "#top"));
        assertTrue(JcefOsrView.sameDocument(SHELL + "#a", SHELL + "#b"));
        assertEquals(SHELL, JcefOsrView.documentBase(SHELL + "#frag"));
    }

    @Test
    @DisplayName("百分号编码差异由双方各自解码后再比对")
    void encodingDifferencesNormalize() {
        assertTrue(JcefOsrView.sameDocument(
                "file:///C:/Users/dev/My%20Docs/page.html",
                "file:///C:/Users/dev/My Docs/page.html"));
        assertTrue(JcefOsrView.sameDocument(SHELL, SHELL.toUpperCase()));
    }

    @Test
    @DisplayName("换目标即不同文档：远端、data、别的本地文件一律不被认成骨架页")
    void differentTargetsAreDifferentDocuments() {
        assertFalse(JcefOsrView.sameDocument(SHELL, "https://evil.example/chat.html"));
        assertFalse(JcefOsrView.sameDocument(SHELL, "data:text/html;charset=utf-8,%3Cscript%3E"));
        assertFalse(JcefOsrView.sameDocument(SHELL,
                "file:///C:/Users/dev/AppData/Local/Temp/jwork/cef-web/composer.html"));
        assertFalse(JcefOsrView.sameDocument(SHELL, null));
    }

    @Test
    @DisplayName("非法百分号序列不抛异常，按原样参与比对")
    void malformedPercentEscapeFallsBackToRaw() {
        assertEquals("file:///C:/a%zz/b.html", JcefOsrView.documentBase("file:///C:/a%zz/b.html"));
        assertTrue(JcefOsrView.sameDocument("file:///C:/a%zz/b.html", "file:///C:/a%zz/b.html#x"));
    }

    @Test
    @DisplayName("方案提取：小写、不含冒号，无方案返回空串")
    void schemeExtraction() {
        assertEquals("data", JcefOsrView.schemeOf("data:text/html,hi"));
        assertEquals("file", JcefOsrView.schemeOf("FILE:///C:/x.html"));
        assertEquals("", JcefOsrView.schemeOf("C:/x.html"));
        assertEquals("", JcefOsrView.schemeOf(null));
    }
}
