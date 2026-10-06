package com.gettyio.gim.agent.domain;

import com.gettyio.gim.agent.domain.dao.*;
import com.gettyio.gim.agent.domain.persist.ConnectionProvider;
import com.gettyio.gim.agent.domain.persist.SqlRunner;
import com.gettyio.gim.agent.domain.persist.SqlSchemaInitializer;

/**
 * 数据库门面：聚合连接、schema 初始化与各 DAO，供上层模块注入使用
 */
public class GimDatabase {

    private final ConnectionProvider connectionProvider;
    private final SqlRunner runner;

    private final AgentDefinitionDao agentDao;
    private final ChatSessionDao sessionDao;
    private final ChatConversationDao conversationDao;
    private final ModelProviderDao modelProviderDao;
    private final ModelConfigDao modelConfigDao;
    private final McpServerDao mcpServerDao;
    private final SkillPackageDao skillDao;
    private final ScheduledTaskDao taskDao;
    private final TaskRunDao taskRunDao;
    private final AppSettingDao settingDao;
    private final ToolPermissionRuleDao permissionRuleDao;
    private final PromptTemplateDao promptTemplateDao;

    public GimDatabase(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
        this.runner = new SqlRunner(connectionProvider);
        this.agentDao = new AgentDefinitionDao(runner);
        this.sessionDao = new ChatSessionDao(runner);
        this.conversationDao = new ChatConversationDao(runner);
        this.modelProviderDao = new ModelProviderDao(runner);
        this.modelConfigDao = new ModelConfigDao(runner);
        this.mcpServerDao = new McpServerDao(runner);
        this.skillDao = new SkillPackageDao(runner);
        this.taskDao = new ScheduledTaskDao(runner);
        this.taskRunDao = new TaskRunDao(runner);
        this.settingDao = new AppSettingDao(runner);
        this.permissionRuleDao = new ToolPermissionRuleDao(runner);
        this.promptTemplateDao = new PromptTemplateDao(runner);
    }

    /** 幂等初始化表结构 */
    public void initSchema() {
        new SqlSchemaInitializer(connectionProvider).initialize();
    }

    public SqlRunner getRunner() {
        return runner;
    }

    public AgentDefinitionDao agentDao() {
        return agentDao;
    }

    public ChatSessionDao sessionDao() {
        return sessionDao;
    }

    public ChatConversationDao conversationDao() {
        return conversationDao;
    }

    public ModelProviderDao modelProviderDao() {
        return modelProviderDao;
    }

    public ModelConfigDao modelConfigDao() {
        return modelConfigDao;
    }

    public McpServerDao mcpServerDao() {
        return mcpServerDao;
    }

    public SkillPackageDao skillDao() {
        return skillDao;
    }

    public ScheduledTaskDao taskDao() {
        return taskDao;
    }

    public TaskRunDao taskRunDao() {
        return taskRunDao;
    }

    public AppSettingDao settingDao() {
        return settingDao;
    }

    public ToolPermissionRuleDao permissionRuleDao() {
        return permissionRuleDao;
    }

    public PromptTemplateDao promptTemplateDao() {
        return promptTemplateDao;
    }
}
