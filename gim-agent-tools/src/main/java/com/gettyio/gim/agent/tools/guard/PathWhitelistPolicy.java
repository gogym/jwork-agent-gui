package com.gettyio.gim.agent.tools.guard;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 文件路径白名单：副作用文件操作仅允许在授权目录内，越权触发人工确认。
 */
public class PathWhitelistPolicy implements ToolGuard {

    /** 授权根目录（绝对路径前缀），来自用户显式授权 + 默认 allowed 目录 */
    private final List<Path> allowedRoots;

    public PathWhitelistPolicy(List<Path> allowedRoots) {
        this.allowedRoots = allowedRoots.stream().map(Path::toAbsolutePath).toList();
    }

    @Override
    public GuardResult inspect(GuardContext ctx) {
        // 仅对文件类工具生效
        String tool = ctx.toolName() == null ? "" : ctx.toolName().toLowerCase();
        if (!tool.contains("file") && !tool.contains("write") && !tool.contains("edit")) {
            return null;
        }
        String input = ctx.toolInput();
        if (input == null || input.isBlank()) {
            return null;
        }
        Path target = Paths.get(input).toAbsolutePath().normalize();
        for (Path root : allowedRoots) {
            if (target.startsWith(root.normalize())) {
                return GuardResult.allow("位于授权目录内：" + root);
            }
        }
        return GuardResult.ask("目标路径在授权目录之外，需用户确认：" + target);
    }

    @Override
    public int order() {
        return 20;
    }
}
