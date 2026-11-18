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

package com.gettyio.gim.agent.domain.util;

import org.slf4j.Logger;

/**
 * 静默关闭单源：清理路径上的 close 失败只告警不抛。
 *
 * @author gogym
 */
public final class Closeables {

    /** 关闭失败仅 warn（带堆栈），不影响主流程。 */
    public static void quietly(AutoCloseable target, Logger log, String message) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (Exception e) {
            log.warn(message, e);
        }
    }

    private Closeables() {
    }
}