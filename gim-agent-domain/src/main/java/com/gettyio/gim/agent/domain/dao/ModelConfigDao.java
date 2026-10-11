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

import com.gettyio.gim.agent.domain.entity.ModelConfig;
import com.gettyio.gim.agent.domain.persist.SqlRunner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * models 表 DAO
 *
 * @author gogym
 */
public class ModelConfigDao {

    private static final String COLUMNS =
            "id, provider_code, model_code, model_name, priority, cost_per_1k_input, cost_per_1k_output, rate_limit_rpm, rate_limit_tpm, "
                    + "enabled, status, last_test_at, last_test_result, supports_vision, created_at, updated_at";

    private final SqlRunner runner;

    public ModelConfigDao(SqlRunner runner) {
        this.runner = runner;
    }

    public static ModelConfig map(ResultSet rs) throws SQLException {
        ModelConfig m = new ModelConfig();
        m.setId(SqlRunner.getLong(rs, "id"));
        m.setProviderCode(rs.getString("provider_code"));
        m.setModelCode(rs.getString("model_code"));
        m.setModelName(rs.getString("model_name"));
        m.setPriority(SqlRunner.getInt(rs, "priority"));
        m.setCostPer1kInput(SqlRunner.getDouble(rs, "cost_per_1k_input"));
        m.setCostPer1kOutput(SqlRunner.getDouble(rs, "cost_per_1k_output"));
        m.setRateLimitRpm(SqlRunner.getInt(rs, "rate_limit_rpm"));
        m.setRateLimitTpm(SqlRunner.getInt(rs, "rate_limit_tpm"));
        m.setEnabled(SqlRunner.getInt(rs, "enabled"));
        m.setStatus(rs.getString("status"));
        m.setLastTestAt(SqlRunner.getDateTime(rs, "last_test_at"));
        m.setLastTestResult(rs.getString("last_test_result"));
        m.setSupportsVision(SqlRunner.getInt(rs, "supports_vision"));
        m.setCreatedAt(SqlRunner.getDateTime(rs, "created_at"));
        m.setUpdatedAt(SqlRunner.getDateTime(rs, "updated_at"));
        return m;
    }

    public long insert(ModelConfig m) {
        LocalDateTime now = LocalDateTime.now();
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        long id = runner.insert(
                "INSERT INTO models (provider_code, model_code, model_name, priority, cost_per_1k_input, cost_per_1k_output, rate_limit_rpm, "
                        + "rate_limit_tpm, enabled, status, last_test_at, last_test_result, supports_vision, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                m.getProviderCode(), m.getModelCode(), m.getModelName(), m.getPriority(),
                m.getCostPer1kInput(), m.getCostPer1kOutput(),
                m.getRateLimitRpm(), m.getRateLimitTpm(), m.getEnabled(), m.getStatus(), m.getLastTestAt(),
                m.getLastTestResult(), m.getSupportsVision(), now, now);
        m.setId(id);
        return id;
    }

    public int update(ModelConfig m) {
        m.setUpdatedAt(LocalDateTime.now());
        return runner.update(
                "UPDATE models SET provider_code=?, model_name=?, priority=?, cost_per_1k_input=?, cost_per_1k_output=?, rate_limit_rpm=?, "
                        + "rate_limit_tpm=?, enabled=?, status=?, last_test_at=?, last_test_result=?, supports_vision=?, updated_at=? WHERE model_code=?",
                m.getProviderCode(), m.getModelName(), m.getPriority(),
                m.getCostPer1kInput(), m.getCostPer1kOutput(),
                m.getRateLimitRpm(), m.getRateLimitTpm(), m.getEnabled(), m.getStatus(), m.getLastTestAt(), m.getLastTestResult(),
                m.getSupportsVision(), m.getUpdatedAt(), m.getModelCode());
    }

    public Optional<ModelConfig> findByCode(String modelCode) {
        return runner.queryOne("SELECT " + COLUMNS + " FROM models WHERE model_code=?", ModelConfigDao::map, modelCode);
    }

    public List<ModelConfig> findAll() {
        return runner.query("SELECT " + COLUMNS + " FROM models ORDER BY priority ASC, id ASC", ModelConfigDao::map);
    }

    public List<ModelConfig> findEnabledByProvider(String providerCode) {
        return runner.query("SELECT " + COLUMNS + " FROM models WHERE provider_code=? AND enabled=1 ORDER BY priority ASC",
                ModelConfigDao::map, providerCode);
    }

    public int deleteByCode(String modelCode) {
        return runner.update("DELETE FROM models WHERE model_code=?", modelCode);
    }
}
