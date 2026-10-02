package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.core.WorkspaceProvisioner;
import com.gettyio.gim.agent.core.capability.AgentCapabilityResolver;
import com.gettyio.gim.agent.core.capability.CapabilityCodec;
import com.gettyio.gim.agent.core.chat.AgentGateway;
import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.EffectiveModelResolver;
import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.core.model.AgentScopeModelFactory;
import com.gettyio.gim.agent.core.model.ModelProbe;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.dao.AgentDefinitionDao;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.entity.SkillPackage;
import com.gettyio.gim.agent.domain.enums.AgentType;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.persist.ConnectionProvider;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import com.gettyio.gim.agent.domain.security.SecretCipher;
import com.gettyio.gim.agent.domain.security.SecretCipherFactory;
import com.gettyio.gim.agent.scheduler.TaskSchedulerService;
import com.gettyio.gim.agent.scheduler.tool.ScheduleTools;
import com.gettyio.gim.agent.tools.ToolSecurityFacade;
import com.gettyio.gim.agent.tools.guard.DangerousCommandPolicy;
import com.gettyio.gim.agent.tools.guard.GuardChain;
import com.gettyio.gim.agent.tools.guard.PathWhitelistPolicy;
import com.gettyio.gim.agent.tools.guard.ToolGuard;
import com.gettyio.gim.agent.tools.hitl.HitlConfirmHandler;
import com.gettyio.gim.agent.tools.skill.SkillService;
import org.quartz.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用级 Bean 装配：把 domain/core/tools/scheduler 的组件注册为 Spring 单例。
 */
