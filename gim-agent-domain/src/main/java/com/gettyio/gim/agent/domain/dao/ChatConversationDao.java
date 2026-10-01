package com.gettyio.gim.agent.domain.dao;

import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.entity.UsageAggregate;
import com.gettyio.gim.agent.domain.enums.TimeRange;
import com.gettyio.gim.agent.domain.persist.SqlRunner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * conversations 表 DAO
 */
public class ChatConversationDao {

    private static final String COLUMNS =
            "id, session_id, agent_code, role, content, tokens_input, tokens_output, tokens_cached, llm_calls, llm_elapsed_ms, model_code, tool_calls, metadata, created_at";

    private final SqlRunner runner;

    public ChatConversationDao(SqlRunner runner) {
        this.runner = runner;
    }

    public static ChatConversation map(ResultSet rs) throws SQLException {
        ChatConversation c = new ChatConversation();
        c.setId(SqlRunner.getLong(rs, "id"));
        c.setSessionId(rs.getString("session_id"));
        c.setAgentCode(rs.getString("agent_code"));
        c.setRole(rs.getString("role"));
        c.setContent(rs.getString("content"));
        c.setTokensInput(SqlRunner.getInt(rs, "tokens_input"));
        c.setTokensOutput(SqlRunner.getInt(rs, "tokens_output"));
        c.setTokensCached(SqlRunner.getInt(rs, "tokens_cached"));
        c.setLlmCalls(SqlRunner.getInt(rs, "llm_calls"));
        c.setLlmElapsedMillis(SqlRunner.getLong(rs, "llm_elapsed_ms"));
        c.setModelCode(rs.getString("model_code"));
        c.setToolCalls(rs.getString("tool_calls"));
        c.setMetadata(rs.getString("metadata"));
        c.setCreatedAt(SqlRunner.getDateTime(rs, "created_at"));
        return c;
    }

    public long insert(ChatConversation c) {
        if (c.getCreatedAt() == null) {
            c.setCreatedAt(LocalDateTime.now());
        }
        long id = runner.insert(
                "INSERT INTO conversations (session_id, agent_code, role, content, tokens_input, tokens_output, tokens_cached, llm_calls, llm_elapsed_ms, model_code, tool_calls, metadata, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                c.getSessionId(), c.getAgentCode(), c.getRole(), c.getContent(),
                c.getTokensInput(), c.getTokensOutput(), c.getTokensCached(),
                c.getLlmCalls(), c.getLlmElapsedMillis(),
                c.getModelCode(), c.getToolCalls(), c.getMetadata(), c.getCreatedAt());
        c.setId(id);
        return id;
    }

    public List<ChatConversation> findBySession(String sessionId) {
        return runner.query("SELECT " + COLUMNS + " FROM conversations WHERE session_id=? ORDER BY id ASC",
                ChatConversationDao::map, sessionId);
    }

    public int deleteBySession(String sessionId) {
        return runner.update("DELETE FROM conversations WHERE session_id=?", sessionId);
    }

    // ---- token 用量聚合（实时 SUM + 按模型单价估算成本，四维度共用一套语句模板） ----

    /**
     * 聚合列：token 合计 + 消息数 + 估算成本。成本用 LEFT JOIN models 取行对应模型单价；
     * 单价缺失（未配或未标注模型）的行按 0 计入，不拉高总价。cached 仅子集信息，不单独计费。
     */
    private static final String SUM_COLS =
            "COALESCE(SUM(c.tokens_input), 0)  AS tokens_input,"
                    + " COALESCE(SUM(c.tokens_output), 0) AS tokens_output,"
                    + " COALESCE(SUM(c.tokens_cached), 0) AS tokens_cached,"
                    + " COALESCE(SUM(c.llm_calls), 0) AS llm_calls,"
                    + " COALESCE(SUM(c.llm_elapsed_ms), 0) AS llm_elapsed_ms,"
                    + " COUNT(*) AS message_count,"
                    + " COALESCE(SUM(COALESCE(CAST(c.tokens_input AS REAL), 0) / 1000.0 * COALESCE(m.cost_per_1k_input, 0)"
                    + " + COALESCE(CAST(c.tokens_output AS REAL), 0) / 1000.0 * COALESCE(m.cost_per_1k_output, 0)), 0) AS est_cost";

