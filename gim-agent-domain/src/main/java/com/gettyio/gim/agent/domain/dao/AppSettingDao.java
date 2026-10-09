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

    public void upsert(String key, String value, String description) {
        int updated = runner.update("UPDATE settings SET setting_value=?, description=?, updated_at=? WHERE setting_key=?",
                value, description, LocalDateTime.now(), key);
        if (updated == 0) {
            runner.insert("INSERT INTO settings (setting_key, setting_value, description, updated_at) VALUES (?,?,?,?)",
                    key, value, description, LocalDateTime.now());
        }
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