@Configuration
public class GimAgentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GimAgentConfiguration.class);

    @Bean
    public GimPaths gimPaths() {
        return new GimPaths();
    }

    @Bean
    public ConnectionProvider connectionProvider(GimPaths paths) {
        return new SqliteConnectionProvider(paths.dbFile());
    }

    @Bean
    public GimDatabase gimDatabase(ConnectionProvider connectionProvider) {
        GimDatabase db = new GimDatabase(connectionProvider);
        db.initSchema();
        return db;
    }

    @Bean
    public SecretCipher secretCipher(GimPaths paths) {
        return SecretCipherFactory.create(paths.secretKeyFile());
    }

    @Bean
    public WorkspaceProvisioner workspaceProvisioner(GimPaths paths) {
        WorkspaceProvisioner provisioner = new WorkspaceProvisioner(paths);
        provisioner.provision();
        return provisioner;
    }

    @Bean
    public AgentScopeModelFactory agentScopeModelFactory() {
        return new AgentScopeModelFactory();
    }

    @Bean
    public DefaultModelResolver defaultModelResolver(GimDatabase db, SecretCipher cipher) {
        return new DefaultModelResolver(db, cipher);
    }

    /** 模型连通性探测：配置页保存前发一条最小对话验证 baseUrl / apiKey / 模型名。 */
    @Bean
    public ModelProbe modelProbe(AgentScopeModelFactory modelFactory) {
        return new ModelProbe(modelFactory);
    }

    @Bean
    public AgentCapabilityResolver agentCapabilityResolver(GimDatabase db) {
        return new AgentCapabilityResolver(db);
    }

    @Bean
    public MultiAgentGateway agentGateway(GimPaths paths,
                                          GimDatabase db,
                                          DefaultModelResolver resolver,
                                          AgentScopeModelFactory modelFactory,
                                          ToolSecurityFacade toolSecurityFacade,
                                          SkillService skillService,
                                          AgentCapabilityResolver capabilities,
                                          ScheduleTools scheduleTools) {
        return new MultiAgentGateway(
                db.agentDao(),
                // 智能体定义 + 消息级模型覆盖 → 模型实例（未定向则用应用默认模型，按生成参数分桶复用）
                (def, providerCode, modelCode) ->
                        modelFactory.getOrCreate(resolver.resolveFor(def, providerCode, modelCode)),
                readAgentsMd(paths),
                paths.workspaceDir(),
                capabilities,
                gw -> {
                    // HITL：把工具安全门面的 precheck（含自动通过档）作为网关的确认回调
                    gw.setConfirmCallback(toolSecurityFacade::precheck);
                    // Skills：挂载 workspace/skills 作为 Harness 技能仓库（可见范围仍由智能体白名单决定）
                    gw.setSkillsDir(skillService.skillsRoot());
                    // 自定义工具：自然语言设定定时任务
                    gw.registerToolTarget(scheduleTools);
                });
    }

    @Bean
    public SkillService skillService(GimPaths paths, GimDatabase db) {
        return new SkillService(paths, db);
    }

    @Bean
    public ScheduleTools scheduleTools(TaskSchedulerService schedulerService, GimDatabase db) {
        return new ScheduleTools(schedulerService, db.taskDao());
    }

    @Bean
    public ProviderAdminService providerAdminService(GimDatabase db, SecretCipher cipher,
                                                     AgentScopeModelFactory modelFactory,
                                                     DefaultModelResolver resolver, ModelProbe modelProbe) {
        return new ProviderAdminService(db, cipher, modelFactory, resolver, modelProbe);
    }

    @Bean
    public TaskAdminService taskAdminService(GimDatabase db, TaskSchedulerService schedulerService,
                                             ChatService chatService) {
        return new TaskAdminService(db, schedulerService, chatService);
    }

    @Bean
    public McpAdminService mcpAdminService(GimDatabase db, AgentAdminService agentAdminService) {
        return new McpAdminService(db, agentAdminService);
    }

    @Bean
    public AgentAdminService agentAdminService(GimDatabase db, MultiAgentGateway agentGateway) {
        return new AgentAdminService(db, agentGateway);
    }

    @Bean
    public SecurityAdminService securityAdminService(GimDatabase db) {
        return new SecurityAdminService(db);
    }

    @Bean
    public UsageStatsService usageStatsService(GimDatabase db) {
        return new UsageStatsService(db);
    }

    @Bean
    public com.gettyio.gim.agent.ui.view.UsageView usageView(UsageStatsService usageStatsService) {
        return new com.gettyio.gim.agent.ui.view.UsageView(usageStatsService);
    }

    @Bean
    public ChatService chatService(AgentGateway agentGateway, GimDatabase db, GimPaths paths,
                                   DefaultModelResolver resolver) {
        // 生效模型编码：复用与运行时一致的选模链（消息级覆盖 → 智能体自带 → 应用默认），
        // 解析失败（如未配默认模型）返回 null，让用量落入「未标注」桶而非丢消息
        EffectiveModelResolver effective = (agentCode, providerCode, modelCode) -> {
            try {
                String code = agentCode == null || agentCode.isBlank()
                        ? MultiAgentGateway.DEFAULT_AGENT_CODE : agentCode;
                AgentDefinition def = db.agentDao().findByCode(code).orElse(null);
                return resolver.resolveFor(def, providerCode, modelCode).modelCode();
            } catch (RuntimeException e) {
                return null;
            }
        };
        return new ChatService(agentGateway, db, paths, effective);
    }

    @Bean
    public GuardChain guardChain(GimPaths paths, GimDatabase db) {
        Path allowed = paths.root().resolve("allowed");
        try {
            Files.createDirectories(allowed);
        } catch (IOException ignored) {
            // 目录创建失败不阻断，白名单仍可使用
        }
        // 动态授权根：各智能体配置的项目目录实时入白名单，避免项目内写被误报越界
        List<ToolGuard> guards = List.of(
                new DangerousCommandPolicy(),
                new PathWhitelistPolicy(List.of(allowed, paths.workspaceDir()),
                        () -> agentProjectRoots(db))
        );
        return new GuardChain(guards, db.permissionRuleDao());
    }

    /** 当前库中全部智能体定义的有效项目目录（失效路径与空白忽略）。 */
    private static List<Path> agentProjectRoots(GimDatabase db) {
        List<Path> roots = new ArrayList<>();
        for (AgentDefinition agent : db.agentDao().findAll()) {
            String configured = agent.getProjectPath();
            if (configured == null || configured.isBlank()) {
                continue;
            }
            try {
                Path dir = Path.of(configured.trim());
                if (Files.isDirectory(dir)) {
                    roots.add(dir);
                }
            } catch (java.nio.file.InvalidPathException ignored) {
                // 畸形路径不参与白名单
            }
        }
        return roots;
    }

    /** 默认确认处理器：无 UI 通道时保守拒绝；UI 控制器可以 @Primary 覆盖 */
    @Bean
    @ConditionalOnMissingBean(HitlConfirmHandler.class)
    public HitlConfirmHandler defaultHitlConfirmHandler() {
        return request -> java.util.concurrent.CompletableFuture.completedFuture(
                com.gettyio.gim.agent.domain.enums.PermissionDecision.DENY);
    }

    @Bean
    public ToolSecurityFacade toolSecurityFacade(GuardChain guardChain, HitlConfirmHandler confirmHandler) {
        return new ToolSecurityFacade(guardChain, confirmHandler);
    }

    @Bean
    public TaskSchedulerService taskSchedulerService(Scheduler scheduler, GimDatabase db) {
        return new TaskSchedulerService(scheduler, db.taskDao());
    }

    @Bean
    public CommandLineRunner resumeTasks(TaskSchedulerService schedulerService) {
        return args -> schedulerService.resumeReadyTasks();
    }

    /**
     * 启动时播种内置默认智能体（幂等）。人格/模型/工作区字段留空，即沿用 AGENTS.md 人格与应用默认模型。
     * <p>技能在出生时就<b>显式声明</b>为当时已启用的技能（{@code seedSkills} 排在前面），
     * 所以后续装配不必隐式继承任何东西；用户可在智能体表单里自由增减。</p>
     */
    @Bean
    @Order(200)
    public CommandLineRunner seedDefaultAgent(GimDatabase db) {
        return args -> {
            AgentDefinitionDao dao = db.agentDao();
            if (dao.findByCode(MultiAgentGateway.DEFAULT_AGENT_CODE).isPresent()) {
                return;
            }
            AgentDefinition agent = new AgentDefinition();
            agent.setAgentCode(MultiAgentGateway.DEFAULT_AGENT_CODE);
            agent.setAgentName("Gim Agent");
            agent.setAgentType(AgentType.SIMPLE.name());
            agent.setDescription("本地优先的个人 AI 助理（默认智能体）");
            // 生成参数留空：交由模型/提供商默认决定，不在此固定 temperature/maxTokens
            agent.setTemperature(null);
            agent.setMaxTokens(null);
            List<String> enabledSkills = new ArrayList<>();
            for (SkillPackage s : db.skillDao().findAll()) {
                if (EnabledStatus.isEnabled(s.getEnabled())) {
                    enabledSkills.add(s.getSkillCode());
                }
            }
            agent.setSkillsJson(CapabilityCodec.writeCodes(enabledSkills));
            dao.insert(agent);
            log.info("播种默认智能体: {}（已启用技能：{}）", agent.getAgentCode(), enabledSkills);
        };
    }

    /** 启动时播种内置示例技能（幂等） */
    @Bean
    @Order(100)
    public CommandLineRunner seedSkills(SkillService skillService) {
        return args -> skillService.seedBuiltinSkills();
    }

    private static String readAgentsMd(GimPaths paths) {
        try {
            if (Files.exists(paths.agentsMd())) {
                return Files.readString(paths.agentsMd(), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // 回退到内置提示
        }
        return "你是 Gim Agent，一个运行在本地桌面的个人 AI 助理。";
    }
}
