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

import com.gettyio.gim.agent.domain.entity.AppSetting;
import com.gettyio.gim.agent.domain.persist.SqlRunner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * settings 表 DAO
 *
 * @author gogym
 */
public class AppSettingDao {

    private final SqlRunner runner;

    public AppSettingDao(SqlRunner runner) {
        this.runner = runner;
    }

    private static final String COLUMNS = "id, setting_key, setting_value, description, updated_at";

    public static AppSetting map(ResultSet rs) throws SQLException {
        AppSetting s = new AppSetting();
        s.setId(SqlRunner.getLong(rs, "id"));
        s.setSettingKey(rs.getString("setting_key"));
        s.setSettingValue(rs.getString("setting_value"));
        s.setDescription(rs.getString("description"));
        s.setUpdatedAt(SqlRunner.getDateTime(rs, "updated_at"));
        return s;
    }

    public Optional<String> getValue(String key) {
        return runner.queryOne("SELECT setting_value FROM settings WHERE setting_key=?",
                rs -> rs.getString("setting_value"), key);
    }

    /**
     * 幂等写入：一条 {@code INSERT ... ON CONFLICT DO UPDATE} 同时覆盖值/描述/时间。
     * <p>不用「先 UPDATE，影响 0 行再 INSERT」：两条语句各开连接时既不原子（中途失败键可能整个消失），
     * 也会给同一次写入记下两个 {@code updated_at}。</p>
     */
    public void upsert(String key, String value, String description) {
        LocalDateTime now = LocalDateTime.now();
        runner.update("INSERT INTO settings (setting_key, setting_value, description, updated_at) VALUES (?,?,?,?) "
                        + "ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value, "
                        + "description=excluded.description, updated_at=excluded.updated_at",
                key, value, description, now);
    }

    /** 删除指定键（知识索引指纹等内部键的定点清理）；返回删除行数。 */
    public int delete(String key) {
        return runner.update("DELETE FROM settings WHERE setting_key=?", key);
    }

    /** 删除指定前缀的全部键（内部键按「命名空间/」分组场景）；前缀中的 LIKE 通配符按字面处理。 */
    public int deleteByPrefix(String prefix) {
        String escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return runner.update("DELETE FROM settings WHERE setting_key LIKE ? ESCAPE '\\'", escaped + "%");
    }

    public List<AppSetting> findAll() {
        return runner.query("SELECT " + COLUMNS + " FROM settings ORDER BY setting_key", AppSettingDao::map);
    }
}
