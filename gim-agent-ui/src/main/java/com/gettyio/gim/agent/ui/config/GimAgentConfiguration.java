package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.core.WorkspaceProvisioner;
import com.gettyio.gim.agent.core.chat.AgentGateway;
import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.HarnessAgentGateway;
import com.gettyio.gim.agent.core.model.AgentScopeModelFactory;
import com.gettyio.gim.agent.domain.GimDatabase;
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
import io.agentscope.core.model.Model;
import org.quartz.Scheduler;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 应用级 Bean 装配：把 domain/core/tools/scheduler 的组件注册为 Spring 单例。
 */
@Configuration
public class GimAgentConfiguration {

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

    @Bean
    public AgentGateway agentGateway(GimPaths paths,
                                     DefaultModelResolver resolver,
                                     AgentScopeModelFactory modelFactory,
                                     ToolSecurityFacade toolSecurityFacade,
                                     SkillService skillService,
                                     ScheduleTools scheduleTools) {
        java.util.function.Supplier<Model> modelSupplier =
                () -> modelFactory.getOrCreate(resolver.resolve());
        HarnessAgentGateway gateway =
                new HarnessAgentGateway("gim-agent", readAgentsMd(paths), modelSupplier, paths.workspaceDir());
        // HITL：把工具安全门面的 precheck 作为网关的确认回调
        gateway.setConfirmCallback(toolSecurityFacade::precheck);
        // Skills：挂载 workspace/skills 作为 Harness 技能仓库
        gateway.setSkillsDir(skillService.skillsRoot());
        // 自定义工具：自然语言设定定时任务
        gateway.registerToolTarget(scheduleTools);
        return gateway;
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
                                                     AgentScopeModelFactory modelFactory) {
        return new ProviderAdminService(db, cipher, modelFactory);
    }

    @Bean
    public TaskAdminService taskAdminService(GimDatabase db, TaskSchedulerService schedulerService,
                                             ChatService chatService) {
        return new TaskAdminService(db, schedulerService, chatService);
    }

    @Bean
    public McpAdminService mcpAdminService(GimDatabase db) {
        return new McpAdminService(db);
    }

    @Bean
    public SecurityAdminService securityAdminService(GimDatabase db) {
        return new SecurityAdminService(db);
    }

    @Bean
    public ChatService chatService(AgentGateway agentGateway, GimDatabase db) {
        return new ChatService(agentGateway, db);
    }

    @Bean
    public GuardChain guardChain(GimPaths paths, GimDatabase db) {
        Path allowed = paths.root().resolve("allowed");
        try {
            Files.createDirectories(allowed);
        } catch (IOException ignored) {
            // 目录创建失败不阻断，白名单仍可使用
        }
        List<ToolGuard> guards = List.of(
                new DangerousCommandPolicy(),
                new PathWhitelistPolicy(List.of(allowed, paths.workspaceDir()))
        );
        return new GuardChain(guards, db.permissionRuleDao());
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

    /** 启动时播种内置示例技能（幂等） */
    @Bean
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
