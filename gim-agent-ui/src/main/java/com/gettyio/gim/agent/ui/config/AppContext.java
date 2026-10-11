package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.core.WorkspaceProvisioner;
import com.gettyio.gim.agent.core.capability.AgentCapabilityResolver;
import com.gettyio.gim.agent.core.capability.CapabilityCodec;
import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.EffectiveModelResolver;
import com.gettyio.gim.agent.core.chat.HarnessAgentGateway;
import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.core.knowledge.EmbeddingClient;
import com.gettyio.gim.agent.core.knowledge.EmbeddingIndexService;
import com.gettyio.gim.agent.core.knowledge.KnowledgeSearchTools;
import com.gettyio.gim.agent.core.knowledge.KnowledgeService;
import com.gettyio.gim.agent.core.knowledge.OpenAiCompatibleEmbeddingClient;
import com.gettyio.gim.agent.core.memory.MemoryService;
import com.gettyio.gim.agent.core.memory.MemoryWriteTool;
import com.gettyio.gim.agent.core.model.AgentScopeModelFactory;
import com.gettyio.gim.agent.core.model.ModelProbe;
import com.gettyio.gim.agent.core.model.ModelSpec;
import com.gettyio.gim.agent.core.plan.PlanService;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.backup.DbBackupService;
import com.gettyio.gim.agent.domain.backup.RestoreApplier;
import com.gettyio.gim.agent.domain.dao.AgentDefinitionDao;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.entity.SkillPackage;
import com.gettyio.gim.agent.domain.enums.AgentType;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.persist.ConnectionProvider;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import com.gettyio.gim.agent.domain.security.LocalAuthService;
import com.gettyio.gim.agent.domain.security.SecretCipher;
import com.gettyio.gim.agent.domain.security.SecretCipherFactory;
import com.gettyio.gim.agent.scheduler.AgentTaskExecutor;
import com.gettyio.gim.agent.scheduler.AgentTaskJobFactory;
import com.gettyio.gim.agent.scheduler.TaskSchedulerService;
import com.gettyio.gim.agent.scheduler.tool.ScheduleTools;
import com.gettyio.gim.agent.tools.ToolSecurityFacade;
import com.gettyio.gim.agent.tools.guard.DangerousCommandPolicy;
import com.gettyio.gim.agent.tools.guard.ExecutableAllowlistPolicy;
import com.gettyio.gim.agent.tools.guard.GuardChain;
import com.gettyio.gim.agent.tools.guard.PathWhitelistPolicy;
import com.gettyio.gim.agent.tools.guard.ToolGuard;
import com.gettyio.gim.agent.tools.image.ImageGenerationClient;
import com.gettyio.gim.agent.tools.image.ImageGenerationTools;
import com.gettyio.gim.agent.tools.sandbox.DockerAvailabilityProbe;
import com.gettyio.gim.agent.tools.sandbox.SandboxExecutor;
import com.gettyio.gim.agent.tools.sandbox.SandboxShellTool;
import com.gettyio.gim.agent.tools.skill.SkillService;
import com.gettyio.gim.agent.tools.web.WebSearchTools;
import com.gettyio.gim.agent.ui.controller.MainController;
import com.gettyio.gim.agent.ui.desktop.GlobalHotkeyService;
import com.gettyio.gim.agent.ui.desktop.KeepAwakeService;
import com.gettyio.gim.agent.ui.desktop.QuickAskWindow;
import com.gettyio.gim.agent.ui.desktop.SelectionAssistant;
import com.gettyio.gim.agent.ui.export.ExportService;
import com.gettyio.gim.agent.ui.hitl.FxHitlConfirmHandler;
import com.gettyio.gim.agent.ui.market.MarketService;
import com.gettyio.gim.agent.ui.notify.SystemNotifier;
import com.gettyio.gim.agent.ui.notify.TrayNotifier;
import com.gettyio.gim.agent.ui.sync.WebDavSyncService;
import com.gettyio.gim.agent.ui.throttle.ModelRateGovernor;
import com.gettyio.gim.agent.ui.update.UpdateChecker;
import com.gettyio.gim.agent.ui.view.ExtensionsView;
import com.gettyio.gim.agent.ui.view.KnowledgeView;
import com.gettyio.gim.agent.ui.view.SkillsView;
import com.gettyio.gim.agent.ui.view.TasksView;
import com.gettyio.gim.agent.ui.view.TerminalView;
import com.gettyio.gim.agent.ui.view.UsageView;
import com.gettyio.gim.agent.ui.voice.TtsService;
import com.gettyio.gim.agent.ui.voice.VoiceInputService;
import com.gettyio.gim.agent.ui.window.AppLifecycle;
import com.gettyio.gim.agent.ui.window.AppShell;
import com.gettyio.gim.agent.ui.window.SettingsWindow;
import io.agentscope.harness.agent.memory.MemoryConfig;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * 应用组合根：以显式构造替代原 Spring 容器，集中装配全部单例并驱动生命周期。
 * <p>构造分两个阶段应对 JavaFX 线程约束：</p>
 * <ul>
 *   <li><b>构造器</b>（JavaFX {@code init()} 的无头线程调用）：装配全部不触碰场景图的服务——
 *       存储、模型、安全、网关、调度、备份等，并启动 Quartz 与保持唤醒等启动期副作用；</li>
 *   <li><b>UI 阶段</b>（{@link #mainController()} 于 FX 线程调用）：视图/设置窗口/主控制器的
 *       构造即创建 JavaFX 节点，必须在 FX 线程完成（对齐原全局懒加载语义——UI 对象在
 *       FXML 装配期才创建）；快速问答小窗与划词助手维持认证后首次取用的懒构建。</li>
 * </ul>
 * <p>启动序列（{@link #runStartupTasks()}）与原 CommandLineRunner 的 {@code @Order}
 * （备份 50 → 预拉镜像 60 → 播种技能 100 → 播种默认智能体 200 → 恢复任务）及
 * ApplicationReadyEvent（WebDAV 自动同步）逐一对应；{@link #close()} 按原
 * {@code @PreDestroy} 语义依次停机。</p>
 */
public final class AppContext implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AppContext.class);

    // ===================== 存储与安全 =====================

    private final GimPaths paths;
    private final GimDatabase db;
    private final SecretCipher cipher;
    private final WorkspaceProvisioner workspaceProvisioner;

    // ===================== 模型 =====================

    private final AgentScopeModelFactory modelFactory;
    private final DefaultModelResolver modelResolver;
    private final ModelProbe modelProbe;

    // ===================== 安全与工具 =====================

    private final SecurityAdminService securityAdmin;
    private final ImageGenerationClient imageGenerationClient;
    private final AgentCapabilityResolver capabilities;
    private final DockerAvailabilityProbe dockerProbe;
    private final SkillService skillService;
    private final GuardChain guardChain;
    private final FxHitlConfirmHandler fxHitl;
    private final ToolSecurityFacade toolSecurity;

    // ===================== 调度与执行 =====================

    private final Scheduler scheduler;
    private final TaskSchedulerService schedulerService;
    private final AgentTaskExecutor taskExecutor;

    // ===================== 网关与聊天 =====================

    private final EmbeddingIndexService embeddingIndexer;
    private final MultiAgentGateway gateway;
    private final ChatService chatService;

    // ===================== 记忆/知识/计划 =====================

    private final MemoryService memoryService;
    private final KnowledgeService knowledgeService;
    private final PlanService planService;

    // ===================== 管理服务 =====================

    private final ProviderAdminService providerAdmin;
    private final ProxyManager proxyManager;
    private final AgentAdminService agentAdmin;
    private final McpAdminService mcpAdmin;
    private final PromptTemplateAdminService promptTemplateAdmin;
    private final TaskAdminService taskAdmin;
    private final LocalAuthService localAuth;
    private final UsageStatsService usageStats;
    private final DbBackupService backupService;
    private final ExportService exportService;
    private final UpdateChecker updateChecker;
    private final MarketService marketService;
    private final WebDavSyncService webDavSync;

    // ===================== 语音/限流 =====================

    private final ModelRateGovernor rateGovernor;
    private final TtsService ttsService;
    private final VoiceInputService voiceInput;

    // ===================== 窗口与生命周期 =====================

    private final AppShell appShell;
    private final TrayNotifier trayNotifier;
    private final SystemNotifier systemNotifier;
    private final AppLifecycle appLifecycle;
    private final GlobalHotkeyService globalHotkeys;
    private final KeepAwakeService keepAwake;

    // ===================== 懒构建（FX 阶段） =====================

    /** 主控制器（FXML 控制器工厂返回该实例）；UI 对象均随其首次构建。 */
    private MainController mainController;
    /** 终端抽屉（关闭阶段需要 shutdown；随主控制器构建）。 */
    private TerminalView terminalView;
    /** 快速问答小窗（原 @Lazy：认证通过后装配热键时首次取用）。 */
    private QuickAskWindow quickAsk;
    /** 划词助手（依赖快速问答小窗，同为认证后懒构建）。 */
    private SelectionAssistant selection;

    private boolean closed;

    /**
     * 装配全部无头服务。须在 JavaFX {@code init()}（工具包已就绪、FX 线程尚未启动）中调用；
     * 任何一步失败即抛出，由启动流程终止并交由未捕获异常处理器记录。
     */
    public AppContext() {
        // ---- 存储：云端恢复须先于任何 SQLite 连接建立 ----
        this.paths = new GimPaths();
        RestoreApplier.applyIfPresent(paths.dbFile(), paths.pendingRestoreFile(), paths.backupsDir());
        ConnectionProvider connection = new SqliteConnectionProvider(paths.dbFile());
        this.db = new GimDatabase(connection);
        db.initSchema();
        this.cipher = SecretCipherFactory.create(paths.secretKeyFile());
        this.workspaceProvisioner = new WorkspaceProvisioner(paths);
        workspaceProvisioner.provision();

        // ---- 模型 ----
        this.modelFactory = new AgentScopeModelFactory();
        this.modelResolver = new DefaultModelResolver(db, cipher);
        this.modelProbe = new ModelProbe(modelFactory);

        // ---- 安全与工具 ----
        this.securityAdmin = new SecurityAdminService(db, cipher);
        this.imageGenerationClient = new ImageGenerationClient(() -> new ImageGenerationClient.Config(
                securityAdmin.getImageBaseUrl(),
                securityAdmin.getImageModel(),
                securityAdmin.getImageApiKeyPlain()));
        this.capabilities = new AgentCapabilityResolver(db);
        this.dockerProbe = DockerAvailabilityProbe.handshake();
        this.skillService = new SkillService(paths, db);
        this.appShell = new AppShell();
        this.guardChain = buildGuardChain(paths, db, securityAdmin);
        this.fxHitl = new FxHitlConfirmHandler(appShell);
        this.toolSecurity = new ToolSecurityFacade(guardChain, fxHitl);

        // ---- 调度：先建调度器（不带 JobFactory），执行器就绪后再注入并启动。
        // 这样破除了 gateway → schedulerService → executor → chatService → gateway 的构造环 ----
        this.scheduler = buildQuartzScheduler();
        this.schedulerService = new TaskSchedulerService(scheduler, db.taskDao());
        this.embeddingIndexer = new EmbeddingIndexService(db, this::buildEmbeddingClient);
        this.gateway = buildGateway();
        this.chatService = new ChatService(gateway, db, paths, buildEffectiveModelResolver());
        this.trayNotifier = new TrayNotifier();
        trayNotifier.init();
        this.systemNotifier = new SystemNotifier(trayNotifier, appShell);
        this.taskExecutor = new AgentTaskExecutor(db, chatService, systemNotifier::onTaskCompleted);
        try {
            scheduler.setJobFactory(new AgentTaskJobFactory(taskExecutor));
            scheduler.start();
        } catch (SchedulerException e) {
            throw new IllegalStateException("Quartz 调度器启动失败", e);
        }

        // ---- 记忆/知识/计划 ----
        this.memoryService = new MemoryService(db, gateway);
        this.knowledgeService = new KnowledgeService(db, gateway, embeddingIndexer);
        this.planService = new PlanService(gateway);

        // ---- 管理服务 ----
        this.providerAdmin = new ProviderAdminService(db, cipher, modelFactory, modelResolver, modelProbe);
        this.proxyManager = new ProxyManager(db, cipher);
        proxyManager.apply();
        this.agentAdmin = new AgentAdminService(db, gateway);
        this.mcpAdmin = new McpAdminService(db, agentAdmin);
        this.promptTemplateAdmin = new PromptTemplateAdminService(db);
        this.taskAdmin = new TaskAdminService(db, schedulerService, taskExecutor);
        this.localAuth = new LocalAuthService(paths.authFile());
        this.usageStats = new UsageStatsService(db);

        // ---- 备份/导出/同步/更新 ----
        this.backupService = new DbBackupService(db, paths.backupsDir());
        this.exportService = new ExportService(db);
        this.updateChecker = new UpdateChecker(db, trayNotifier);
        this.marketService = new MarketService(updateChecker, skillService, mcpAdmin);
        this.webDavSync = new WebDavSyncService(db, cipher, paths, backupService);

        // ---- 语音/限流 ----
        this.rateGovernor = new ModelRateGovernor(db);
        this.ttsService = new TtsService(db, cipher);
        this.voiceInput = new VoiceInputService(db, cipher);

        // ---- 窗口与生命周期 ----
        this.appLifecycle = new AppLifecycle(db, trayNotifier, localAuth);
        this.globalHotkeys = new GlobalHotkeyService(db);
        this.keepAwake = new KeepAwakeService(db);
        keepAwake.start();
    }

    // ===================== 启动序列 =====================

    /**
     * 启动期工序：精确对齐原 CommandLineRunner 的 {@code @Order}（50/60/100/200 → 恢复任务）
     * 与 ApplicationReadyEvent 钩子（WebDAV 自动同步）。须在 {@link #runStartupTasks()} 前完成构造。
     */
    public void runStartupTasks() {
        // @Order(50)：启动自动补备份（VACUUM INTO 可能秒级耗时，放后台线程不阻塞主窗出现）
        Thread backup = new Thread(() -> {
            try {
                backupService.backupIfDue();
            } catch (RuntimeException e) {
                log.warn("启动自动备份失败", e);
            }
        }, "startup-backup");
        backup.setDaemon(true);
        backup.start();

        // @Order(60)：后台预拉取沙箱镜像（连通性握手通过后用当前配置镜像；失败只记日志）
        Thread prePull = new Thread(() -> {
            SandboxExecutor executor = new SandboxExecutor(dockerProbe, securityAdmin.getSandboxImage());
            if (!executor.dockerAvailable()) {
                return;
            }
            executor.prePullImage();
        }, "sandbox-prepull");
        prePull.setDaemon(true);
        prePull.start();

        // @Order(100)：播种内置示例技能（幂等）
        skillService.seedBuiltinSkills();

        // @Order(200)：播种内置默认智能体（幂等）
        seedDefaultAgent();

        // 无 @Order（最低优先级，最后执行）：恢复全部 READY 任务
        schedulerService.resumeReadyTasks();

        // ApplicationReadyEvent：WebDAV 自动同步巡检
        webDavSync.startAutoSync();
    }

    // ===================== UI 阶段（FX 线程） =====================

    /**
     * 主控制器：FXML 控制器工厂以此获取单例。首次调用（须在 FX 线程）一次性构建全部 UI 对象；
     * 视图构造即创建 JavaFX 节点，切勿在无头线程调用。
     */
    public synchronized MainController mainController() {
        if (mainController == null) {
            UsageView usageView = new UsageView(usageStats);
            KnowledgeView knowledgeView = new KnowledgeView(knowledgeService, securityAdmin);
            SkillsView skillsView = new SkillsView(skillService, agentAdmin, marketService);
            ExtensionsView extensionsView = new ExtensionsView(skillsView, knowledgeView, mcpAdmin, marketService);
            this.terminalView = new TerminalView(paths);
            TasksView tasksView = new TasksView(taskAdmin, promptTemplateAdmin, agentAdmin, keepAwake, voiceInput);
            tasksView.init();
            SettingsWindow settingsWindow = new SettingsWindow(providerAdmin, paths, mcpAdmin, marketService,
                    securityAdmin, agentAdmin, backupService, db, webDavSync, voiceInput, ttsService, gateway,
                    proxyManager, modelFactory, memoryService, exportService, promptTemplateAdmin, appLifecycle,
                    updateChecker, globalHotkeys, dockerProbe, skillsView, localAuth);
            this.mainController = new MainController(chatService, gateway, settingsWindow, appShell, tasksView,
                    extensionsView, usageView, terminalView, agentAdmin, paths, providerAdmin, securityAdmin,
                    localAuth, appLifecycle, knowledgeService, planService, promptTemplateAdmin, rateGovernor,
                    systemNotifier, voiceInput, ttsService, imageGenerationClient, exportService, db);
        }
        return mainController;
    }

    /** 快速问答小窗（原 @Lazy：认证通过后热键装配时首次取用；须在 FX 线程调用）。 */
    public synchronized QuickAskWindow quickAskWindow() {
        if (quickAsk == null) {
            quickAsk = new QuickAskWindow(chatService, voiceInput);
        }
        return quickAsk;
    }

    /** 划词助手（依赖快速问答小窗；须在 FX 线程调用）。 */
    public synchronized SelectionAssistant selectionAssistant() {
        if (selection == null) {
            selection = new SelectionAssistant(quickAskWindow());
        }
        return selection;
    }

    // ===================== 访问器（供入口装配） =====================

    public AppShell appShell() {
        return appShell;
    }

    public AppLifecycle appLifecycle() {
        return appLifecycle;
    }

    public LocalAuthService localAuthService() {
        return localAuth;
    }

    public UpdateChecker updateChecker() {
        return updateChecker;
    }

    public GlobalHotkeyService globalHotkeyService() {
        return globalHotkeys;
    }

    // ===================== 停机 =====================

    /**
     * 应用终止（FX 线程）：按原 {@code @PreDestroy} 语义停机——热键 → 保持唤醒 → 懒构建的
     * 小窗/终端 → 托盘 → 调度器 → 网关收尾。各步失败仅记日志，保证后续步骤继续执行。
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        runQuietly("全局热键", globalHotkeys::stop);
        runQuietly("保持唤醒", keepAwake::stop);
        QuickAskWindow quick = quickAsk;
        if (quick != null) {
            runQuietly("快速问答小窗", quick::dispose);
        }
        TerminalView terminal = terminalView;
        if (terminal != null) {
            runQuietly("终端抽屉", terminal::shutdown);
        }
        runQuietly("系统托盘", trayNotifier::dispose);
        runQuietly("任务调度器", () -> {
            try {
                scheduler.shutdown(false);
            } catch (SchedulerException e) {
                throw new IllegalStateException(e);
            }
        });
        runQuietly("智能体网关", gateway::close);
    }

    private static void runQuietly(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("{} 关闭失败", what, e);
        }
    }

    // ===================== 装配辅助 =====================

    /** 模型解析适配：智能体定义 + 消息级模型覆盖 → 模型实例；备用模型同提供商按优先级取下一个启用模型。 */
    private MultiAgentGateway.ModelResolver buildModelResolver() {
        return new MultiAgentGateway.ModelResolver() {
            @Override
            public io.agentscope.core.model.Model resolve(AgentDefinition def, String providerCode, String modelCode) {
                return modelFactory.getOrCreate(modelResolver.resolveFor(def, providerCode, modelCode));
            }

            @Override
            public io.agentscope.core.model.Model resolveFallback(AgentDefinition def, String providerCode, String modelCode) {
                ModelSpec fallback = modelResolver.resolveFallback(def, providerCode, modelCode);
                return fallback == null ? null : modelFactory.getOrCreate(fallback);
            }
        };
    }

    /**
     * 生效模型编码解析：复用与运行时一致的选模链（消息级覆盖 → 智能体自带 → 应用默认），
     * 解析失败（如未配默认模型）返回 null，让用量落入「未标注」桶而非丢消息。
     */
    private EffectiveModelResolver buildEffectiveModelResolver() {
        return (agentCode, providerCode, modelCode) -> {
            try {
                String code = agentCode == null || agentCode.isBlank()
                        ? MultiAgentGateway.DEFAULT_AGENT_CODE : agentCode;
                AgentDefinition def = db.agentDao().findByCode(code).orElse(null);
                return modelResolver.resolveFor(def, providerCode, modelCode).modelCode();
            } catch (RuntimeException e) {
                return null;
            }
        };
    }

    /**
     * 向量知识索引器的 embedding 客户端：每次现建（读加密 settings），配置改动即生效；
     * 地址/模型未配置返回 null（索引器降级为不可用）。
     */
    private EmbeddingClient buildEmbeddingClient() {
        String baseUrl = securityAdmin.getKnowledgeEmbeddingBaseUrl();
        String model = securityAdmin.getKnowledgeEmbeddingModel();
        if (baseUrl.isEmpty() || model.isEmpty()) {
            return null;
        }
        return new OpenAiCompatibleEmbeddingClient(baseUrl, securityAdmin.getKnowledgeEmbeddingKeyPlain(), model);
    }

    /** 多智能体网关：运行体惰性装配，本方法只登记装配器（各工具挂载在首次调用时才触发）。 */
    private MultiAgentGateway buildGateway() {
        return new MultiAgentGateway(
                db.agentDao(),
                buildModelResolver(),
                readAgentsMd(paths),
                paths.workspaceDir(),
                capabilities,
                gw -> {
                    // HITL：把工具安全门面的 precheck（含自动通过档）作为网关的确认回调
                    gw.setConfirmCallback(toolSecurity::precheck);
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
                    gw.registerToolTarget(new WebSearchTools(securityAdmin::getWebSearchApiKeyPlain));
                    // 知识检索工具：knowledge_search——向量召回知识块，与 KNOWLEDGE.md 文件树路线并存
                    gw.registerToolTarget(new KnowledgeSearchTools(embeddingIndexer, gw::getAgentId));
                    // 分类记忆工具：memory_write——按主题写入 memory/<分类>.md（可搜索、随沉淀合并进 MEMORY.md；
                    // 记忆关闭时网关侧与框架记忆工具同门禁跳过）
                    gw.registerToolTarget(new MemoryWriteTool(gw.getWorkspace()));
                    // 图像生成工具：generate_image——配置实时取用，产物落 workspace/generated 后由模型以 Markdown 引用展示
                    gw.registerToolTarget(new ImageGenerationTools(imageGenerationClient, () -> paths.generatedDir()));
                    // Docker 沙箱：自动通过档且设置开启时，Shell 走一次性容器执行；握手失败自动回退本地
                    gw.setShellToolFactory((localFs, ws, project, autoApprove) -> new SandboxShellTool(
                            localFs, ws, project, autoApprove,
                            securityAdmin::isSandboxDockerEnabled,
                            securityAdmin::getSandboxImage,
                            dockerProbe));
                });
    }

    /** Quartz 裸装配：内存作业存储（原 spring.quartz.job-store-type=memory）+ 10 线程池。 */
    private static Scheduler buildQuartzScheduler() {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "JworkScheduler");
        props.setProperty("org.quartz.scheduler.instanceId", "NON_CLUSTERED");
        props.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "10");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        try {
            return new StdSchedulerFactory(props).getScheduler();
        } catch (SchedulerException e) {
            throw new IllegalStateException("Quartz 调度器初始化失败", e);
        }
    }

    /** 工具守卫链：危险命令黑名单 + Shell 程序白名单 + 文件路径白名单（扩展项实时读库）。 */
    private static GuardChain buildGuardChain(GimPaths paths, GimDatabase db, SecurityAdminService securityAdmin) {
        Path allowed = paths.root().resolve("allowed");
        try {
            Files.createDirectories(allowed);
        } catch (IOException ignored) {
            // 目录创建失败不阻断，白名单仍可使用
        }
        List<ToolGuard> guards = List.of(
                // 危险命令黑名单：每次评估实时读库，设置页增删改/启停无需重启即生效
                new DangerousCommandPolicy(securityAdmin::listDangerousRules),
                // shell 只能启动白名单内的程序；扩展来源 = 用户文件 + 设置页新增（实时合并）
                new ExecutableAllowlistPolicy(ExecutableAllowlistPolicy.DEFAULT_ALLOWED,
                        shellExtraSupplier(paths, securityAdmin)),
                // 动态授权根：各智能体项目目录 + 设置页新增目录，实时并入白名单
                new PathWhitelistPolicy(List.of(allowed, paths.workspaceDir()),
                        pathExtraSupplier(db, securityAdmin))
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
            } catch (InvalidPathException ignored) {
                // 畸形路径不参与白名单
            }
        }
        return roots;
    }

    /** 从 AppSetting {@link HarnessAgentGateway#SETTING_CONTEXT_MAX_TOKENS} 读取上下文窗口上限；缺失或非法（非正整数）则返回空（用默认）。 */
    private static Optional<Integer> readContextMaxTokens(GimDatabase db) {
        return db.settingDao().getValue(HarnessAgentGateway.SETTING_CONTEXT_MAX_TOKENS).flatMap(raw -> {
            try {
                int v = Integer.parseInt(raw.trim());
                return v > 0 ? Optional.of(v) : Optional.empty();
            } catch (NumberFormatException e) {
                return Optional.empty();
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
     *   <li>{@link HarnessAgentGateway#SETTING_MEMORY_ENABLED}：{@code "false"} 即关闭（其余/缺省为开）；</li>
     *   <li>{@link HarnessAgentGateway#SETTING_MEMORY_FLUSH_MODE}：{@code always}（默认）/ {@code throttled}（≥ 10min）/ {@code never}。</li>
     * </ul>
     */
    private static void applyMemorySettings(HarnessAgentGateway gw, GimDatabase db) {
        boolean enabled = readBool(db, HarnessAgentGateway.SETTING_MEMORY_ENABLED, true);
        gw.setMemoryEnabled(enabled);
        String flushMode = db.settingDao().getValue(HarnessAgentGateway.SETTING_MEMORY_FLUSH_MODE)
                .map(v -> v.trim().toLowerCase(java.util.Locale.ROOT))
                .orElse("always");
        MemoryConfig.FlushTrigger trigger = switch (flushMode) {
            case "never" -> MemoryConfig.FlushTrigger.never();
            case "throttled" -> MemoryConfig.FlushTrigger.throttled(java.time.Duration.ofMinutes(10));
            default -> MemoryConfig.FlushTrigger.always();
        };
        gw.setMemoryConfig(MemoryConfig.builder().flushTrigger(trigger).build());
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

    /**
     * 启动时播种内置默认智能体（幂等）。人格/模型/工作区字段留空，即沿用 AGENTS.md 人格与应用默认模型。
     * <p>技能在出生时就<b>显式声明</b>为当时已启用的技能（播种工序排在其前面），
     * 所以后续装配不必隐式继承任何东西；用户可在智能体表单里自由增减。</p>
     */
    private void seedDefaultAgent() {
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
    }
}
