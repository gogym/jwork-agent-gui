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

package com.gettyio.gim.agent.domain.entity;

/**
 * 向量知识块（knowledge_chunks 表）：某智能体某份知识文档的一个分块及其嵌入向量。
 * <p>{@code embedding} 以 float 数组形式承载，DAO 层负责与 BLOB（小端 float 序列）互转；
 * 向量维度取决于生成时的 embedding 模型，不做强校验（检索时维度不符的块跳过）。</p>
 *
 * @author gogym
 */
public class KnowledgeChunk {

    private Long id;
    private String agentCode;
    private String docName;
    private Integer chunkIdx;
    private String content;
    private float[] embedding;
    private java.time.LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAgentCode() {
        return agentCode;
    }

    public void setAgentCode(String agentCode) {
        this.agentCode = agentCode;
    }

    public String getDocName() {
        return docName;
    }

    public void setDocName(String docName) {
        this.docName = docName;
    }

    public Integer getChunkIdx() {
        return chunkIdx;
    }

    public void setChunkIdx(Integer chunkIdx) {
        this.chunkIdx = chunkIdx;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public java.time.LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(java.time.LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
