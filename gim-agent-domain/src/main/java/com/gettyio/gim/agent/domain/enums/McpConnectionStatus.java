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

package com.gettyio.gim.agent.domain.enums;

/**
 * MCP 服务连接状态（落库值即枚举名）。
 *
 * @author gogym
 */
public enum McpConnectionStatus {

    /** 尚未探测。 */
    UNKNOWN,

    /** 最近一次探测/注册成功。 */
    CONNECTED,

    /** 最近一次探测/注册失败。 */
    DISCONNECTED;

    /** 按连通与否取状态。 */
    public static McpConnectionStatus of(boolean connected) {
        return connected ? CONNECTED : DISCONNECTED;
    }
}