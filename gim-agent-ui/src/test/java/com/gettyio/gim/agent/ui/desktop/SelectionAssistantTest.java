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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SelectionAssistant} 的静态组装逻辑测试：只覆盖纯函数，不触 FX 不弹窗。
 *
 * @author gogym
 */
class SelectionAssistantTest {

    @Test
    void askKeepsOriginalText() {
        assertEquals("hello world", SelectionAssistant.buildPrompt(SelectionAssistant.Action.ASK, "hello world"));
    }

    @Test
    void translatePrependsDirective() {
        String out = SelectionAssistant.buildPrompt(SelectionAssistant.Action.TRANSLATE, "hello");
        assertTrue(out.endsWith("hello"));
        assertNotEquals("hello", out);
    }

    @Test
    void polishPrependsDirective() {
        String out = SelectionAssistant.buildPrompt(SelectionAssistant.Action.POLISH, "hello");
        assertTrue(out.endsWith("hello"));
        assertNotEquals("hello", out);
    }

    @Test
    void previewFoldsWhitespace() {
        assertEquals("a b c", SelectionAssistant.truncatePreview("a\n b\t\tc", 20));
    }

    @Test
    void previewTruncatesLongText() {
        assertEquals("12345…", SelectionAssistant.truncatePreview("12345678901234567890", 5));
    }

    @Test
    void previewKeepsShortTextAsIs() {
        assertEquals("abc", SelectionAssistant.truncatePreview("  abc  ", 10));
    }
}
