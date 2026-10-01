package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.entity.ModelConfig;
import com.gettyio.gim.agent.domain.entity.UsageAggregate;
import com.gettyio.gim.agent.domain.enums.TimeRange;

import java.util.ArrayList;
import java.util.List;

/**
 * token 用量统计服务：把 {@link UsageAggregate} 聚合行按维度补上展示名，供用量页渲染。
 * <p>全局 / 智能体 / 模型 / 会话四个维度全部走实时 SUM（数据量级为桌面单机，够用且零一致性问题）。</p>
 */
public class UsageStatsService {

    private final GimDatabase db;

    public UsageStatsService(GimDatabase db) {
        this.db = db;
    }

    /** 全局用量（单行）。 */
    public UsageRow global(TimeRange range) {
        return toRow(db.conversationDao().aggregateGlobal(range), UsageRow::globalName);
    }

    /** 按智能体维度分组（按总 token 降序）。 */
    public List<UsageRow> byAgent(TimeRange range) {
        List<UsageRow> rows = new ArrayList<>();
        for (UsageAggregate a : db.conversationDao().aggregateBy("agent_code", range)) {
            rows.add(toRow(a, this::agentName));
        }
        return rows;
    }

    /** 按模型维度分组（按总 token 降序）。 */
    public List<UsageRow> byModel(TimeRange range) {
        List<UsageRow> rows = new ArrayList<>();
        for (UsageAggregate a : db.conversationDao().aggregateBy("model_code", range)) {
            rows.add(toRow(a, this::modelName));
        }
        return rows;
    }

    /** 按会话维度分组（按总 token 降序）。 */
    public List<UsageRow> bySession(TimeRange range) {
        List<UsageRow> rows = new ArrayList<>();
        for (UsageAggregate a : db.conversationDao().aggregateBy("session_id", range)) {
            rows.add(toRow(a, this::sessionName));
        }
        return rows;
    }

    private UsageRow toRow(UsageAggregate a, NameResolver resolver) {
        return new UsageRow(a.groupKey(), resolver.name(a.groupKey()),
                a.tokensInput(), a.tokensOutput(), a.tokensCached(),
                a.totalTokens(), a.llmCalls(), a.llmElapsedMillis(), a.messageCount(), a.estCost());
    }

    private String agentName(String code) {
        if (code == null || code.isBlank()) {
            return UsageRow.UNTAGGED;
        }
        return db.agentDao().findByCode(code).map(AgentDefinition::getAgentName)
                .filter(n -> n != null && !n.isBlank()).orElse(code);
    }

    private String modelName(String code) {
        if (code == null || code.isBlank()) {
            return UsageRow.UNTAGGED;
        }
        return db.modelConfigDao().findByCode(code).map(ModelConfig::getModelName)
                .filter(n -> n != null && !n.isBlank()).orElse(code);
    }

    private String sessionName(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UsageRow.UNTAGGED;
        }
        return db.sessionDao().findBySessionId(sessionId).map(ChatSession::getTitle)
                .filter(t -> t != null && !t.isBlank()).orElse(sessionId);
    }

    /** 展示名解析器：把分组键翻译成人类可读名称。 */
    @FunctionalInterface
    private interface NameResolver {
        String name(String groupKey);
    }

    /**
     * 用量行（维度表格的一行）。
     *
     * @param code             分组键（全局为 {@code "ALL"}）
     * @param name             展示名
     * @param tokensInput      输入 token 合计
     * @param tokensOutput     输出 token 合计
     * @param tokensCached     缓存命中输入 token 合计
     * @param totalTokens      输入 + 输出
     * @param llmCalls         LLM 调用次数合计
     * @param llmElapsedMillis 模型调用累计耗时（毫秒）
     * @param messageCount     参与统计的消息数
     * @param estCost          估算成本（未配单价部分按 0 计）
     */
    public record UsageRow(String code, String name, int tokensInput, int tokensOutput,
                           int tokensCached, int totalTokens, int llmCalls, long llmElapsedMillis,
                           int messageCount, double estCost) {

        /** 未标注维度键的展示占位（视图按语言本地化，这里用可识别标记）。 */
        public static final String UNTAGGED = "\u0000UNTAGGED";

        static String globalName(String ignored) {
            return "ALL";
        }
    }
}
