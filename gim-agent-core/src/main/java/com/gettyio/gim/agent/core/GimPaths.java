package com.gettyio.gim.agent.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地数据目录约定（本地优先，数据自持）：
 * <pre>
 * ~/.jwork/
 *   jwork.db
 *   secret.key
 *   workspace/
 *   logs/
 *   backups/
 * </pre>
 * 根目录可通过构造参数覆盖（测试/自定义）。
 */
public class GimPaths {

    public static final String DEFAULT_ROOT_NAME = ".jwork";

    private final Path root;

    public GimPaths() {
        this(Paths.get(System.getProperty("user.home"), DEFAULT_ROOT_NAME));
    }

    public GimPaths(Path root) {
        this.root = root.toAbsolutePath();
    }

    public Path root() {
        return root;
    }

    public Path dbFile() {
        return root.resolve("jwork.db");
    }

    public Path secretKeyFile() {
        return root.resolve("secret.key");
    }

    public Path workspaceDir() {
        return root.resolve("workspace");
    }

    public Path logsDir() {
        return root.resolve("logs");
    }

    /** 数据库备份目录：VACUUM INTO 快照按时间命名存放，滚动保留最近若干份 */
    public Path backupsDir() {
        return root.resolve("backups");
    }

    public Path skillsDir() {
        return workspaceDir().resolve("skills");
    }

    public Path knowledgeDir() {
        return workspaceDir().resolve("knowledge");
    }

    /** 图片附件落盘根目录：按 {@code <sessionId>/<uuid>.<ext>} 归档，随会话删除而清理 */
    public Path attachmentsDir() {
        return workspaceDir().resolve("attachments");
    }

    public Path agentsMd() {
        return workspaceDir().resolve("AGENTS.md");
    }

    public Path memoryMd() {
        return workspaceDir().resolve("MEMORY.md");
    }
}
