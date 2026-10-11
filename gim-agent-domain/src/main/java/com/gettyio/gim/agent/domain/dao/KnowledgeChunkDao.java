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
import com.gettyio.gim.agent.domain.persist.SqlRunner;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * knowledge_chunks 表 DAO：向量知识块的整文档替换式写入与按智能体读取。
 * <p>嵌入向量以小端 float 序列存 BLOB；个人规模知识库（万级块内）直接全量载入内存做余弦检索，
 * 无需引入向量索引扩展。</p>
 *
 * @author gogym
 */
public class KnowledgeChunkDao {

    private static final String COLUMNS = "id, agent_code, doc_name, chunk_idx, content, embedding, created_at";

    private final SqlRunner runner;

    public KnowledgeChunkDao(SqlRunner runner) {
        this.runner = runner;
    }

    private static KnowledgeChunk map(ResultSet rs) throws SQLException {
        KnowledgeChunk c = new KnowledgeChunk();
        c.setId(SqlRunner.getLong(rs, "id"));
        c.setAgentCode(rs.getString("agent_code"));
        c.setDocName(rs.getString("doc_name"));
        c.setChunkIdx(SqlRunner.getInt(rs, "chunk_idx"));
        c.setContent(rs.getString("content"));
        c.setEmbedding(toFloats(rs.getBytes("embedding")));
        c.setCreatedAt(SqlRunner.getDateTime(rs, "created_at"));
        return c;
    }

    /** 整文档替换：先删该文档旧块，再按序写入新块（导入/重建索引共用）。 */
    public void replaceDoc(String agentCode, String docName, List<KnowledgeChunk> chunks) {
        deleteDoc(agentCode, docName);
        LocalDateTime now = LocalDateTime.now();
        for (KnowledgeChunk c : chunks) {
            runner.update("INSERT INTO knowledge_chunks (agent_code, doc_name, chunk_idx, content, embedding, created_at) "
                            + "VALUES (?,?,?,?,?,?)",
                    agentCode, docName, c.getChunkIdx(), c.getContent(), toBytes(c.getEmbedding()), now);
        }
    }

    /** 某智能体的全部向量块（按文档名、块序稳定排列）。 */
    public List<KnowledgeChunk> findByAgent(String agentCode) {
        return runner.query("SELECT " + COLUMNS + " FROM knowledge_chunks WHERE agent_code=? "
                + "ORDER BY doc_name, chunk_idx", KnowledgeChunkDao::map, agentCode);
    }

    /** 删除某智能体某份文档的全部向量块。 */
    public int deleteDoc(String agentCode, String docName) {
        return runner.update("DELETE FROM knowledge_chunks WHERE agent_code=? AND doc_name=?", agentCode, docName);
    }

    /** 删除某智能体的全部向量块（智能体删除联动）。 */
    public int deleteByAgent(String agentCode) {
        return runner.update("DELETE FROM knowledge_chunks WHERE agent_code=?", agentCode);
    }

    /** 某智能体已索引的文档数。 */
    public int countDocs(String agentCode) {
        return runner.queryOne("SELECT COUNT(DISTINCT doc_name) AS n FROM knowledge_chunks WHERE agent_code=?",
                rs -> rs.getInt("n"), agentCode).orElse(0);
    }

    /** 某智能体的向量块总数。 */
    public int countChunks(String agentCode) {
        return runner.queryOne("SELECT COUNT(*) AS n FROM knowledge_chunks WHERE agent_code=?",
                rs -> rs.getInt("n"), agentCode).orElse(0);
    }

    // ---- float[] <-> BLOB（小端） ----

    private static byte[] toBytes(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) {
            buf.putFloat(f);
        }
        return buf.array();
    }

    private static float[] toFloats(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return new float[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[blob.length / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.getFloat();
        }
        return out;
    }
}
