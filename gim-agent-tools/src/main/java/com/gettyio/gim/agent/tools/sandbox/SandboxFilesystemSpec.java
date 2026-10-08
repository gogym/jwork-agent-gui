package com.gettyio.gim.agent.tools.sandbox;

import java.nio.file.Path;

/**
 * 自动通过档的文件系统落地规格：本次工具副作用应落在哪个根目录、以何种隔离模式，
 * 以及回退时供 UI 渲染的一句话提示（i18n 键，正常隔离时为 {@code null}）。
 * <p>由 {@link SandboxProvisioner} 探测环境后产出，是「意图 → 实际落地」的不可变结论。</p>
 *
 * @param mode   隔离落地模式
 * @param root   实际写操作落地的根目录（绝对路径）
 * @param notice 回退提示的 i18n 键；{@code null} 表示无需提示（未开启隔离或隔离成功）
 */
public record SandboxFilesystemSpec(SandboxMode mode, Path root, String notice) {

    /** 隔离成功：落 Docker 容器工作目录，无提示。 */
    public static SandboxFilesystemSpec docker(Path containerWorkspace) {
        return new SandboxFilesystemSpec(SandboxMode.DOCKER, containerWorkspace, null);
    }

    /** 回退本地工作目录；{@code notice} 为需要向用户说明的 i18n 键，可为 {@code null}。 */
    public static SandboxFilesystemSpec localFallback(Path localWorkspace, String notice) {
        return new SandboxFilesystemSpec(SandboxMode.LOCAL_FALLBACK, localWorkspace, notice);
    }

    /** 是否处于真正的隔离落地（Docker）。 */
    public boolean isolated() {
        return mode == SandboxMode.DOCKER;
    }

    /** 是否需要向用户提示（发生了带说明的回退）。 */
    public boolean shouldNotice() {
        return notice != null && !notice.isBlank();
    }
}
