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

import com.gettyio.gim.agent.domain.entity.ModelProviderConfig;
import com.gettyio.gim.agent.domain.persist.SqlRunner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * model_providers 表 DAO
 *
 * @author gogym
 */
public class ModelProviderDao {

    private static final String COLUMNS =
            "id, provider_code, provider_name, api_protocol, base_url, api_key, auth_method, enabled, remark, created_at, updated_at";

    private final SqlRunner runner;

    public ModelProviderDao(SqlRunner runner) {
        this.runner = runner;
    }

    public static ModelProviderConfig map(ResultSet rs) throws SQLException {
        ModelProviderConfig p = new ModelProviderConfig();
        p.setId(SqlRunner.getLong(rs, "id"));
        p.setProviderCode(rs.getString("provider_code"));
        p.setProviderName(rs.getString("provider_name"));
        p.setApiProtocol(rs.getString("api_protocol"));
        p.setBaseUrl(rs.getString("base_url"));
        p.setApiKey(rs.getString("api_key"));
        p.setAuthMethod(rs.getString("auth_method"));
        p.setEnabled(SqlRunner.getInt(rs, "enabled"));
        p.setRemark(rs.getString("remark"));
        p.setCreatedAt(SqlRunner.getDateTime(rs, "created_at"));
        p.setUpdatedAt(SqlRunner.getDateTime(rs, "updated_at"));
        return p;
    }

    public long insert(ModelProviderConfig p) {
        LocalDateTime now = LocalDateTime.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        long id = runner.insert(
                "INSERT INTO model_providers (provider_code, provider_name, api_protocol, base_url, api_key, auth_method, enabled, remark, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                p.getProviderCode(), p.getProviderName(), p.getApiProtocol(), p.getBaseUrl(), p.getApiKey(),
                p.getAuthMethod(), p.getEnabled(), p.getRemark(), now, now);
        p.setId(id);
        return id;
    }

    public int update(ModelProviderConfig p) {
        p.setUpdatedAt(LocalDateTime.now());
        return runner.update(
                "UPDATE model_providers SET provider_name=?, api_protocol=?, base_url=?, api_key=?, auth_method=?, enabled=?, remark=?, updated_at=? "
                        + "WHERE provider_code=?",
                p.getProviderName(), p.getApiProtocol(), p.getBaseUrl(), p.getApiKey(), p.getAuthMethod(),
                p.getEnabled(), p.getRemark(), p.getUpdatedAt(), p.getProviderCode());
    }

    public Optional<ModelProviderConfig> findByCode(String providerCode) {
        return runner.queryOne("SELECT " + COLUMNS + " FROM model_providers WHERE provider_code=?",
                ModelProviderDao::map, providerCode);
    }

    public List<ModelProviderConfig> findAll() {
        return runner.query("SELECT " + COLUMNS + " FROM model_providers ORDER BY id", ModelProviderDao::map);
    }

    public int deleteByCode(String providerCode) {
        return runner.update("DELETE FROM model_providers WHERE provider_code=?", providerCode);
    }
}
