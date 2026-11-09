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

package com.gettyio.gim.agent.domain.dao;

import com.gettyio.gim.agent.domain.entity.KnowledgeChunk;
import com.gettyio.gim.agent.domain.persist.PersistException;
import com.gettyio.gim.agent.domain.persist.SqlRunner;
import com.gettyio.gim.agent.domain.persist.SqlSchemaInitializer;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KnowledgeChunkDao#replaceDoc} 的原子性单测：删旧块与写新块必须同进同退。
 * <p>失败注入用真实约束——{@code content NOT NULL}，抽取器给出空正文就是这么个下场，
 * 比 mock 更接近线上。</p>
 *
 * @author gogym
 */
class KnowledgeChunkDaoTest {

    private static final String AGENT = "gim-agent";
    private static final String DOC = "手册.md";

    @TempDir
    Path tempDir;

    private KnowledgeChunkDao dao;

    @BeforeEach
    void setUp() {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(tempDir.resolve("gim.db"));
        new SqlSchemaInitializer(cp).initialize();
        SqlRunner runner = new SqlRunner(cp);
        dao = new KnowledgeChunkDao(runner);
    }

    private static KnowledgeChunk chunk(int idx, String content) {
        KnowledgeChunk c = new KnowledgeChunk();
        c.setChunkIdx(idx);
        c.setContent(content);
        c.setEmbedding(new float[]{idx, 1f});
        return c;
    }

    @Test
    @DisplayName("成功路径：整文档替换，旧块不残留")
    void replaceSwapsWholeDoc() {
        dao.replaceDoc(AGENT, DOC, List.of(chunk(0, "旧一"), chunk(1, "旧二")));
        dao.replaceDoc(AGENT, DOC, List.of(chunk(0, "新一")));

        List<KnowledgeChunk> stored = dao.findByAgent(AGENT);
        assertEquals(1, stored.size());
        assertEquals("新一", stored.get(0).getContent());
    }

    @Test
    @DisplayName("新块写入中途失败：旧块仍在，文档不会从索引里静默消失")
    void failureMidInsertKeepsOldChunks() {
        dao.replaceDoc(AGENT, DOC, List.of(chunk(0, "旧一"), chunk(1, "旧二"), chunk(2, "旧三")));

        // 第 2 块正文为空，撞 NOT NULL 约束：删除已执行、第 1 块已写入，整笔必须回滚
        assertThrows(PersistException.class, () ->
                dao.replaceDoc(AGENT, DOC, List.of(chunk(0, "新一"), chunk(1, null), chunk(2, "新三"))));

        List<KnowledgeChunk> stored = dao.findByAgent(AGENT);
        assertEquals(3, stored.size(), "删旧与写新要一起消失，否则这份文档再也检索不到");
        assertEquals("旧一", stored.get(0).getContent());
        assertEquals("旧三", stored.get(2).getContent());
    }

    @Test
    @DisplayName("空列表即清空该文档（其余文档不受影响）")
    void emptyListClearsOnlyThatDoc() {
        dao.replaceDoc(AGENT, DOC, List.of(chunk(0, "a")));
        dao.replaceDoc(AGENT, "另一篇.md", List.of(chunk(0, "b")));

        dao.replaceDoc(AGENT, DOC, List.of());

        List<KnowledgeChunk> stored = dao.findByAgent(AGENT);
        assertEquals(1, stored.size());
        assertTrue(stored.get(0).getDocName().equals("另一篇.md"));
        assertEquals(1, dao.countDocs(AGENT));
    }
}
