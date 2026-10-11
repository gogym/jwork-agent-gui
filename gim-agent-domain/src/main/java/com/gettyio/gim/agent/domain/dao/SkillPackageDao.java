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

import com.gettyio.gim.agent.domain.entity.SkillPackage;
import com.gettyio.gim.agent.domain.persist.SqlRunner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * skills 表 DAO
 *
 * @author gogym
 */
public class SkillPackageDao {

    private static final String COLUMNS =
            "id, skill_code, skill_name, description, version, entry_path, source, requires_json, enabled, created_at, updated_at";

    private final SqlRunner runner;

    public SkillPackageDao(SqlRunner runner) {
        this.runner = runner;
    }

    public static SkillPackage map(ResultSet rs) throws SQLException {
        SkillPackage s = new SkillPackage();
        s.setId(SqlRunner.getLong(rs, "id"));
        s.setSkillCode(rs.getString("skill_code"));
        s.setSkillName(rs.getString("skill_name"));
        s.setDescription(rs.getString("description"));
        s.setVersion(rs.getString("version"));
        s.setEntryPath(rs.getString("entry_path"));
        s.setSource(rs.getString("source"));
        s.setRequiresJson(rs.getString("requires_json"));
        s.setEnabled(SqlRunner.getInt(rs, "enabled"));
        s.setCreatedAt(SqlRunner.getDateTime(rs, "created_at"));
        s.setUpdatedAt(SqlRunner.getDateTime(rs, "updated_at"));
        return s;
    }

    public long insert(SkillPackage s) {
        LocalDateTime now = LocalDateTime.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        long id = runner.insert(
                "INSERT INTO skills (skill_code, skill_name, description, version, entry_path, source, requires_json, enabled, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                s.getSkillCode(), s.getSkillName(), s.getDescription(), s.getVersion(), s.getEntryPath(),
                s.getSource(), s.getRequiresJson(), s.getEnabled(), now, now);
        s.setId(id);
        return id;
    }

    public int update(SkillPackage s) {
        s.setUpdatedAt(LocalDateTime.now());
        return runner.update(
                "UPDATE skills SET skill_name=?, description=?, version=?, entry_path=?, source=?, requires_json=?, enabled=?, updated_at=? "
                        + "WHERE skill_code=?",
                s.getSkillName(), s.getDescription(), s.getVersion(), s.getEntryPath(), s.getSource(),
                s.getRequiresJson(), s.getEnabled(), s.getUpdatedAt(), s.getSkillCode());
    }

    public Optional<SkillPackage> findByCode(String skillCode) {
        return runner.queryOne("SELECT " + COLUMNS + " FROM skills WHERE skill_code=?", SkillPackageDao::map, skillCode);
    }

    public List<SkillPackage> findAll() {
        return runner.query("SELECT " + COLUMNS + " FROM skills ORDER BY id", SkillPackageDao::map);
    }

    public int deleteByCode(String skillCode) {
        return runner.update("DELETE FROM skills WHERE skill_code=?", skillCode);
    }
}
