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

package com.gettyio.jwork.agent.domain.enums;

/**
 * 会话状态
 *
 * @author gogym
 */
public enum SessionStatus {

    ACTIVE("ACTIVE"),
    CLOSED("CLOSED");

    private final String code;

    SessionStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** 按持久化码解析（对应 {@link #getCode()}，忽略大小写）；未知值抛 {@link IllegalArgumentException}。 */
    public static SessionStatus fromCode(String code) {
        for (SessionStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("不支持的会话状态: " + code);
    }
}
