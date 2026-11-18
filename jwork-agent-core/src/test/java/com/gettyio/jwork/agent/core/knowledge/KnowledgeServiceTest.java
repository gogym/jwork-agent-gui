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

package com.gettyio.jwork.agent.core.knowledge;

import com.gettyio.jwork.agent.core.chat.MultiAgentGateway;
import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.dao.AgentDefinitionDao;
import com.gettyio.jwork.agent.domain.entity.AgentDefinition;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeService} 纯逻辑单测：以真实临时目录 + 桩网关（{@code workspaceForAgent} 指回临时目录）
 * 验证导入命名清洗、KNOWLEDGE.md 索引重建、摘要标记与回退、同名去重、目录穿越防护、删除一致性与类型校验。
 * <p>不触碰数据库运行体（导入/列示/删除路径不依赖 {@code db}），{@code listAgents} 用 mock DAO 覆盖。</p>
 *
 * @author gogym
 */
class KnowledgeServiceTest {

    @TempDir
    Path temp;

    private KnowledgeService service;
    private Path knowledgeDir;

    @BeforeEach
    void setUp() {
        MultiAgentGateway gateway = mock(MultiAgentGateway.class);
        Path workspace = temp.resolve("ws");
        when(gateway.workspaceForAgent(anyString())).thenReturn(workspace);
        knowledgeDir = workspace.resolve("knowledge");
        service = new KnowledgeService(mock(JworkDatabase.class), gateway);
    }

