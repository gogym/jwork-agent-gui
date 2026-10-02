package com.gettyio.gim.agent.tools.guard;

import com.gettyio.gim.agent.core.chat.ToolCallsCodec;
import com.gettyio.gim.agent.core.chat.ToolCallsCodec.ToolCallView;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * 文件路径白名单：对副作用文件操作做授权边界标注。
 * <p>注意它不再对「白名单内」的操作静默放行——方案 B 下每一次写操作都要经 HITL
 * diff 预览确认（重复打扰由「总是允许」规则短路）；白名单的作用是把「越界路径」
 * 标注进风险提示，让用户知道这次确认的对象超出了授权目录。</p>
 */
public class PathWhitelistPolicy implements ToolGuard {

    /** 静态授权根目录（绝对路径前缀），来自用户显式授权 + 默认 allowed 目录 */
    private final List<Path> allowedRoots;
    /** 动态授权根（如各智能体配置的项目目录），每次评估时取最新快照 */
    private final Supplier<Collection<Path>> extraRoots;

    public PathWhitelistPolicy(List<Path> allowedRoots) {
        this(allowedRoots, List::of);
    }

    public PathWhitelistPolicy(List<Path> allowedRoots, Supplier<Collection<Path>> extraRoots) {
        this.allowedRoots = allowedRoots.stream().map(Path::toAbsolutePath).toList();
        this.extraRoots = extraRoots;
    }

    @Override
    public GuardResult inspect(GuardContext ctx) {
        // 仅对文件类工具生效
        String tool = ctx.toolName() == null ? "" : ctx.toolName().toLowerCase();
        if (!tool.contains("file") && !tool.contains("write") && !tool.contains("edit")) {
            return null;
        }
        List<String> outside = new ArrayList<>();
        for (ToolCallView call : ToolCallsCodec.decode(ctx.toolInput())) {
            String path = call.text("path");
            if (path == null || path.isBlank()) {
                continue;
            }
            Path target;
            try {
                target = Paths.get(path).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                continue; // 畸形路径交给文件系统层拒绝，这里不误伤
            }
            if (!inAllowed(target)) {
                outside.add(target.toString());
            }
        }
        if (outside.isEmpty()) {
            // 全部在授权目录内：不表态，交给链尾默认询问（仍展示 diff 确认）
            return null;
        }
        return GuardResult.ask("目标路径在授权目录之外，请谨慎确认：" + String.join("、", outside));
    }

    /** 判断路径是否落在任一授权根（静态 + 动态快照）内。 */
    private boolean inAllowed(Path target) {
        for (Path root : allowedRoots) {
            if (target.startsWith(root.normalize())) {
                return true;
            }
        }
        for (Path root : extraRoots.get()) {
            if (root != null && target.startsWith(root.toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int order() {
        return 20;
    }
}
