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

import com.gettyio.jwork.agent.core.JworkPaths;
import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.persist.SqliteConnectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EmbeddingIndexService} 与 {@link TextChunker} 单测：假嵌入客户端按词根生成可复现向量，
 * 真实 SQLite 落库验整文档替换、余弦检索与未配置无害跳过。
 *
 * @author gogym
 */
class EmbeddingIndexServiceTest {

    /** 确定性假嵌入：按词根哈希置 1 的 64 维稀疏向量，含同词根的文本相似度高。 */
    private static final class FakeClient implements EmbeddingClient {
        @Override
        public float[][] embedAll(List<String> texts) {
            float[][] out = new float[texts.size()][];
            for (int i = 0; i < texts.size(); i++) {
                out[i] = vector(texts.get(i));
            }
            return out;
        }

        private static float[] vector(String text) {
            float[] v = new float[64];
            for (String token : text.toLowerCase().split("[^a-z0-9]+")) {
                if (token.isEmpty()) {
                    continue;
                }
                v[Math.abs(token.hashCode()) % 64] = 1;
            }
            return v;
        }
    }

    /** 与 {@link FakeClient} 向量口径相同、但类身份不同（默认 cacheKey 即类名）：验证换嵌入配置触发指纹失配。 */
    private static final class FakeClientAlt implements EmbeddingClient {
        private final FakeClient delegate = new FakeClient();

        @Override
        public float[][] embedAll(List<String> texts) {
            return delegate.embedAll(texts);
        }
    }

    @TempDir
    Path tempDir;

    private JworkDatabase db;
    private EmbeddingIndexService indexer;

    @BeforeEach
    void setUp() {
        db = new JworkDatabase(new SqliteConnectionProvider(tempDir.resolve("jwork.db")));
        db.initSchema();
        indexer = new EmbeddingIndexService(db, FakeClient::new);
    }

    @Test
    @DisplayName("索引文档：分块落库，文档数/块数计数一致")
    void indexDocumentStoresChunks() {
        String text = ("机器学习是人工智能的一个分支。").repeat(100);
        int chunks = indexer.indexDocument("agent-a", "ml.md", text);

        assertTrue(chunks > 1, "长文本应产生多个块");
        assertEquals(1, indexer.docCount("agent-a"));
        assertEquals(chunks, indexer.chunkCount("agent-a"));
    }

    @Test
    @DisplayName("重复索引同文档：整文档替换不叠加")
    void reindexReplaces() {
        String text = "第一段内容。" + ("后续段落内容若干。").repeat(40);
        int first = indexer.indexDocument("agent-a", "doc.md", text);
        int second = indexer.indexDocument("agent-a", "doc.md", text);

        assertEquals(first, second);
        assertEquals(first, indexer.chunkCount("agent-a"));
    }

    @Test
    @DisplayName("检索：查询词命中对应文档块，相关度降序")
    void searchReturnsRelevantHits() {
        // 英文词根与假嵌入分词口径一致：query 与 java.md 共享 threadpool/concurrency 两个词根
        indexer.indexDocument("agent-a", "java.md", "concurrency threadpool virtualthreads");
        indexer.indexDocument("agent-a", "cook.md", "cooking pork sugar sauce");

        List<EmbeddingIndexService.SearchHit> hits = indexer.search("agent-a", "threadpool concurrency", 5);

        assertTrue(!hits.isEmpty());
        assertEquals("java.md", hits.get(0).docName());
        double top = hits.get(0).score();
        assertTrue(hits.stream().allMatch(h -> h.score() <= top));
    }

    @Test
    @DisplayName("删除文档/智能体联动清块")
    void removeClearsChunks() {
        indexer.indexDocument("agent-a", "a.md", "内容若干。");
        indexer.indexDocument("agent-b", "b.md", "另一些内容。");

        indexer.removeDocument("agent-a", "a.md");
        assertEquals(0, indexer.chunkCount("agent-a"));
        assertEquals(1, indexer.chunkCount("agent-b"));

        indexer.removeAgent("agent-b");
        assertEquals(0, indexer.chunkCount("agent-b"));
    }

