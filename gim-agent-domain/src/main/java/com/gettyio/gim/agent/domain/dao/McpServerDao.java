package com.gettyio.gim.agent.domain.dao;

import com.gettyio.gim.agent.domain.entity.McpServerConfig;
import com.gettyio.gim.agent.domain.persist.SqlRunner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * mcp_servers 表 DAO
 */
public class McpServerDao {

    private static final String COLUMNS =
            "id, server_code, server_name, transport_type, command, args_json, env_json, base_url, headers_json, "
                    + "enabled, timeout, status, created_at, updated_at";

    private final SqlRunner runner;

    public McpServerDao(SqlRunner runner) {
        this.runner = runner;
    }

    public static McpServerConfig map(ResultSet rs) throws SQLException {
        McpServerConfig m = new McpServerConfig();
        m.setId(SqlRunner.getLong(rs, "id"));
        m.setServerCode(rs.getString("server_code"));
        m.setServerName(rs.getString("server_name"));
        m.setTransportType(rs.getString("transport_type"));
        m.setCommand(rs.getString("command"));
        m.setArgsJson(rs.getString("args_json"));
        m.setEnvJson(rs.getString("env_json"));
        m.setBaseUrl(rs.getString("base_url"));
        m.setHeadersJson(rs.getString("headers_json"));
        m.setEnabled(SqlRunner.getInt(rs, "enabled"));
        m.setTimeout(SqlRunner.getInt(rs, "timeout"));
        m.setStatus(rs.getString("status"));
        m.setCreatedAt(SqlRunner.getDateTime(rs, "created_at"));
        m.setUpdatedAt(SqlRunner.getDateTime(rs, "updated_at"));
        return m;
    }

    public long insert(McpServerConfig m) {
        LocalDateTime now = LocalDateTime.now();
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        long id = runner.insert(
                "INSERT INTO mcp_servers (server_code, server_name, transport_type, command, args_json, env_json, base_url, "
                        + "headers_json, enabled, timeout, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                m.getServerCode(), m.getServerName(), m.getTransportType(), m.getCommand(), m.getArgsJson(),
                m.getEnvJson(), m.getBaseUrl(), m.getHeadersJson(), m.getEnabled(), m.getTimeout(), m.getStatus(), now, now);
        m.setId(id);
        return id;
    }

    public int update(McpServerConfig m) {
        m.setUpdatedAt(LocalDateTime.now());
        return runner.update(
                "UPDATE mcp_servers SET server_name=?, transport_type=?, command=?, args_json=?, env_json=?, base_url=?, "
                        + "headers_json=?, enabled=?, timeout=?, status=?, updated_at=? WHERE server_code=?",
                m.getServerName(), m.getTransportType(), m.getCommand(), m.getArgsJson(), m.getEnvJson(), m.getBaseUrl(),
                m.getHeadersJson(), m.getEnabled(), m.getTimeout(), m.getStatus(), m.getUpdatedAt(), m.getServerCode());
    }

    public Optional<McpServerConfig> findByCode(String serverCode) {
        return runner.queryOne("SELECT " + COLUMNS + " FROM mcp_servers WHERE server_code=?", McpServerDao::map, serverCode);
    }

    public List<McpServerConfig> findAll() {
        return runner.query("SELECT " + COLUMNS + " FROM mcp_servers ORDER BY id", McpServerDao::map);
    }

    public List<McpServerConfig> findEnabled() {
        return runner.query("SELECT " + COLUMNS + " FROM mcp_servers WHERE enabled=1 ORDER BY id", McpServerDao::map);
    }

    public int deleteByCode(String serverCode) {
        return runner.update("DELETE FROM mcp_servers WHERE server_code=?", serverCode);
    }
}
