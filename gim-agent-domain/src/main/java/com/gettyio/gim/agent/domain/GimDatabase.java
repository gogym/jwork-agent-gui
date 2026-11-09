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

package com.gettyio.gim.agent.domain;

import com.gettyio.gim.agent.domain.dao.*;
import com.gettyio.gim.agent.domain.persist.ConnectionProvider;
import com.gettyio.gim.agent.domain.persist.SqlRunner;
import com.gettyio.gim.agent.domain.persist.SqlSchemaInitializer;

/**
 * 数据库门面：聚合连接、schema 初始化与各 DAO，供上层模块注入使用
 *
 * @author gogym
 */
public class GimDatabase {

    private final ConnectionProvider connectionProvider;
    private final SqlRunner runner;

    private final AgentDefinitionDao agentDao;
    private final ChatSessionDao sessionDao;
    private final SessionGroupDao sessionGroupDao;
    private final ChatConversationDao conversationDao;
    private final TurnDraftDao draftDao;
    private final ModelProviderDao modelProviderDao;
    private final ModelConfigDao modelConfigDao;
    private final McpServerDao mcpServerDao;
    private final SkillPackageDao skillDao;
    private final ScheduledTaskDao taskDao;
    private final TaskRunDao taskRunDao;
    private final AppSettingDao settingDao;
    private final ToolPermissionRuleDao permissionRuleDao;
    private final PromptTemplateDao promptTemplateDao;
    private final KnowledgeChunkDao knowledgeChunkDao;

    public GimDatabase(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
        this.runner = new SqlRunner(connectionProvider);
        this.agentDao = new AgentDefinitionDao(runner);
        this.sessionDao = new ChatSessionDao(runner);
        this.sessionGroupDao = new SessionGroupDao(runner);
        this.conversationDao = new ChatConversationDao(runner);
        this.draftDao = new TurnDraftDao(runner);
        this.modelProviderDao = new ModelProviderDao(runner);
        this.modelConfigDao = new ModelConfigDao(runner);
        this.mcpServerDao = new McpServerDao(runner);
        this.skillDao = new SkillPackageDao(runner);
        this.taskDao = new ScheduledTaskDao(runner);
        this.taskRunDao = new TaskRunDao(runner);
        this.settingDao = new AppSettingDao(runner);
        this.permissionRuleDao = new ToolPermissionRuleDao(runner);
        this.promptTemplateDao = new PromptTemplateDao(runner);
        this.knowledgeChunkDao = new KnowledgeChunkDao(runner);
    }

    /** 幂等初始化表结构 */
    public void initSchema() {
        new SqlSchemaInitializer(connectionProvider).initialize();
    }

    /**
     * 把一组 DAO 调用包成单个原子操作：全部成功才提交，中途抛异常则整体回滚。
     * <p>用于「删父行 + 改子行计数」这类跨表成对写入——默认每语句一个连接，任一步失败都会留下
     * 半套状态（消息删了、计数没回退；旧块删了、新块没写进）。</p>
     */
    public void runInTransaction(Runnable work) {
        runner.runInTransaction(work);
    }

    /** {@link #runInTransaction(Runnable)} 的带返回值版。 */
    public <T> T callInTransaction(java.util.function.Supplier<T> work) {
        return runner.callInTransaction(work);
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

    public SessionGroupDao sessionGroupDao() {
        return sessionGroupDao;
    }

    public ChatConversationDao conversationDao() {
        return conversationDao;
    }

    /** 在途应答检查点（崩溃/被杀后重启时提升为一条助手消息）。 */
    public TurnDraftDao draftDao() {
        return draftDao;
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

    public KnowledgeChunkDao knowledgeChunkDao() {
        return knowledgeChunkDao;
    }
}
