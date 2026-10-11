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

package com.gettyio.gim.agent.core.memory;

import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.core.memory.MemoryService.MemoryFile;
import com.gettyio.gim.agent.core.memory.MemoryService.MemoryScope;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.dao.AgentDefinitionDao;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MemoryService} 纯逻辑单测：以真实临时目录 + 桩网关（共享工作区与各智能体工作区指到临时目录）
 * 验证作用域枚举分组与合并、文件统计、按作用域读写删、未知键拒绝、文件名防穿越、分类文件 CRUD 与命名清洗。
 * <p>不触碰数据库运行体：{@code db} 仅经 mock DAO 提供智能体定义列表。</p>
 *
 * @author gogym
 */
class MemoryServiceTest {

    @TempDir
    Path temp;

    private MemoryService service;
    private Path shared;
    private Path agentWs;

    @BeforeEach
    void setUp() {
        shared = temp.resolve("shared");
        agentWs = temp.resolve("agentA");
        MultiAgentGateway gateway = mock(MultiAgentGateway.class);
        when(gateway.sharedWorkspace()).thenReturn(shared);
        when(gateway.workspaceForAgent(anyString())).thenReturn(shared);
        when(gateway.workspaceForAgent("A")).thenReturn(agentWs);

        AgentDefinition a = new AgentDefinition();
        a.setAgentCode("A");
        a.setAgentName("写作助手");
        AgentDefinition b = new AgentDefinition();
        b.setAgentCode("B");
        b.setAgentName("翻译助手");
        GimDatabase db = mock(GimDatabase.class);
        AgentDefinitionDao dao = mock(AgentDefinitionDao.class);
        when(db.agentDao()).thenReturn(dao);
        when(dao.findAll()).thenReturn(List.of(a, b));
        service = new MemoryService(db, gateway);
    }

    private String sharedKey() {
        return shared.toAbsolutePath().normalize().toString();
    }

    private String agentKey() {
        return agentWs.toAbsolutePath().normalize().toString();
    }

    @Test
    @DisplayName("作用域枚举：共享恒首位且未设专属目录的智能体并入；专属工作区独立成卡并统计文件")
    void listScopesGroupsAndCounts() throws IOException {
        Files.createDirectories(agentWs.resolve("memory"));
        Files.writeString(agentWs.resolve("MEMORY.md"), "# 长期记忆");
        Files.writeString(agentWs.resolve("memory").resolve("2026-10-01.md"), "daily");

        List<MemoryScope> scopes = service.listScopes();

        assertEquals(2, scopes.size());
        MemoryScope sharedScope = scopes.get(0);
        assertTrue(sharedScope.shared());
        assertEquals(sharedKey(), sharedScope.key());
        assertEquals(List.of("翻译助手"), sharedScope.agentNames(), "未设专属目录的智能体应并入共享作用域");
        assertEquals(0, sharedScope.fileCount());

        MemoryScope agentScope = scopes.get(1);
        assertFalse(agentScope.shared());
        assertEquals(agentKey(), agentScope.key());
        assertEquals(List.of("写作助手"), agentScope.agentNames());
        assertEquals(2, agentScope.fileCount(), "MEMORY.md 计 1 + 台账 1");
        assertTrue(agentScope.lastModified() > 0);
    }

    @Test
    @DisplayName("多智能体共用同一专属目录时合并为一张卡")
    void listScopesMergesSameDir() {
        MultiAgentGateway gateway = mock(MultiAgentGateway.class);
        when(gateway.sharedWorkspace()).thenReturn(shared);
        when(gateway.workspaceForAgent(anyString())).thenReturn(agentWs);
        AgentDefinition a = new AgentDefinition();
        a.setAgentCode("A");
        a.setAgentName("写作助手");
        AgentDefinition b = new AgentDefinition();
        b.setAgentCode("B");
        b.setAgentName("翻译助手");
        GimDatabase db = mock(GimDatabase.class);
        AgentDefinitionDao dao = mock(AgentDefinitionDao.class);
        when(db.agentDao()).thenReturn(dao);
        when(dao.findAll()).thenReturn(List.of(a, b));

        List<MemoryScope> scopes = new MemoryService(db, gateway).listScopes();

        assertEquals(2, scopes.size(), "共享 + 一张合并的专属卡");
        MemoryScope merged = scopes.get(1);
        assertFalse(merged.shared());
        assertEquals(List.of("写作助手", "翻译助手"), merged.agentNames());
    }

    @Test
    @DisplayName("写读长期记忆：写到共享作用域并可读回，文件数随之更新")
    void writeAndReadLongTerm() {
        service.writeLongTerm(sharedKey(), "用户偏好：简洁回答");

        assertEquals("用户偏好：简洁回答", service.readLongTerm(sharedKey()));
        assertEquals(1, service.listScopes().get(0).fileCount());
    }

    @Test
    @DisplayName("台账：列表按名逆序、读回内容、删除后文件消失")
    void dailyLedgerLifecycle() throws IOException {
        Files.createDirectories(agentWs.resolve("memory"));
        Files.writeString(agentWs.resolve("memory").resolve("2026-10-01.md"), "one");
        Files.writeString(agentWs.resolve("memory").resolve("2026-10-02.md"), "two");

        assertEquals(List.of("2026-10-02.md", "2026-10-01.md"), service.listDailyFiles(agentKey()));
        assertEquals("two", service.readMemoryFile(agentKey(), "2026-10-02.md"));

        service.deleteMemoryFile(agentKey(), "2026-10-02.md");
        assertFalse(Files.exists(agentWs.resolve("memory").resolve("2026-10-02.md")));
        assertEquals(List.of("2026-10-01.md"), service.listDailyFiles(agentKey()));
    }

