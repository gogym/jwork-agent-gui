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

package com.gettyio.jwork.agent.ui.i18n;

import com.gettyio.jwork.agent.ui.prefs.UserPrefs;
import javafx.application.Platform;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 国际化工具：简体中文 / 繁体中文 / 英文。
 * <p>资源包位于 classpath {@code i18n/messages*.properties}（UTF-8，JDK17 默认按 UTF-8 读取）。
 * 当前语言持久化在 {@link UserPrefs} 的 {@code lang} 键；切换语言时重新加载资源包，
 * 并回调所有已注册的语言变更监听器（在 FX 线程执行），使已打开界面即时刷新。</p>
 *
 * @author gogym
 */
public final class I18n {

    /** 支持的界面语言；{@code nativeName} 恒以本族语显示，不随界面语言变化。 */
    public enum Language {
        ZH_CN("简体中文", Locale.SIMPLIFIED_CHINESE),
        ZH_TW("繁體中文", Locale.TRADITIONAL_CHINESE),
        EN("English", Locale.ENGLISH);

        private final String nativeName;
        private final Locale locale;

        Language(String nativeName, Locale locale) {
            this.nativeName = nativeName;
            this.locale = locale;
        }

        public String nativeName() {
            return nativeName;
        }

        public Locale locale() {
            return locale;
        }
    }

    private static final String BUNDLE_BASE = "i18n.messages";

    private static volatile ResourceBundle bundle;
    private static volatile Language current;
    private static final List<Consumer<Language>> LISTENERS = new CopyOnWriteArrayList<>();

    private I18n() {
    }

    public static Language current() {
        ensureInit();
        return current;
    }

    /** 取译文；无参数时直接返回，有参数时用 {@link MessageFormat} 填充 {@code {0}} 等占位符。 */
    public static String t(String key, Object... args) {
        String pattern = raw(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return MessageFormat.format(pattern, args);
        } catch (IllegalArgumentException e) {
            return pattern;
        }
    }

    /** 切换语言：持久化偏好、重载资源包、通知全部监听器（FX 线程）。 */
    public static synchronized void setLanguage(Language lang) {
        ensureInit();
        if (lang == current) {
            return;
        }
        current = lang;
        UserPrefs.put("lang", lang.name());
        bundle = ResourceBundle.getBundle(BUNDLE_BASE, lang.locale());
        notifyListeners(lang);
    }

    /** 注册语言变更监听器（弱语义：随单例存活整个进程）。 */
    public static void onChange(Consumer<Language> listener) {
        LISTENERS.add(listener);
    }

    private static void notifyListeners(Language lang) {
        for (Consumer<Language> l : LISTENERS) {
            Platform.runLater(() -> l.accept(lang));
        }
    }

    private static void ensureInit() {
        if (bundle != null) {
            return;
        }
        synchronized (I18n.class) {
            if (bundle != null) {
                return;
            }
            current = resolveInitial();
            bundle = ResourceBundle.getBundle(BUNDLE_BASE, current.locale());
        }
    }

    private static Language resolveInitial() {
        String saved = UserPrefs.get("lang", null);
        if (saved != null) {
            try {
                return Language.valueOf(saved);
            } catch (IllegalArgumentException ignored) {
                // 非法值回退到自动检测
            }
        }
        return detect();
    }

    /** 依系统默认 Locale 推断初始语言。 */
    private static Language detect() {
        Locale d = Locale.getDefault();
        if ("zh".equals(d.getLanguage())) {
            String c = d.getCountry();
            if ("TW".equals(c) || "HK".equals(c) || "MO".equals(c)) {
                return Language.ZH_TW;
            }
            return Language.ZH_CN;
        }
        return Language.EN;
    }

    private static ResourceBundle bundleOrThrow() {
        ensureInit();
        return bundle;
    }

    private static String raw(String key) {
        try {
            return bundleOrThrow().getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }
}