    @Test
    @DisplayName("未配置 embedding：索引返回 -1、检索为空，均不抛异常")
    void unconfiguredDegradesGracefully() {
        EmbeddingIndexService plain = new EmbeddingIndexService(db, () -> null);

        assertEquals(-1, plain.indexDocument("agent-a", "a.md", "内容"));
        assertTrue(plain.search("agent-a", "任意", 5).isEmpty());
        assertTrue(!plain.isConfigured());
    }

    @Test
    @DisplayName("分块器：单块上限、跨块重叠、短文本单块")
    void chunkerBasics() {
        List<String> single = TextChunker.chunk("短文本一。");
        assertEquals(1, single.size());

        String longText = "段落甲。".repeat(300) + "\n\n" + "段落乙。".repeat(300);
        List<String> chunks = TextChunker.chunk(longText);
        assertTrue(chunks.size() >= 2);
        for (String c : chunks) {
            assertTrue(c.length() <= 1200, "块长不超上限");
        }
        // 相邻块存在重叠：后块以前块尾部（overlap 150 字符）开头
        String prev = chunks.get(chunks.size() - 3);
        String next = chunks.get(chunks.size() - 2);
        if (prev.length() > 150) {
            assertTrue(next.startsWith(prev.substring(prev.length() - 150)), "后块应以前块尾部重叠开头");
        }
    }

    @Test
    @DisplayName("增量指纹：索引后判定最新；内容/配置变更、删除后失配")
    void fingerprintTracksChanges() {
        String text = "稳定的知识内容。";
        assertTrue(!indexer.isUpToDate("agent-a", "a.md", text), "未索引不应视为最新");

        indexer.indexDocument("agent-a", "a.md", text);
        assertTrue(indexer.isUpToDate("agent-a", "a.md", text), "同内容同配置应判定最新");
        assertTrue(!indexer.isUpToDate("agent-a", "a.md", text + "追加"), "内容变更应失配");
        assertTrue(!indexer.isUpToDate("agent-a", "other.md", text), "未索引的其他文档应失配");

        EmbeddingIndexService swapped = new EmbeddingIndexService(db, FakeClientAlt::new);
        assertTrue(!swapped.isUpToDate("agent-a", "a.md", text), "换嵌入配置应失配");

        indexer.removeDocument("agent-a", "a.md");
        assertTrue(!indexer.isUpToDate("agent-a", "a.md", text), "删除后指纹应清除");
    }

    @Test
    @DisplayName("混合检索：0.7×向量 + 0.3×关键词——子串字面命中（向量无共享）也得分，双通道最高")
    void hybridScoringVectorPlusKeyword() {
        // query 词面 "cook"：假嵌入按词根哈希置位，"cooking" 哈希位与其不同（向量无共享）但含子串 "cook"
        indexer.indexDocument("agent-a", "c.md", "cook pork");   // 双通道：向量共享 + 字面命中
        indexer.indexDocument("agent-a", "b.md", "cooking");     // 仅关键词通道（子串命中）
        indexer.indexDocument("agent-a", "a.md", "pork sugar");  // 双通道皆无

        List<EmbeddingIndexService.SearchHit> hits = indexer.search("agent-a", "cook", 5);

        assertEquals(3, hits.size());
        assertEquals("c.md", hits.get(0).docName());
        assertEquals(0.7 * (1 / Math.sqrt(2)) + 0.3, hits.get(0).score(), 1e-9);
        assertEquals("b.md", hits.get(1).docName());
        assertEquals(0.3, hits.get(1).score(), 1e-9);
        assertEquals("a.md", hits.get(2).docName());
        assertEquals(0.0, hits.get(2).score(), 1e-9);
    }
}
