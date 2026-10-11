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

import com.gettyio.gim.agent.domain.entity.PromptTemplate;
import com.gettyio.gim.agent.domain.persist.SqlRunner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * prompt_templates 表 DAO：提示词模板的增删改查与启停。
 * <p>{@code enabled} 以 0/1 存储；{@link #findEnabled()} 供聊天输入区的「模板」下拉只列启用项。</p>
 *
 * @author gogym
 */
public class PromptTemplateDao {

    private static final String COLUMNS =
            "id, template_code, template_name, content, description, enabled, created_at, updated_at";

    private final SqlRunner runner;

    public PromptTemplateDao(SqlRunner runner) {
        this.runner = runner;
    }

    public static PromptTemplate map(ResultSet rs) throws SQLException {
        PromptTemplate t = new PromptTemplate();
        t.setId(SqlRunner.getLong(rs, "id"));
        t.setTemplateCode(rs.getString("template_code"));
        t.setTemplateName(rs.getString("template_name"));
        t.setContent(rs.getString("content"));
        t.setDescription(rs.getString("description"));
        t.setEnabled(SqlRunner.getInt(rs, "enabled"));
        t.setCreatedAt(SqlRunner.getDateTime(rs, "created_at"));
        t.setUpdatedAt(SqlRunner.getDateTime(rs, "updated_at"));
        return t;
    }

    /** 插入模板并回填主键；时间戳取当前时间，{@code enabled} 为空默认启用（1）。 */
    public long insert(PromptTemplate t) {
        LocalDateTime now = LocalDateTime.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        if (t.getEnabled() == null) {
            t.setEnabled(1);
        }
        long id = runner.insert(
                "INSERT INTO prompt_templates (template_code, template_name, content, description, enabled, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                t.getTemplateCode(), t.getTemplateName(), t.getContent(), t.getDescription(), t.getEnabled(), now, now);
        t.setId(id);
        return id;
    }

    public int update(PromptTemplate t) {
        t.setUpdatedAt(LocalDateTime.now());
        return runner.update(
                "UPDATE prompt_templates SET template_name=?, content=?, description=?, enabled=?, updated_at=? WHERE template_code=?",
                t.getTemplateName(), t.getContent(), t.getDescription(), t.getEnabled(), t.getUpdatedAt(), t.getTemplateCode());
    }

    public Optional<PromptTemplate> findByCode(String templateCode) {
        return runner.queryOne("SELECT " + COLUMNS + " FROM prompt_templates WHERE template_code=?",
                PromptTemplateDao::map, templateCode);
    }

    public List<PromptTemplate> findAll() {
        return runner.query("SELECT " + COLUMNS + " FROM prompt_templates ORDER BY id", PromptTemplateDao::map);
    }

    /** 启用中的模板（聊天输入区下拉用），按插入序。 */
    public List<PromptTemplate> findEnabled() {
        return runner.query("SELECT " + COLUMNS + " FROM prompt_templates WHERE enabled=? ORDER BY id",
                PromptTemplateDao::map, 1);
    }

    public int setEnabled(String templateCode, boolean enabled) {
        return runner.update("UPDATE prompt_templates SET enabled=?, updated_at=? WHERE template_code=?",
                enabled ? 1 : 0, LocalDateTime.now(), templateCode);
    }

    public int deleteByCode(String templateCode) {
        return runner.update("DELETE FROM prompt_templates WHERE template_code=?", templateCode);
    }
}
