package com.gettyio.gim.agent.tools.sandbox;

import java.io.File;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Docker 隔离环境是否可用的探针。
 * <p>抽成函数式接口以便注入与单测打桩；工程不引入任何 Docker SDK，默认实现
 * {@link #onPath()} 仅做 PATH 可执行文件存在性这类零副作用的轻量探测，真正的连通性握手留待后续接入。</p>
 */
@FunctionalInterface
public interface DockerAvailabilityProbe {

    /**
     * Docker 守护进程/CLI 是否就绪可用。实现不得抛出受检异常；探测失败应返回 {@code false}
     * 以交由上层优雅回退本地。
     */
    boolean available();

    /** 基于 PATH 是否存在 docker 可执行文件的轻量默认探针（骨架实现，不拉起容器、不做网络握手）。 */
    static DockerAvailabilityProbe onPath() {
        return () -> {
            try {
                String path = System.getenv("PATH");
                if (path == null || path.isBlank()) {
                    return false;
                }
                boolean windows = System.getProperty("os.name", "")
                        .toLowerCase(Locale.ROOT).contains("win");
                String[] names = windows
                        ? new String[]{"docker.exe", "docker.com"}
                        : new String[]{"docker"};
                for (String dir : path.split(Pattern.quote(File.pathSeparator))) {
                    if (dir.isBlank()) {
                        continue;
                    }
                    for (String name : names) {
                        if (new File(dir, name).isFile()) {
                            return true;
                        }
                    }
                }
                return false;
            } catch (RuntimeException e) {
                // 探测异常一律视为不可用，交由上层回退，绝不向外抛出
                return false;
            }
        };
    }
}
