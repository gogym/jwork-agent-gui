package com.gettyio.gim.agent.core.knowledge;

import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.KnowledgeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * 向量知识索引服务：文档分块 → 嵌入 → 落库（knowledge_chunks），查询向量做余弦检索。
 * <p>嵌入客户端由装配方以 {@link Supplier} 注入、运行时现取——设置页改配置无需重启即生效；
 * 返回 {@code null} 表示未配置（此时索引/检索为无害跳过，文件树路线不受影响）。</p>
 */
public class EmbeddingIndexService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexService.class);

    /** 检索默认返回条数。 */
    private static final int DEFAULT_TOP_K = 5;

    private final GimDatabase db;
    private final Supplier<EmbeddingClient> clientSupplier;

    public EmbeddingIndexService(GimDatabase db, Supplier<EmbeddingClient> clientSupplier) {
        this.db = db;
        this.clientSupplier = clientSupplier;
    }

    /** 检索命中：所属文档、块序、原文、相似度（余弦，[-1,1]）。 */
    public record SearchHit(String docName, int chunkIdx, String content, double score) {
    }

    /** 当前嵌入客户端；未配置返回 {@code null}。 */
    public EmbeddingClient currentClient() {
        try {
            return clientSupplier.get();
        } catch (RuntimeException e) {
            log.warn("读取 embedding 配置失败", e);
            return null;
        }
    }

    /** 是否已配置可用的 embedding 提供方。 */
    public boolean isConfigured() {
        return currentClient() != null;
    }

    /**
     * 索引一份文档：整文档替换式写入分块向量。
     *
     * @return 写入的块数；未配置 embedding 时返回 -1（调用方据此提示用户）
     * @throws IllegalStateException 嵌入服务调用失败
     */
    public int indexDocument(String agentCode, String docName, String text) {
        EmbeddingClient client = currentClient();
        if (client == null) {
            return -1;
        }
        List<String> parts = TextChunker.chunk(text);
        if (parts.isEmpty()) {
            db.knowledgeChunkDao().deleteDoc(agentCode, docName);
            return 0;
        }
        float[][] vectors = client.embedAll(parts);
        List<KnowledgeChunk> chunks = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            KnowledgeChunk c = new KnowledgeChunk();
            c.setAgentCode(agentCode);
            c.setDocName(docName);
            c.setChunkIdx(i);
            c.setContent(parts.get(i));
            c.setEmbedding(vectors[i]);
            chunks.add(c);
        }
        db.knowledgeChunkDao().replaceDoc(agentCode, docName, chunks);
        log.info("向量索引完成: agent={}, doc={}, chunks={}", agentCode, docName, chunks.size());
        return chunks.size();
    }

    /** 删除某份文档的向量块（文件删除联动）。 */
    public void removeDocument(String agentCode, String docName) {
        db.knowledgeChunkDao().deleteDoc(agentCode, docName);
    }

    /** 删除某智能体的全部向量块（智能体删除联动）。 */
    public void removeAgent(String agentCode) {
        db.knowledgeChunkDao().deleteByAgent(agentCode);
    }

    /** 已索引文档数。 */
    public int docCount(String agentCode) {
        return db.knowledgeChunkDao().countDocs(agentCode);
    }

    /** 向量块总数。 */
    public int chunkCount(String agentCode) {
        return db.knowledgeChunkDao().countChunks(agentCode);
    }

    /**
     * 余弦检索该智能体的知识块，取相似度最高的 topK 条。
     * <p>未配置 embedding 时返回空列表；维度不符的旧块跳过（换过 embedding 模型后重建索引即可）。</p>
     */
    public List<SearchHit> search(String agentCode, String query, int topK) {
        EmbeddingClient client = currentClient();
        if (client == null || query == null || query.isBlank()) {
            return List.of();
        }
        float[] qv = client.embed(query);
        int k = topK <= 0 ? DEFAULT_TOP_K : topK;
        List<SearchHit> hits = new ArrayList<>();
        for (KnowledgeChunk c : db.knowledgeChunkDao().findByAgent(agentCode)) {
            Double sim = cosine(qv, c.getEmbedding());
            if (sim != null) {
                hits.add(new SearchHit(c.getDocName(), c.getChunkIdx(), c.getContent(), sim));
            }
        }
        hits.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        return hits.size() > k ? hits.subList(0, k) : hits;
    }

    /** 余弦相似度；维度不符返回 {@code null}（视为不可比）。 */
    private static Double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return null;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return null;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