    private static byte[] txt(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("导入：清洗基名生成 .md、写摘要标记、重建索引含该文件与摘要")
    void importCleansNameWritesSummaryAndIndex() {
        String name = service.importDocument("A", "My Report!.txt", "text/plain",
                txt("Hello world content"), "自定义摘要");

        assertEquals("My-Report.md", name);
        assertTrue(Files.exists(knowledgeDir.resolve(name)), "入库文件应落在 knowledge/ 下");
        String doc = service.readDoc("A", name);
        assertTrue(doc.contains("<!-- jwork:summary=自定义摘要 -->"), "文件头应含摘要标记");
        assertTrue(doc.contains("Hello world content"), "正文应入库");

        String index = service.readIndex("A");
        assertTrue(index.contains(name), "索引应列出文件名");
        assertTrue(index.contains("自定义摘要"), "索引应带摘要");
    }

    @Test
    @DisplayName("导入：摘要留空时从正文首个有效行派生")
    void importDerivesSummaryWhenBlank() {
        service.importDocument("A", "note.md", "text/markdown",
                txt("操作系统维护手册\n第二行不重要"), "");
        String index = service.readIndex("A");
        assertTrue(index.contains("操作系统维护手册"), "空摘要应回退正文首有效行");
        assertFalse(index.contains("第二行不重要"), "只取首行，不应含后续行");
    }

    @Test
    @DisplayName("导入：同名基去重，第二次追加 -1 后缀")
    void importDedupsSameBase() {
        String first = service.importDocument("A", "dup.txt", "text/plain", txt("one"), "s1");
        String second = service.importDocument("A", "dup.txt", "text/plain", txt("two"), "s2");
        assertEquals("dup.md", first);
        assertEquals("dup-1.md", second);
        assertEquals(2, service.listDocs("A").size());
    }

    @Test
    @DisplayName("列表：按名升序且排除 KNOWLEDGE.md 索引本身")
    void listDocsSortedAndExcludesIndex() {
        service.importDocument("A", "beta.txt", "text/plain", txt("b"), "s");
        service.importDocument("A", "alpha.txt", "text/plain", txt("a"), "s");
        List<KnowledgeService.DocInfo> docs = service.listDocs("A");
        assertEquals(2, docs.size());
        assertEquals("alpha.md", docs.get(0).name());
        assertEquals("beta.md", docs.get(1).name());
        assertTrue(docs.stream().noneMatch(d -> "KNOWLEDGE.md".equals(d.name())), "不应列出索引文件");
    }

    @Test
    @DisplayName("删除：移除文件并从索引剔除其行")
    void deleteRemovesFileAndIndexLine() {
        String name = service.importDocument("A", "del.txt", "text/plain", txt("to delete"), "摘要删除我");
        assertTrue(service.readIndex("A").contains(name));
        service.deleteDoc("A", name);
        assertFalse(Files.exists(knowledgeDir.resolve(name)));
        assertFalse(service.readIndex("A").contains(name), "删除后索引不应再含该文件");
    }

    @Test
    @DisplayName("目录穿越防护：非法名/索引名被拒，删除读取均空操作不越界")
    void traversalNamesRejected() {
        service.importDocument("A", "safe.txt", "text/plain", txt("x"), "s");
        // 非法名：readDoc 返回空串，deleteDoc 无操作、不抛异常
        assertEquals("", service.readDoc("A", "../evil.txt"));
        assertEquals("", service.readDoc("A", "..\\evil.txt"));
        service.deleteDoc("A", "../safe.md");   // 应被拒绝，不删除
        assertTrue(Files.exists(knowledgeDir.resolve("safe.md")), "越界删除不得触达真实文件");
        service.deleteDoc("A", "KNOWLEDGE.md"); // 索引名不可删
        assertTrue(Files.exists(knowledgeDir.resolve("KNOWLEDGE.md")));
    }

    @Test
    @DisplayName("类型与空内容校验：不支持类型、空字节各抛其因")
    void validatesTypeAndEmptyBytes() {
        assertThrows(IllegalArgumentException.class,
                () -> service.importDocument("A", "bad.exe", "application/x-msdownload", txt("data"), "s"));
        assertThrows(IllegalArgumentException.class,
                () -> service.importDocument("A", "ok.txt", "text/plain", new byte[0], "s"));
    }

    @Test
    @DisplayName("listAgents：委托 agentDao.findAll")
    void listAgentsDelegatesToDao() {
        JworkDatabase db = mock(JworkDatabase.class);
        AgentDefinitionDao dao = mock(AgentDefinitionDao.class);
        when(db.agentDao()).thenReturn(dao);
        AgentDefinition a = new AgentDefinition();
        a.setAgentCode("A");
        when(dao.findAll()).thenReturn(List.of(a));
        MultiAgentGateway gateway = mock(MultiAgentGateway.class);
        assertEquals(1, new KnowledgeService(db, gateway).listAgents().size());
    }

    @Test
    @DisplayName("URL 导入：抓取 HTML 剥标签落盘（主机名折连字符命名），script/style 剥离，摘要与索引就位")
    void importFromUrlExtractsHtmlText() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/guide", ex -> {
            String html = "<html><head><script>var x=1;</script><style>.a{color:red}</style></head>"
                    + "<body><h1>快速指南</h1><p>第一步：安装依赖</p></body></html>";
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/guide";
            String name = service.importFromUrl("A", url, "本地指南");

            assertEquals("127-0-0-1-guide.md", name);
            String doc = service.readDoc("A", name);
            assertTrue(doc.contains("<!-- jwork:summary=本地指南 -->"), "摘要标记应写入文件头");
            assertTrue(doc.contains("快速指南"), "标题文字应保留");
            assertTrue(doc.contains("第一步：安装依赖"), "段落文字应保留");
            assertFalse(doc.contains("var x=1"), "script 内容应被剥离");
            assertFalse(doc.contains("color:red"), "style 内容应被剥离");
            assertTrue(service.readIndex("A").contains(name), "KNOWLEDGE.md 应列出新文件");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("URL 导入：非 http/https 地址与抓取非 2xx 各抛其因")
    void importFromUrlRejectsBadAddressAndStatus() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> service.importFromUrl("A", "ftp://host/x", "s"));
        assertThrows(IllegalArgumentException.class, () -> service.importFromUrl("A", "file:///etc/passwd", "s"));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/missing";
            assertThrows(IllegalStateException.class, () -> service.importFromUrl("A", url, "s"));
        } finally {
            server.stop(0);
        }
    }
}
