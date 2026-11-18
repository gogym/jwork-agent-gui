/*
 * Copyright 2026 gogym
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gettyio.jwork.agent.tools.sandbox;

import com.gettyio.jwork.agent.domain.util.OsPlatform;
import java.io.File;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Docker 隔离环境是否可用的探针。
 * <p>抽成函数式接口以便注入与单测打桩；工程不引入任何 Docker SDK。两级探测并存：
 * {@link #onPath()} 仅做 PATH 可执行文件存在性这类零副作用的轻量探测，
 * {@link #handshake()} 真实调用 docker CLI 握手守护进程（覆盖「CLI 在但引擎没跑」的空窗），
 * 带 TTL 缓存避免频繁付出进程启动成本，是沙箱落地与设置页状态的默认探测。</p>
 *
 * @author gogym
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
                boolean windows = OsPlatform.current().isWindows();
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

    /** 握手探测默认超时：docker CLI 子进程等待上限。 */
    Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);

    /** 握手结果默认缓存时长：工具调用路径上避免每次执行都重复启动 CLI 进程。 */
    Duration HANDSHAKE_TTL = Duration.ofSeconds(30);

    /**
     * 真实连通性握手 + TTL 缓存：{@code docker version} 取 Server 段版本，超时内退出码为 0
     * 才算可用。默认 5s 超时、30s 缓存；探测异常一律收敛为不可用。
     */
    static DockerAvailabilityProbe handshake() {
        return handshake(HANDSHAKE_TIMEOUT, HANDSHAKE_TTL, DockerAvailabilityProbe::pingDefault);
    }

    /**
     * 自定义超时/缓存的握手探测；{@code ttl <= 0} 表示不缓存（每次调用重新握手，
     * 供设置页「立即检测」等需要新鲜结果的场景）。
     */
    static DockerAvailabilityProbe handshake(Duration timeout, Duration ttl) {
        return handshake(timeout, ttl, DockerAvailabilityProbe::pingDefault);
    }

    /** 握手核心（探针可注入以便单测打桩）：线程安全，结果按 TTL 缓存。 */
    static DockerAvailabilityProbe handshake(Duration timeout, Duration ttl, DockerPing ping) {
        Duration effectiveTimeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? HANDSHAKE_TIMEOUT : timeout;
        long ttlNanos = ttl == null || ttl.isNegative() ? 0L : ttl.toNanos();
        DockerPing pinger = ping == null ? DockerAvailabilityProbe::pingDefault : ping;
        return new DockerAvailabilityProbe() {
            private long lastCheckNanos = Long.MIN_VALUE;
            private boolean lastResult;

            /** TTL 内命中缓存直接返回；否则真实握手探测一次，超时/异常一律收敛为不可用。 */
            @Override
            public synchronized boolean available() {
                long now = System.nanoTime();
                if (ttlNanos > 0 && lastCheckNanos != Long.MIN_VALUE
                        && now - lastCheckNanos < ttlNanos) {
                    return lastResult;
                }
                boolean ok;
                try {
                    ok = pinger.ping(effectiveTimeout);
                } catch (RuntimeException e) {
                    ok = false;
                }
                lastResult = ok;
                lastCheckNanos = now;
                return ok;
            }
        };
    }

    /** 默认握手实现：起一个 docker CLI 子进程读 Server 版本，退出码 0 即守护进程可达。 */
    static boolean pingDefault(Duration timeout) {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return false;
            }
            try (InputStream in = p.getInputStream()) {
                // 输出极小，随读随弃，确保管道排空后再取退出码
                in.readAllBytes();
            }
            return p.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // 未安装 CLI（IOException）等一切异常收敛为不可用
            return false;
        }
    }

    /** 握手探针的函数式抽象：向真实 Docker 环境发一次轻量探询。 */
    @FunctionalInterface
    interface DockerPing {
        boolean ping(Duration timeout);
    }
}
