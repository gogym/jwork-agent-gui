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

import com.gettyio.jwork.agent.domain.entity.ToolPermissionRule;
import com.gettyio.jwork.agent.domain.persist.SqlRunner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * tool_permission_rules 表 DAO
 *
 * @author gogym
 */
public class ToolPermissionRuleDao {

    private static final String COLUMNS = "id, tool_name, rule_content, decision, created_at, expires_at";

    private final SqlRunner runner;

    public ToolPermissionRuleDao(SqlRunner runner) {
        this.runner = runner;
    }

    public static ToolPermissionRule map(ResultSet rs) throws SQLException {
        ToolPermissionRule r = new ToolPermissionRule();
        r.setId(SqlRunner.getLong(rs, "id"));
        r.setToolName(rs.getString("tool_name"));
        r.setRuleContent(rs.getString("rule_content"));
        r.setDecision(rs.getString("decision"));
        r.setCreatedAt(SqlRunner.getDateTime(rs, "created_at"));
        r.setExpiresAt(SqlRunner.getDateTime(rs, "expires_at"));
        return r;
    }

    /**
     * 记录或更新某工具（+可选规则内容）的决策。
     * <p>{@code expiresAt} 由调用方决定：工具级通配带有效期（{@code GuardChain.TOOL_LEVEL_TTL}），
     * 窄规则传 null 长期有效。 重复点击「总是允许」走更新分支并<b>重写到期时间</b>——那是用户当场又确认了一次，
     * 若沿用建行时的 created_at 来推算，一个天天在用的工具会因为第一次点击太久前而反复弹框。</p>
     *
     * @param ruleContent 规则内容；null 表示整个工具级
     * @param expiresAt   失效时刻，null 表示不失效
     */
    public void upsert(String toolName, String ruleContent, String decision, LocalDateTime expiresAt) {
        Optional<ToolPermissionRule> existing = find(toolName, ruleContent);
        if (existing.isPresent()) {
            runner.update("UPDATE tool_permission_rules SET decision=?, expires_at=? WHERE id=?",
                    decision, expiresAt, existing.get().getId());
        } else {
            runner.insert("INSERT INTO tool_permission_rules (tool_name, rule_content, decision, created_at, expires_at) "
                            + "VALUES (?,?,?,?,?)",
                    toolName, ruleContent, decision, LocalDateTime.now(), expiresAt);
        }
    }

    /** 按工具名 + 规则内容查找；{@code ruleContent} 为 null 时匹配 NULL 行；不存在返回空。 */
    public Optional<ToolPermissionRule> find(String toolName, String ruleContent) {
        if (ruleContent == null) {
            return runner.queryOne("SELECT " + COLUMNS + " FROM tool_permission_rules WHERE tool_name=? AND rule_content IS NULL",
                    ToolPermissionRuleDao::map, toolName);
        }
        return runner.queryOne("SELECT " + COLUMNS + " FROM tool_permission_rules WHERE tool_name=? AND rule_content=?",
                ToolPermissionRuleDao::map, toolName, ruleContent);
    }

    public List<ToolPermissionRule> findAll() {
        return runner.query("SELECT " + COLUMNS + " FROM tool_permission_rules ORDER BY id", ToolPermissionRuleDao::map);
    }

    /** 某工具的全部规则（含命令/目录级）：拦截链据此判断本次调用是否已被「总是允许」覆盖。 */
    public List<ToolPermissionRule> findByTool(String toolName) {
        return runner.query("SELECT " + COLUMNS + " FROM tool_permission_rules WHERE tool_name=? ORDER BY id",
                ToolPermissionRuleDao::map, toolName);
    }

    public int delete(Long id) {
        return runner.update("DELETE FROM tool_permission_rules WHERE id=?", id);
    }
}
