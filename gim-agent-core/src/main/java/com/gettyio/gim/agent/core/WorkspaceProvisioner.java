package com.gettyio.gim.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工作区初始化：确保 Harness 工作区目录与默认人格/记忆文件存在（幂等）
 */
public class WorkspaceProvisioner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceProvisioner.class);

    private static final String DEFAULT_AGENTS_MD = """
            # Gim Agent

            你是运行在本地桌面的个人 AI 助理。遵循以下原则：

            - 本地优先：优先使用用户授权目录内的文件与工具。
            - 安全确认：执行文件写入、shell 命令等副作用操作前，触发用户确认。
            - 记忆沉淀：将长期有价值的事实写入 MEMORY.md。
            - 定时能力：用户要求定期/到点执行某事时，使用 schedule_task 工具（Quartz cron 表达式），
              并用 list_tasks / cancel_task 管理与查询任务。
            - 技能复用：workspace/skills/ 下已启用的技能（SKILL.md）定义了专项任务规范，命中时应遵循。
            - 简洁直接：回答以可执行为先，避免冗余。
            """;

    private static final String DEFAULT_MEMORY_MD = """
            # 长期记忆

            （由 Harness 自动维护，记录用户偏好、常用事实与约定）
            """;

    private final GimPaths paths;

    public WorkspaceProvisioner(GimPaths paths) {
        this.paths = paths;
    }

    public void provision() {
        try {
            createDirs();
            ensureFile(paths.agentsMd(), DEFAULT_AGENTS_MD);
            ensureFile(paths.memoryMd(), DEFAULT_MEMORY_MD);
            log.info("工作区就绪: {}", paths.workspaceDir());
        } catch (IOException e) {
            throw new UncheckedIOException("初始化工作区失败", e);
        }
    }

    private void createDirs() throws IOException {
        Files.createDirectories(paths.root());
        Files.createDirectories(paths.workspaceDir());
        Files.createDirectories(paths.skillsDir());
        Files.createDirectories(paths.knowledgeDir());
        Files.createDirectories(paths.logsDir());
    }

    private void ensureFile(Path file, String defaultContent) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, defaultContent, StandardCharsets.UTF_8);
            log.info("生成默认文件: {}", file);
        }
    }
}
