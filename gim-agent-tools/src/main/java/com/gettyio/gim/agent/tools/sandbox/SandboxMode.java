package com.gettyio.gim.agent.tools.sandbox;

/**
 * 自动通过档的文件系统隔离落地模式。
 * <p>方案 B 下「自动通过」档不弹框直接放行副作用工具，为避免其写操作直接落到用户真实目录，
 * 规划以 Docker 容器内工作目录承接；当隔离依赖不可用时优雅回退本地工作目录并提示用户。</p>
 */
public enum SandboxMode {

    /** Docker 容器内隔离文件系统：自动通过档的写副作用落在容器工作目录。 */
    DOCKER,

    /** 本地工作目录：未开启隔离、或 Docker 依赖缺失时的回退落地。 */
    LOCAL_FALLBACK
}
