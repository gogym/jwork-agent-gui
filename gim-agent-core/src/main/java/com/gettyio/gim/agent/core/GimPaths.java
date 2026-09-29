package com.gettyio.gim.agent.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地数据目录约定（本地优先，数据自持）：
 * <pre>
 * ~/.gim-agent/
 *   gim-agent.db
 *   secret.key
 *   workspace/
 *   logs/
 * </pre>
 * 根目录可通过构造参数覆盖（测试/自定义）。
 */
public class GimPaths {

    public static final String DEFAULT_ROOT_NAME = ".gim-agent";

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
        return root.resolve("gim-agent.db");
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

    public Path skillsDir() {
        return workspaceDir().resolve("skills");
    }

    public Path knowledgeDir() {
        return workspaceDir().resolve("knowledge");
    }

    public Path agentsMd() {
        return workspaceDir().resolve("AGENTS.md");
    }

    public Path memoryMd() {
        return workspaceDir().resolve("MEMORY.md");
    }
}
