package com.gettyio.gim.agent.domain.dao;

import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.persist.SqlRunner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * agents 表 DAO
 */
public class AgentDefinitionDao {

    private static final String COLUMNS =
            "id, agent_code, agent_name, agent_type, description, system_prompt, model_code, provider_code, "
                    + "model_name, skills_json, mcp_servers_json, workspace_dir, temperature, max_tokens, enabled, "
                    + "created_at, updated_at";

    private final SqlRunner runner;

    public AgentDefinitionDao(SqlRunner runner) {
        this.runner = runner;
    }

    public static AgentDefinition map(ResultSet rs) throws SQLException {
        AgentDefinition a = new AgentDefinition();
        a.setId(SqlRunner.getLong(rs, "id"));
        a.setAgentCode(rs.getString("agent_code"));
        a.setAgentName(rs.getString("agent_name"));
        a.setAgentType(rs.getString("agent_type"));
        a.setDescription(rs.getString("description"));
        a.setSystemPrompt(rs.getString("system_prompt"));
        a.setModelCode(rs.getString("model_code"));
        a.setProviderCode(rs.getString("provider_code"));
        a.setModelName(rs.getString("model_name"));
        a.setSkillsJson(rs.getString("skills_json"));
        a.setMcpServersJson(rs.getString("mcp_servers_json"));
        a.setWorkspaceDir(rs.getString("workspace_dir"));
        a.setTemperature(SqlRunner.getDouble(rs, "temperature"));
        a.setMaxTokens(SqlRunner.getInt(rs, "max_tokens"));
        a.setEnabled(SqlRunner.getInt(rs, "enabled"));
        a.setCreatedAt(SqlRunner.getDateTime(rs, "created_at"));
        a.setUpdatedAt(SqlRunner.getDateTime(rs, "updated_at"));
        return a;
    }

    public long insert(AgentDefinition a) {
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        long id = runner.insert(
                "INSERT INTO agents (agent_code, agent_name, agent_type, description, system_prompt, model_code, "
                        + "provider_code, model_name, skills_json, mcp_servers_json, workspace_dir, temperature, "
                        + "max_tokens, enabled, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                a.getAgentCode(), a.getAgentName(), a.getAgentType(), a.getDescription(), a.getSystemPrompt(),
                a.getModelCode(), a.getProviderCode(), a.getModelName(), a.getSkillsJson(), a.getMcpServersJson(),
                a.getWorkspaceDir(), a.getTemperature(), a.getMaxTokens(), a.getEnabled(), a.getCreatedAt(), a.getUpdatedAt());
        a.setId(id);
        return id;
    }

    public int update(AgentDefinition a) {
        a.setUpdatedAt(LocalDateTime.now());
        return runner.update(
                "UPDATE agents SET agent_name=?, agent_type=?, description=?, system_prompt=?, model_code=?, "
                        + "provider_code=?, model_name=?, skills_json=?, mcp_servers_json=?, workspace_dir=?, "
                        + "temperature=?, max_tokens=?, enabled=?, updated_at=? WHERE agent_code=?",
                a.getAgentName(), a.getAgentType(), a.getDescription(), a.getSystemPrompt(), a.getModelCode(),
                a.getProviderCode(), a.getModelName(), a.getSkillsJson(), a.getMcpServersJson(), a.getWorkspaceDir(),
                a.getTemperature(), a.getMaxTokens(), a.getEnabled(), a.getUpdatedAt(), a.getAgentCode());
    }

    public Optional<AgentDefinition> findByCode(String agentCode) {
        return runner.queryOne("SELECT " + COLUMNS + " FROM agents WHERE agent_code=?",
                AgentDefinitionDao::map, agentCode);
    }

    public List<AgentDefinition> findAll() {
        return runner.query("SELECT " + COLUMNS + " FROM agents ORDER BY id", AgentDefinitionDao::map);
    }

    public List<AgentDefinition> findEnabled() {
        return runner.query("SELECT " + COLUMNS + " FROM agents WHERE enabled=1 ORDER BY id", AgentDefinitionDao::map);
    }

    public int deleteByCode(String agentCode) {
        return runner.update("DELETE FROM agents WHERE agent_code=?", agentCode);
    }
}
