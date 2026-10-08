package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.core.WorkspaceProvisioner;
import com.gettyio.gim.agent.core.capability.AgentCapabilityResolver;
import com.gettyio.gim.agent.core.capability.CapabilityCodec;
import com.gettyio.gim.agent.core.chat.AgentGateway;
import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.EffectiveModelResolver;
import com.gettyio.gim.agent.core.chat.HarnessAgentGateway;
import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.core.knowledge.KnowledgeService;
import com.gettyio.gim.agent.core.memory.MemoryService;
import com.gettyio.gim.agent.core.plan.PlanService;
import com.gettyio.gim.agent.core.model.AgentScopeModelFactory;
import com.gettyio.gim.agent.core.model.ModelProbe;
import com.gettyio.gim.agent.core.model.ModelSpec;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.backup.DbBackupService;
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
import com.gettyio.gim.agent.tools.guard.ExecutableAllowlistPolicy;
import com.gettyio.gim.agent.tools.guard.GuardChain;
import com.gettyio.gim.agent.tools.guard.PathWhitelistPolicy;
import com.gettyio.gim.agent.tools.guard.ToolGuard;
import com.gettyio.gim.agent.tools.hitl.HitlConfirmHandler;
import com.gettyio.gim.agent.tools.sandbox.SandboxExecutor;
import com.gettyio.gim.agent.tools.skill.SkillService;
import com.gettyio.gim.agent.tools.web.WebSearchTools;
import com.gettyio.gim.agent.ui.export.ExportService;
import io.agentscope.harness.agent.memory.MemoryConfig;
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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

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
                                          TaskSchedulerService schedulerService,
                                          SecurityAdminService securityAdminService,
                                          com.gettyio.gim.agent.core.knowledge.EmbeddingIndexService embeddingIndexer) {
        // 智能体定义 + 消息级模型覆盖 → 模型实例（未定向则用应用默认模型，按生成参数分桶复用）；
        // 备用模型：同提供商按优先级取下一个启用模型，接 Harness 框架级重试 + failover
        MultiAgentGateway.ModelResolver modelResolver = new MultiAgentGateway.ModelResolver() {
            @Override
            public io.agentscope.core.model.Model resolve(AgentDefinition def, String providerCode, String modelCode) {
                return modelFactory.getOrCreate(resolver.resolveFor(def, providerCode, modelCode));
            }

            @Override
            public io.agentscope.core.model.Model resolveFallback(AgentDefinition def, String providerCode, String modelCode) {
                ModelSpec fallback = resolver.resolveFallback(def, providerCode, modelCode);
                return fallback == null ? null : modelFactory.getOrCreate(fallback);
            }
        };
        return new MultiAgentGateway(
                db.agentDao(),
                modelResolver,
                readAgentsMd(paths),
                paths.workspaceDir(),
                capabilities,
                gw -> {
                    // HITL：把工具安全门面的 precheck（含自动通过档）作为网关的确认回调
                    gw.setConfirmCallback(toolSecurityFacade::precheck);
                    // Skills：挂载 workspace/skills 作为 Harness 技能仓库（可见范围仍由智能体白名单决定）
                    gw.setSkillsDir(skillService.skillsRoot());
                    // 上下文窗口上限：AppSetting 键 agent.context.maxTokens 可调，缺省沿用网关内置默认值
                    readContextMaxTokens(db).ifPresent(gw::setMaxContextTokens);
                    // 长期记忆：开关 + flush 节流策略由 AppSetting 下发（缺省开启、每次调用后 flush）
                    applyMemorySettings(gw, db);
                    // 计划模式：总开关由 AppSetting 下发（缺省开启，注册 plan_* 工具与只读中间件）
                    gw.setPlanModeEnabled(readBool(db, "agent.planMode.enabled", true));
                    // 自定义工具：自然语言设定定时任务——每个智能体一份实例，新建任务归属当前智能体
                    gw.registerToolTarget(new ScheduleTools(schedulerService, db.taskDao(), gw.getAgentId()));
                    // 联网工具：web_search / web_fetch——Key 从加密的 settings 实时取用，改配不重启即生效
                    gw.registerToolTarget(new WebSearchTools(securityAdminService::getWebSearchApiKeyPlain));
                    // 知识检索工具：knowledge_search——向量召回知识块，与 KNOWLEDGE.md 文件树路线并存
                    gw.registerToolTarget(new com.gettyio.gim.agent.core.knowledge.KnowledgeSearchTools(
                            embeddingIndexer, gw::getAgentId));
                });
    }

    /** 从 AppSetting {@code agent.context.maxTokens} 读取上下文窗口上限；缺失或非法（非正整数）则返回空（用默认）。 */
    private static java.util.Optional<Integer> readContextMaxTokens(GimDatabase db) {
        return db.settingDao().getValue("agent.context.maxTokens").flatMap(raw -> {
            try {
                int v = Integer.parseInt(raw.trim());
                return v > 0 ? java.util.Optional.of(v) : java.util.Optional.empty();
            } catch (NumberFormatException e) {
                return java.util.Optional.empty();
            }
        });
    }

    /** 读一个布尔型 AppSetting：{@code "false"}（忽略大小写）为关，其余/缺省为 {@code defaultValue}。 */
    private static boolean readBool(GimDatabase db, String key, boolean defaultValue) {
        return db.settingDao().getValue(key)
                .map(v -> !"false".equalsIgnoreCase(v.trim()))
                .orElse(defaultValue);
    }

    /** 依 AppSetting 下发长期记忆装配：
     * <ul>
     *   <li>{@code agent.memory.enabled}：{@code "false"} 即关闭（其余/缺省为开）；</li>
     *   <li>{@code agent.memory.flushMode}：{@code always}（默认）/ {@code throttled}（≥ 10min）/ {@code never}。</li>
     * </ul>
     */
    private static void applyMemorySettings(HarnessAgentGateway gw, GimDatabase db) {
        boolean enabled = db.settingDao().getValue("agent.memory.enabled")
                .map(v -> !"false".equalsIgnoreCase(v.trim()))
                .orElse(true);
        gw.setMemoryEnabled(enabled);
        String flushMode = db.settingDao().getValue("agent.memory.flushMode")
                .map(v -> v.trim().toLowerCase(java.util.Locale.ROOT))
                .orElse("always");
        MemoryConfig.FlushTrigger trigger = switch (flushMode) {
            case "never" -> MemoryConfig.FlushTrigger.never();
            case "throttled" -> MemoryConfig.FlushTrigger.throttled(java.time.Duration.ofMinutes(10));
            default -> MemoryConfig.FlushTrigger.always();
        };
        gw.setMemoryConfig(MemoryConfig.builder().flushTrigger(trigger).build());
    }

    /** 应用级长期记忆管理：供记忆页读写各智能体工作区的 MEMORY.md 与 memory/*.md。 */
    @Bean
    public MemoryService memoryService(GimDatabase db, MultiAgentGateway agentGateway) {
        return new MemoryService(db, agentGateway);
    }

    /**
     * 向量知识索引器：分块嵌入落 knowledge_chunks，供知识检索工具与知识页重建索引。
     * embedding 客户端每次现建（读加密 settings），配置改动即生效。
     */
    @Bean
    public com.gettyio.gim.agent.core.knowledge.EmbeddingIndexService embeddingIndexService(
            GimDatabase db, SecurityAdminService securityAdminService) {
        return new com.gettyio.gim.agent.core.knowledge.EmbeddingIndexService(db, () -> {
            String baseUrl = securityAdminService.getKnowledgeEmbeddingBaseUrl();
            String model = securityAdminService.getKnowledgeEmbeddingModel();
            if (baseUrl.isEmpty() || model.isEmpty()) {
                return null;
            }
            return new com.gettyio.gim.agent.core.knowledge.OpenAiCompatibleEmbeddingClient(
                    baseUrl, securityAdminService.getKnowledgeEmbeddingKeyPlain(), model);
        });
    }

    /** 应用级知识库管理（文件树 + 向量双路线）：供知识页导入/删除资料并维护 KNOWLEDGE.md 索引。 */
    @Bean
    public KnowledgeService knowledgeService(GimDatabase db, MultiAgentGateway agentGateway,
                                             com.gettyio.gim.agent.core.knowledge.EmbeddingIndexService embeddingIndexer) {
        return new KnowledgeService(db, agentGateway, embeddingIndexer);
    }

    /** 计划文件读写：供规划轮结束后在 UI 预览/编辑 {@code plans/PLAN.md}。 */
    @Bean
    public PlanService planService(MultiAgentGateway agentGateway) {
        return new PlanService(agentGateway);
    }

    @Bean
    public SkillService skillService(GimPaths paths, GimDatabase db) {
        return new SkillService(paths, db);
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
    public PromptTemplateAdminService promptTemplateAdminService(GimDatabase db) {
        return new PromptTemplateAdminService(db);
    }

    @Bean
    public AgentAdminService agentAdminService(GimDatabase db, MultiAgentGateway agentGateway) {
        return new AgentAdminService(db, agentGateway);
    }

    @Bean
    public SecurityAdminService securityAdminService(GimDatabase db, SecretCipher cipher) {
        return new SecurityAdminService(db, cipher);
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
    public GuardChain guardChain(GimPaths paths, GimDatabase db, SecurityAdminService securityAdminService) {
        Path allowed = paths.root().resolve("allowed");
        try {
            Files.createDirectories(allowed);
        } catch (IOException ignored) {
            // 目录创建失败不阻断，白名单仍可使用
        }
        List<ToolGuard> guards = List.of(
                // 危险命令黑名单：每次评估实时读库，设置页增删改/启停无需重启即生效
                new DangerousCommandPolicy(securityAdminService::listDangerousRules),
                // shell 只能启动白名单内的程序；扩展来源 = 用户文件 + 设置页新增（实时合并）
                new ExecutableAllowlistPolicy(ExecutableAllowlistPolicy.DEFAULT_ALLOWED,
                        shellExtraSupplier(paths, securityAdminService)),
                // 动态授权根：各智能体项目目录 + 设置页新增目录，实时并入白名单
                new PathWhitelistPolicy(List.of(allowed, paths.workspaceDir()),
                        pathExtraSupplier(db, securityAdminService))
        );
        return new GuardChain(guards, db.permissionRuleDao());
    }

    /** Shell 白名单扩展：文本文件与设置页新增程序名实时合并（归一交给策略本身）。 */
    private static Supplier<Collection<String>> shellExtraSupplier(GimPaths paths, SecurityAdminService security) {
        Supplier<Collection<String>> file =
                ExecutableAllowlistPolicy.fileSupplier(paths.root().resolve("shell-allowlist.txt"));
        return () -> {
            List<String> merged = new ArrayList<>(file.get());
            merged.addAll(security.listShellAllowlist());
            return merged;
        };
    }

    /** 文件授权目录扩展：各智能体项目目录 + 设置页新增目录（仅收录真实存在的目录）。 */
    private static Supplier<Collection<Path>> pathExtraSupplier(GimDatabase db, SecurityAdminService security) {
        return () -> {
            List<Path> roots = new ArrayList<>(agentProjectRoots(db));
            for (String configured : security.listPathRoots()) {
                try {
                    Path dir = Path.of(configured.trim());
                    if (Files.isDirectory(dir)) {
                        roots.add(dir);
                    }
                } catch (InvalidPathException ignored) {
                    // 畸形路径不参与白名单
                }
            }
            return roots;
        };
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

    /** 数据库备份服务：VACUUM INTO 在线快照 + 滚动保留（backups 目录）。 */
    @Bean
    public DbBackupService dbBackupService(GimDatabase db, GimPaths paths) {
        return new DbBackupService(db, paths.backupsDir());
    }

    /** 会话/全库导出服务：单会话 Markdown 与全库 JSON。 */
    @Bean
    public ExportService exportService(GimDatabase db) {
        return new ExportService(db);
    }

    /**
     * 启动时距上次备份超过 24h 自动补一次（首次无记录也执行）。
     * <p>备份（VACUUM INTO + 滚动清理）可能秒级耗时，放后台线程执行，不阻塞主窗出现。</p>
     */
    @Bean
    @Order(50)
    public CommandLineRunner autoBackupOnStartup(DbBackupService backupService) {
        return args -> {
            Thread t = new Thread(() -> {
                try {
                    backupService.backupIfDue();
                } catch (RuntimeException e) {
                    log.warn("启动自动备份失败", e);
                }
            }, "startup-backup");
            t.setDaemon(true);
            t.start();
        };
    }

    /** 启动时后台预拉取沙箱轻量镜像（Docker 可用时；失败/超时只记日志，绝不阻塞启动）。 */
    @Bean
    @Order(60)
    public CommandLineRunner prePullSandboxImage() {
        SandboxExecutor executor = new SandboxExecutor();
        return args -> {
            if (!executor.dockerAvailable()) {
                return;
            }
            Thread t = new Thread(executor::prePullImage, "sandbox-prepull");
            t.setDaemon(true);
            t.start();
        };
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
            agent.setAgentName("Jwork");
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
        return "你是 Jwork，一个运行在本地桌面的个人 AI 助理。";
    }
}
