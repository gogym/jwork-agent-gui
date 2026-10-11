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
 * 启用状态，数据库中以 0/1 存储
 *
 * @author gogym
 */
public final class EnabledStatus {

    public static final int DISABLED = 0;
    public static final int ENABLED = 1;

    private EnabledStatus() {
    }

    public static boolean isEnabled(Integer value) {
        return value != null && value == ENABLED;
    }
}
