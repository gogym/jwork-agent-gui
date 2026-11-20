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

package com.gettyio.jwork.agent.ui.prefs;

import com.gettyio.jwork.agent.core.JworkPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 用户偏好存储：数据根目录下的 {@code theme.properties}（随 {@link JworkPaths} 解析，默认 {@code ~/.jwork/theme.properties}），
 * 供 UiTheme（模式/字号）与 I18n（语言）共享读写，避免多文件互相覆盖。
 *
 * @author gogym
 */
public final class UserPrefs {

    private static Properties props;

    private UserPrefs() {
    }

    public static synchronized String get(String key, String def) {
        load();
        String v = props.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    public static synchronized void put(String key, String value) {
        load();
        props.setProperty(key, value);
        save();
    }

    private static void load() {
        if (props != null) {
            return;
        }
        props = new Properties();
        Path f = file();
        if (Files.exists(f)) {
            try (InputStream in = Files.newInputStream(f)) {
                props.load(in);
            } catch (IOException e) {
                // 偏好文件损坏时以默认值启动
            }
        }
    }

    private static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            try (OutputStream out = Files.newOutputStream(f)) {
                props.store(out, "jwork-agent preferences");
            }
        } catch (IOException e) {
            // 保存失败仅影响下次启动的记忆，不阻断当前使用
        }
    }

    private static Path file() {
        return new JworkPaths().root().resolve("theme.properties");
    }
}