    @Test
    @DisplayName("整个删除：MEMORY.md 与 memory/ 目录（含 archive 归档）一并移除")
    void deleteAllRemovesEverything() throws IOException {
        Files.createDirectories(agentWs.resolve("memory").resolve("archive"));
        Files.writeString(agentWs.resolve("MEMORY.md"), "x");
        Files.writeString(agentWs.resolve("memory").resolve("2026-10-01.md"), "x");
        Files.writeString(agentWs.resolve("memory").resolve("archive").resolve("old.md"), "x");

        service.deleteAll(agentKey());

        assertFalse(Files.exists(agentWs.resolve("MEMORY.md")));
        assertFalse(Files.exists(agentWs.resolve("memory")));
        assertEquals(0, service.listScopes().get(1).fileCount());
    }

    @Test
    @DisplayName("未知作用域键：读返回空、写/删无操作、不触碰任何路径")
    void unknownScopeKeyRejected() {
        String fake = temp.resolve("elsewhere").toAbsolutePath().normalize().toString();

        assertEquals("", service.readLongTerm(fake));
        assertEquals(List.of(), service.listDailyFiles(fake));
        assertEquals(List.of(), service.listCategoryFiles(fake));
        assertEquals("", service.readMemoryFile(fake, "a.md"));
        service.writeLongTerm(fake, "nope");
        service.deleteMemoryFile(fake, "a.md");
        service.deleteAll(fake);
        assertThrows(IllegalArgumentException.class,
                () -> service.createCategoryFile(fake, "a", "x"), "未知作用域新建应拒绝");

        assertFalse(Files.exists(temp.resolve("elsewhere")), "未知键不得创建/删除任何文件");
    }

    @Test
    @DisplayName("文件名防穿越：含分隔符/.. 的台账名一律拒绝")
    void traversalNamesRejected() throws IOException {
        Files.createDirectories(agentWs.resolve("memory"));
        Files.writeString(agentWs.resolve("memory").resolve("safe.md"), "keep");

        assertEquals("", service.readMemoryFile(agentKey(), "../safe.md"));
        assertEquals("", service.readMemoryFile(agentKey(), "..\\safe.md"));
        assertEquals("", service.readMemoryFile(agentKey(), "sub/safe.md"));
        service.deleteMemoryFile(agentKey(), "../safe.md");

        assertTrue(Files.exists(agentWs.resolve("memory").resolve("safe.md")), "越界删除不得触达真实文件");
    }

    @Test
    @DisplayName("分类记忆文件：新建返回清洗名、可读回、与台账分流并按修改时间倒序")
    void categoryFileLifecycle() throws IOException {
        Path memoryDir = agentWs.resolve("memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("2026-10-01.md"), "daily");
        Files.writeString(memoryDir.resolve(".consolidation_state"), "1");

        String created = service.createCategoryFile(agentKey(), " 用户偏好.MD ", "- 简洁回答");
        assertEquals("用户偏好.md", created, "中文保留且后缀归一为小写 .md");
        assertEquals("- 简洁回答", service.readMemoryFile(agentKey(), created));
        assertEquals("Project-Notes.md",
                service.createCategoryFile(agentKey(), "Project Notes!", "- ship on Friday"),
                "空格/符号折叠为连字符");

        Files.setLastModifiedTime(memoryDir.resolve("用户偏好.md"), FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(memoryDir.resolve("Project-Notes.md"), FileTime.fromMillis(2_000L));
        assertEquals(List.of("Project-Notes.md", "用户偏好.md"),
                service.listCategoryFiles(agentKey()).stream().map(MemoryFile::name).toList(),
                "按修改时间倒序，台账与非 md 文件不得混入");
        assertEquals(List.of("2026-10-01.md"), service.listDailyFiles(agentKey()));

        service.deleteMemoryFile(agentKey(), created);
        assertFalse(Files.exists(memoryDir.resolve("用户偏好.md")));
    }

    @Test
    @DisplayName("新建分类文件：重名报错、日期名与清洗后空名报错")
    void categoryFileCreationRejected() throws IOException {
        Path memoryDir = agentWs.resolve("memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("dup.md"), "x");

        assertThrows(IllegalStateException.class,
                () -> service.createCategoryFile(agentKey(), "dup", "y"), "重名应报错");
        assertThrows(IllegalArgumentException.class,
                () -> service.createCategoryFile(agentKey(), "2026-10-01", "y"),
                "日期格式名与台账冲突应拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> service.createCategoryFile(agentKey(), "///", "y"), "清洗后为空的名称应拒绝");
    }

    @Test
    @DisplayName("文件名规范化：保留中英文数字、折叠非法字符；空/保留/超长/日期名返回 null")
    void normalizeNameRules() {
        assertEquals("用户偏好.md", MemoryService.normalizeCategoryName(" 用户偏好 "));
        assertEquals("Project-Notes.md", MemoryService.normalizeCategoryName("Project Notes!"));
        assertEquals("notes.md", MemoryService.normalizeCategoryName("notes.MD"));
        assertEquals("x".repeat(60) + ".md", MemoryService.normalizeCategoryName("x".repeat(60)));

        assertNull(MemoryService.normalizeCategoryName(null));
        assertNull(MemoryService.normalizeCategoryName(""));
        assertNull(MemoryService.normalizeCategoryName("///"));
        assertNull(MemoryService.normalizeCategoryName("2026-10-01"));
        assertNull(MemoryService.normalizeCategoryName("archive"));
        assertNull(MemoryService.normalizeCategoryName("x".repeat(61)));
    }
}
