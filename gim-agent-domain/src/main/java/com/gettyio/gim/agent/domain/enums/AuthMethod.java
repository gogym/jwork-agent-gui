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
 * API 认证方式（部分第三方厂商用 Bearer 头代替 x-api-key）
 *
 * @author gogym
 */
public enum AuthMethod {

    /** x-api-key 请求头，Anthropic 原生方式 */
    X_API_KEY,

    /** Authorization: Bearer 请求头 */
    BEARER_TOKEN;

    /** 按名称解析（忽略大小写、去首尾空白）；{@code null}/空白或未知值均回退 {@link #X_API_KEY}（不抛异常）。 */
    public static AuthMethod fromCode(String code) {
        if (code == null || code.isBlank()) {
            return X_API_KEY;
        }
        for (AuthMethod method : values()) {
            if (method.name().equalsIgnoreCase(code.trim())) {
                return method;
            }
        }
        return X_API_KEY;
    }
}
