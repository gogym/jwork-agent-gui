package com.gettyio.gim.agent.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 用户偏好存储：{@code ~/.gim-agent/theme.properties}，
 * 供 UiTheme（模式/字号）与 I18n（语言）共享读写，避免多文件互相覆盖。
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
                props.store(out, "gim-agent preferences");
            }
        } catch (IOException e) {
            // 保存失败仅影响下次启动的记忆，不阻断当前使用
        }
    }

    private static Path file() {
        return Path.of(System.getProperty("user.home"), ".gim-agent", "theme.properties");
    }
}
