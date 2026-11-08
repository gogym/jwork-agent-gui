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

package com.gettyio.gim.agent.core.knowledge;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.List;
import java.util.function.Supplier;

/**
 * 面向 Agent 的知识检索工具：{@code knowledge_search}——对当前智能体的知识库做混合检索
 * （向量语义 + 关键词覆盖率加权），返回最相关的若干知识块原文与出处。
 * <p>与 KNOWLEDGE.md 文件树路线并存：先混合召回相关片段，需要完整上下文再按 doc_name 用
 * {@code read_file}/{@code grep_files} 读原文。embedding 未配置时返回明确提示（引导去知识页配置），
 * 而非抛异常打断对话。每个智能体一份实例（agentCode 经网关注入）。</p>
 *
 * @author gogym
 */
public class KnowledgeSearchTools {

    /** 返回片段的单块截断（字符）：向量块本身有限，兜底防个别超大块撑上下文。 */
    private static final int CONTENT_CHARS = 800;

    private final EmbeddingIndexService indexer;
    private final Supplier<String> agentCodeSupplier;

    public KnowledgeSearchTools(EmbeddingIndexService indexer, Supplier<String> agentCodeSupplier) {
        this.indexer = indexer;
        this.agentCodeSupplier = agentCodeSupplier;
    }

    /** 工具入口（{@code knowledge_search}）：混合检索当前智能体知识库，返回带出处（文档/块号/得分）的片段；未配置、无命中或异常均以文本返回，不打断对话。 */
    @Tool(name = "knowledge_search", readOnly = true,
            description = "在当前智能体的知识库中做混合检索（向量语义 + 关键词），返回最相关的知识片段及出处文档名。"
                    + "适合在 KNOWLEDGE.md 目录看不出该读哪个文件、或问题需要分散在多份资料里的细节时使用；"
                    + "需要完整上下文时再用 read_file/grep_files 按出处文件名读原文。")
    public String knowledgeSearch(
            @ToolParam(name = "query", description = "要检索的问题或关键词（语义自然表达即可）") String query,
            @ToolParam(name = "top_k", required = false,
                    description = "返回片段条数，1-10，默认 5") Integer topK) {
        if (query == null || query.isBlank()) {
            return "query 不能为空。";
        }
        if (!indexer.isConfigured()) {
            return "向量知识索引未配置：请到「拓展 > 知识」页填写 embedding 提供方（Base URL / 模型 / API Key）后重建索引。";
        }
        int k = topK == null ? 5 : Math.max(1, Math.min(10, topK));
        try {
            List<EmbeddingIndexService.SearchHit> hits = indexer.search(agentCodeSupplier.get(), query.trim(), k);
            if (hits.isEmpty()) {
                return "知识库没有已索引的内容（或尚未重建索引）。可在「拓展 > 知识」页导入资料并重建索引。";
            }
            StringBuilder sb = new StringBuilder("【知识检索】").append(query.trim()).append('\n');
            int i = 1;
            for (EmbeddingIndexService.SearchHit hit : hits) {
                String content = hit.content();
                if (content.length() > CONTENT_CHARS) {
                    content = content.substring(0, CONTENT_CHARS) + "…";
                }
                sb.append('\n').append(i++).append(". 出处：").append(hit.docName())
                        .append("（块 ").append(hit.chunkIdx()).append("，相关度 ")
                        .append(String.format(java.util.Locale.ROOT, "%.3f", hit.score())).append("）\n")
                        .append(content.replace("\n\n", "\n")).append('\n');
            }
            return sb.toString().strip();
        } catch (RuntimeException e) {
            return "知识检索失败：" + e.getMessage();
        }
    }
}
