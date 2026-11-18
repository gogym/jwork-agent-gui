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

package com.gettyio.jwork.agent.domain.util;

import java.util.Locale;

/**
 * 操作系统平台单源判定：全工程不再各自读 {@code os.name} 猜平台。
 *
 * @author gogym
 */
public enum OsPlatform {

    WINDOWS("windows"),
    MACOS("macos"),
    LINUX("linux"),
    /** 识别不出时的兜底（更新通道等按空串处理）。 */
    OTHER("");

    private final String code;

    OsPlatform(String code) {
        this.code = code;
    }

    /** 更新通道/下载 URL 模板用的平台码：windows/macos/linux，未知为空串。 */
    public String code() {
        return code;
    }

    public boolean isWindows() {
        return this == WINDOWS;
    }

    /** 当前进程所在平台（os.name 运行期不变，解析一次缓存）。 */
    public static OsPlatform current() {
        return CURRENT;
    }

    /** 原始 os.name（仅展示用途，如关于页）。 */
    public static String rawName() {
        return System.getProperty("os.name", "?");
    }

    private static final OsPlatform CURRENT = parse(System.getProperty("os.name", ""));

    private static OsPlatform parse(String osName) {
        String name = osName.toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return MACOS;
        }
        if (name.contains("nux") || name.contains("nix")) {
            return LINUX;
        }
        return OTHER;
    }
}