    /** 聚合查询统一从 conversations LEFT JOIN models（按 model_code 关联取单价）。 */
    private static final String FROM_JOIN =
            " FROM conversations c LEFT JOIN models m ON c.model_code = m.model_code";

    /**
     * 时间窗下界谓词：{@code ALL} 返回空串（不加过滤）；否则按 {@code datetime()} 归一化后比较，
     * 规避 {@code created_at} 变长字符串的边界误判。
     */
    private static String timeWhere(TimeRange range) {
        return range == null || range.since().isEmpty() ? "" : " WHERE datetime(c.created_at) >= datetime(?)";
    }

    /** 时间窗下界参数：无过滤时为空数组，有下界时携带该时间戳。 */
    private static Object[] timeParam(TimeRange range) {
        return range == null || range.since().isEmpty() ? new Object[0] : new Object[]{range.since().get()};
    }

    /** 全局汇总（按时间窗）。 */
    public UsageAggregate aggregateGlobal(TimeRange range) {
        return runner.queryOne(
                "SELECT 'ALL' AS group_key, " + SUM_COLS + FROM_JOIN + timeWhere(range),
                rs -> mapAggregate(rs, "group_key"), timeParam(range))
                .orElseGet(() -> UsageAggregate.empty("ALL"));
    }

    /** 按维度分组的用量汇总（按时间窗）；{@code column} 必须是 agent_code / model_code / session_id 之一。 */
    public List<UsageAggregate> aggregateBy(String column, TimeRange range) {
        String col = requireDimColumn(column);
        return runner.query(
                "SELECT c." + col + " AS group_key, " + SUM_COLS + FROM_JOIN + timeWhere(range)
                        + " GROUP BY c." + col
                        + " ORDER BY COALESCE(SUM(c.tokens_input), 0) + COALESCE(SUM(c.tokens_output), 0) DESC",
                rs -> mapAggregate(rs, "group_key"), timeParam(range));
    }

    /** 单个会话的用量汇总。 */
    public UsageAggregate aggregateBySession(String sessionId) {
        return runner.queryOne(
                "SELECT ? AS group_key, " + SUM_COLS + FROM_JOIN + " WHERE c.session_id=?",
                rs -> mapAggregate(rs, "group_key"), sessionId, sessionId)
                .orElseGet(() -> UsageAggregate.empty(sessionId));
    }

    /** 单个智能体的用量汇总。 */
    public UsageAggregate aggregateByAgent(String agentCode) {
        return runner.queryOne(
                "SELECT ? AS group_key, " + SUM_COLS + FROM_JOIN + " WHERE c.agent_code=?",
                rs -> mapAggregate(rs, "group_key"), agentCode, agentCode)
                .orElseGet(() -> UsageAggregate.empty(agentCode));
    }

    private static UsageAggregate mapAggregate(ResultSet rs, String keyColumn) throws SQLException {
        return new UsageAggregate(
                rs.getString(keyColumn),
                rs.getInt("tokens_input"),
                rs.getInt("tokens_output"),
                rs.getInt("tokens_cached"),
                rs.getInt("llm_calls"),
                rs.getLong("llm_elapsed_ms"),
                rs.getInt("message_count"),
                rs.getDouble("est_cost"));
    }

    /** 白名单：分组列只允许这几个真实列名，杜绝拼接注入。 */
    private static String requireDimColumn(String column) {
        switch (column) {
            case "agent_code":
            case "model_code":
            case "session_id":
                return column;
            default:
                throw new IllegalArgumentException("非法的用量聚合维度列: " + column);
        }
    }
}
