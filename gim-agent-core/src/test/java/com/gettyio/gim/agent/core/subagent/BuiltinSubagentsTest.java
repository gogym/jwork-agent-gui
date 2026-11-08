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

package com.gettyio.gim.agent.core.subagent;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuiltinSubagents} 契约单测：内置 {@code explore} 必须只读（白名单里没有写/跑命令的工具）、
 * 只能被委派、且与用户自定义声明共存时让位于用户。
 *
 * @author gogym
 */
class BuiltinSubagentsTest {

    @Test
    @DisplayName("explore 声明：只读白名单 + 仅子代理档位 + 内联人格")
    void exploreDeclarationIsReadOnly() {
        SubagentDeclaration decl = BuiltinSubagents.explore();

        assertEquals("explore", decl.getName());
        assertEquals(SubagentDeclaration.Mode.SUBAGENT, decl.getMode());
        assertEquals(WorkspaceMode.ISOLATED, decl.getWorkspaceMode());
        assertEquals(BuiltinSubagents.EXPLORE_TOOLS, decl.getTools());
        assertTrue(decl.getSteps() > 10, "steps=" + decl.getSteps());
        assertFalse(decl.getInlineAgentsBody().isBlank());
        assertTrue(decl.getDescription().contains("只读"), decl.getDescription());
    }

    @Test
    @DisplayName("白名单里不存在写文件、跑命令、联网与记忆写入工具")
    void allowlistExcludesSideEffectTools() {
        List<String> forbidden = List.of("write_file", "edit_file", "execute", "shell",
                "deliver_artifact", "memory_save", "web_search", "web_fetch", "agent_spawn");
        for (String name : forbidden) {
            assertFalse(BuiltinSubagents.EXPLORE_TOOLS.contains(name), "不应授予: " + name);
        }
    }

    @Test
    @DisplayName("没有用户定义时仍内置 explore；有定义时排在最前且顺序不变")
    void mergesAheadOfUserDeclarations() {
        assertEquals(1, BuiltinSubagents.withExplore(null).size());
        assertEquals(1, BuiltinSubagents.withExplore(List.of()).size());

        SubagentDeclaration a = decl("reviewer");
        SubagentDeclaration b = decl("translator");
        List<SubagentDeclaration> merged = BuiltinSubagents.withExplore(new ArrayList<>(List.of(a, b)));

        assertEquals(List.of("explore", "reviewer", "translator"),
                merged.stream().map(SubagentDeclaration::getName).toList());
    }

    @Test
    @DisplayName("用户自己占了 explore 这个名字：不再重复注入，用户的说了算")
    void userNameWinsOnCollision() {
        List<SubagentDeclaration> merged = BuiltinSubagents.withExplore(List.of(decl(" Explore ")));

        assertEquals(1, merged.size());
        assertEquals(" Explore ", merged.get(0).getName());
    }

    @Test
    @DisplayName("null 元素被过滤，不会把空声明交给框架")
    void nullEntriesAreSkipped() {
        List<SubagentDeclaration> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(decl("reviewer"));

        assertEquals(2, BuiltinSubagents.withExplore(withNull).size());
    }

    private static SubagentDeclaration decl(String name) {
        return SubagentDeclaration.builder()
                .name(name)
                .description("用户自定义的 " + name)
                .build();
    }
}
