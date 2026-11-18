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

package com.gettyio.jwork.agent.domain.dao;

import com.gettyio.jwork.agent.domain.entity.ChatConversation;
import com.gettyio.jwork.agent.domain.enums.ConversationRole;
import com.gettyio.jwork.agent.domain.persist.SqlRunner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code turn_drafts} 表 DAO：在途助手应答的检查点。
 * <p>每会话最多一条（{@code session_id} 为主键），列为 conversations 的同构子集，
 * 因此 {@link #save} 收的就是那行尚未定稿的 {@link ChatConversation}，重启提升时原样搬过去即可。</p>
 * <p>草稿只服务于崩溃/被杀恢复：正常收尾由 {@code persistAssistant} 与删草稿同事务完成，
 * 库里不会留下已定稿轮次的残稿。</p>
 *
 * @author gogym
 */
public class TurnDraftDao {

    private static final String COLUMNS = "session_id, agent_code, content, tokens_input, tokens_output, tokens_cached, "
            + "last_input_tokens, llm_calls, llm_elapsed_ms, model_code, tool_calls, metadata, created_at";

    private final SqlRunner runner;

    public TurnDraftDao(SqlRunner runner) {
        this.runner = runner;
    }

    /** 覆盖式写入某会话的在途应答；{@code row.sessionId} 为空则忽略（没有会话就无处挂这半截）。 */
    public void save(ChatConversation row) {
        if (row == null || row.getSessionId() == null || row.getSessionId().isBlank()) {
            return;
        }
        runner.update("INSERT INTO turn_drafts (" + COLUMNS + ", updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(session_id) DO UPDATE SET agent_code=excluded.agent_code, content=excluded.content, "
                        + "tokens_input=excluded.tokens_input, tokens_output=excluded.tokens_output, "
                        + "tokens_cached=excluded.tokens_cached, last_input_tokens=excluded.last_input_tokens, "
                        + "llm_calls=excluded.llm_calls, llm_elapsed_ms=excluded.llm_elapsed_ms, "
                        + "model_code=excluded.model_code, tool_calls=excluded.tool_calls, metadata=excluded.metadata, "
                        + "created_at=excluded.created_at, updated_at=excluded.updated_at",
                row.getSessionId(), row.getAgentCode(), row.getContent(),
                row.getTokensInput(), row.getTokensOutput(), row.getTokensCached(),
                row.getLastInputTokens(), row.getLlmCalls(), row.getLlmElapsedMillis(),
                row.getModelCode(), row.getToolCalls(), row.getMetadata(),
                row.getCreatedAt() == null ? LocalDateTime.now() : row.getCreatedAt(), LocalDateTime.now());
    }

    /** 待提升的全部在途应答（按会话定序，返回的角色固定为助手）；供启动期恢复使用。 */
    public List<ChatConversation> findAll() {
        return runner.query("SELECT " + COLUMNS + " FROM turn_drafts ORDER BY session_id ASC",
                TurnDraftDao::mapAssistant);
    }

    /** 丢弃某会话的在途应答（收尾落盘的同事务里调用）。 */
    public int delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        return runner.update("DELETE FROM turn_drafts WHERE session_id=?", sessionId);
    }

    private static ChatConversation mapAssistant(ResultSet rs) throws SQLException {
        ChatConversation c = new ChatConversation();
        c.setSessionId(rs.getString("session_id"));
        c.setAgentCode(rs.getString("agent_code"));
        c.setRole(ConversationRole.ASSISTANT.getCode());
        c.setContent(rs.getString("content"));
        c.setTokensInput(SqlRunner.getInt(rs, "tokens_input"));
        c.setTokensOutput(SqlRunner.getInt(rs, "tokens_output"));
        c.setTokensCached(SqlRunner.getInt(rs, "tokens_cached"));
        c.setLastInputTokens(SqlRunner.getInt(rs, "last_input_tokens"));
        c.setLlmCalls(SqlRunner.getInt(rs, "llm_calls"));
        c.setLlmElapsedMillis(SqlRunner.getLong(rs, "llm_elapsed_ms"));
        c.setModelCode(rs.getString("model_code"));
        c.setToolCalls(rs.getString("tool_calls"));
        c.setMetadata(rs.getString("metadata"));
        c.setCreatedAt(SqlRunner.getDateTime(rs, "created_at"));
        return c;
    }
}
