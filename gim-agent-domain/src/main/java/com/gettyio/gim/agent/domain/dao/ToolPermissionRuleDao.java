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

import com.gettyio.gim.agent.domain.entity.ToolPermissionRule;
import com.gettyio.gim.agent.domain.persist.SqlRunner;

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

    private static final String COLUMNS = "id, tool_name, rule_content, decision, created_at";

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
        return r;
    }

    /** 记录或更新某工具（+可选规则内容）的决策 */
    public void upsert(String toolName, String ruleContent, String decision) {
        Optional<ToolPermissionRule> existing = find(toolName, ruleContent);
        if (existing.isPresent()) {
            runner.update("UPDATE tool_permission_rules SET decision=? WHERE id=?", decision, existing.get().getId());
        } else {
            runner.insert("INSERT INTO tool_permission_rules (tool_name, rule_content, decision, created_at) VALUES (?,?,?,?)",
                    toolName, ruleContent, decision, LocalDateTime.now());
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

    public int delete(Long id) {
        return runner.update("DELETE FROM tool_permission_rules WHERE id=?", id);
    }
}
