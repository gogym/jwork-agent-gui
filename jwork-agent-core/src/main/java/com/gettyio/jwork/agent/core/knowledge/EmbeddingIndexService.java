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

import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.entity.KnowledgeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 向量知识索引服务：文档分块 → 嵌入 → 落库（knowledge_chunks），查询时做向量 + 关键词混合检索。
 * <p>嵌入客户端由装配方以 {@link Supplier} 注入、运行时现取——设置页改配置无需重启即生效；
 * 返回 {@code null} 表示未配置（此时索引/检索为无害跳过，文件树路线不受影响）。</p>
 * <p>增量重建：每份文档索引后把「内容 + 嵌入配置」指纹写入 settings 表（不改表结构），
 * {@link #isUpToDate} 供批量重建跳过未变更文档；删除文档/智能体时指纹随之清理。</p>
 *
 * @author gogym
 */
public class EmbeddingIndexService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexService.class);

    /** 检索默认返回条数。 */
    private static final int DEFAULT_TOP_K = 5;

    /** 文档索引指纹的设置键前缀（前缀 + agentCode + "/" + docName）。 */
    private static final String FP_PREFIX = "knowledge.index.fingerprint:";

    /** 混合评分权重：向量语义为主，关键词覆盖校正。 */
    private static final double VECTOR_WEIGHT = 0.7;
    private static final double KEYWORD_WEIGHT = 0.3;

    /** 查询分词：汉字连续段 / 拉丁数字连续段。 */
    private static final Pattern TERM_PATTERN = Pattern.compile("[\\p{IsHan}]+|[a-z0-9_]+");

    private final JworkDatabase db;
    private final Supplier<EmbeddingClient> clientSupplier;

    public EmbeddingIndexService(JworkDatabase db, Supplier<EmbeddingClient> clientSupplier) {
        this.db = db;
        this.clientSupplier = clientSupplier;
    }

    /** 检索命中：所属文档、块序、原文、融合相关度（0..1，向量语义为主 + 关键词覆盖校正）。 */
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
            db.settingDao().delete(fpKey(agentCode, docName));
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
        db.settingDao().upsert(fpKey(agentCode, docName), fingerprint(text, client),
                "知识文档索引指纹（增量重建跳过依据，内部键）");
        log.info("向量索引完成: agent={}, doc={}, chunks={}", agentCode, docName, chunks.size());
        return chunks.size();
    }

    /**
     * 文档是否已按当前内容与当前嵌入配置索引过（增量重建的跳过依据）。
     * 未配置 embedding 一律返回 {@code false}，由调用方给出明确提示。
     */
    public boolean isUpToDate(String agentCode, String docName, String text) {
        EmbeddingClient client = currentClient();
        if (client == null) {
            return false;
        }
        return db.settingDao().getValue(fpKey(agentCode, docName))
                .map(fp -> fp.equals(fingerprint(text, client)))
                .orElse(false);
    }

    /** 删除某份文档的向量块（文件删除联动），指纹一并清理。 */
    public void removeDocument(String agentCode, String docName) {
        db.knowledgeChunkDao().deleteDoc(agentCode, docName);
        db.settingDao().delete(fpKey(agentCode, docName));
    }

    /** 删除某智能体的全部向量块与索引指纹（智能体删除联动）。 */
    public void removeAgent(String agentCode) {
        db.knowledgeChunkDao().deleteByAgent(agentCode);
        db.settingDao().deleteByPrefix(FP_PREFIX + agentCode + "/");
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
     * 混合检索该智能体的知识块：向量余弦（负值截零）+ 关键词覆盖分加权融合，取 topK。
     * <p>纯语义能命中表述不同但意义相近的内容，关键词覆盖则校正「字面命中却被向量稀释」的块
     * （如专有名词、代码标识符）；未配置 embedding 时返回空列表；维度不符的旧块跳过
     * （换过 embedding 模型后重建索引即可，指纹机制会自动触发重建）。</p>
     */
    public List<SearchHit> search(String agentCode, String query, int topK) {
        EmbeddingClient client = currentClient();
        if (client == null || query == null || query.isBlank()) {
            return List.of();
        }
        float[] qv = client.embed(query);
        List<String> terms = queryTerms(query);
        int k = topK <= 0 ? DEFAULT_TOP_K : topK;
        List<SearchHit> hits = new ArrayList<>();
        for (KnowledgeChunk c : db.knowledgeChunkDao().findByAgent(agentCode)) {
            Double sim = cosine(qv, c.getEmbedding());
            if (sim == null) {
                continue;
            }
            double fused = VECTOR_WEIGHT * Math.max(0, sim)
                    + KEYWORD_WEIGHT * keywordScore(c.getContent(), terms);
            hits.add(new SearchHit(c.getDocName(), c.getChunkIdx(), c.getContent(), fused));
        }
        hits.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        return hits.size() > k ? hits.subList(0, k) : hits;
    }

    // ---- 增量索引指纹 ----

    /** 指纹键：按「智能体/文档」命名空间存 settings 表，避免动 knowledge_chunks 表结构。 */
    private static String fpKey(String agentCode, String docName) {
        return FP_PREFIX + agentCode + "/" + docName;
    }

    /** 内容指纹：SHA-256(文本 + 嵌入配置身份)——换内容或换模型都会失配而触发重建。 */
    private static String fingerprint(String text, EmbeddingClient client) {
        String raw = (text == null ? "" : text) + "\n" + client.cacheKey();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    // ---- 混合检索打分 ----

    /** 查询分词：拉丁/数字段整词（单字符丢弃）；汉字段切 bigram（短段保留原字），去重。 */
    private static List<String> queryTerms(String query) {
        List<String> terms = new ArrayList<>();
        Matcher m = TERM_PATTERN.matcher(query.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String t = m.group();
            if (isHan(t)) {
                if (t.length() <= 2) {
                    if (!terms.contains(t)) {
                        terms.add(t);
                    }
                } else {
                    for (int i = 0; i + 1 < t.length(); i++) {
                        String bigram = t.substring(i, i + 2);
                        if (!terms.contains(bigram)) {
                            terms.add(bigram);
                        }
                    }
                }
            } else if (t.length() >= 2 && !terms.contains(t)) {
                terms.add(t);
            }
        }
        return terms;
    }

    /** 关键词覆盖分：查询词在块中出现的比例（0..1）。 */
    private static double keywordScore(String content, List<String> terms) {
        if (content == null || content.isEmpty() || terms.isEmpty()) {
            return 0;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        int hit = 0;
        for (String t : terms) {
            if (lower.contains(t)) {
                hit++;
            }
        }
        return (double) hit / terms.size();
    }

    private static boolean isHan(String s) {
        return Character.UnicodeScript.of(s.codePointAt(0)) == Character.UnicodeScript.HAN;
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
