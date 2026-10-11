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

package com.gettyio.gim.agent.tools.sandbox;

/**
 * 自动通过档的文件系统隔离落地模式。
 * <p>方案 B 下「自动通过」档不弹框直接放行副作用工具，为避免其写操作直接落到用户真实目录，
 * 规划以 Docker 容器内工作目录承接；当隔离依赖不可用时优雅回退本地工作目录并提示用户。</p>
 *
 * @author gogym
 */
public enum SandboxMode {

    /** Docker 容器内隔离文件系统：自动通过档的写副作用落在容器工作目录。 */
    DOCKER,

    /** 本地工作目录：未开启隔离、或 Docker 依赖缺失时的回退落地。 */
    LOCAL_FALLBACK
}
