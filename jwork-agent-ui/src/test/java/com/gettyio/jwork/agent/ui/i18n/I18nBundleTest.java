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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语言包一致性守卫：四份 messages*.properties 的键集必须完全对齐，且占位符/撇号写法必须与
 * {@link I18n#t(String, Object...)} 的取值方式匹配（无参数时原样返回、有参数时走 MessageFormat）。
 * <p>MessageFormat 会把未加倍的 {@code '} 当作引用符起始：带 {@code {0}} 的键里写 {@code agent's}
 * 会静默丢字（输出 "agents"），而不带占位符的键根本不经 MessageFormat，写 {@code agent''s} 就会
 * 原样显示两个撇号。两种错法都不会抛异常，只能靠本测试拦下。</p>
 *
 * @author gogym
 */
class I18nBundleTest {

    private static final String BUNDLE_BASE = "i18n.messages";

    /** 四个语言包对应的 Locale：默认包（英文）+ en + zh_CN + zh_TW。 */
    private static final List<Locale> LOCALES = List.of(Locale.ROOT, Locale.ENGLISH,
            Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE);

    /** 形如 {@code {0}}、{@code {12}} 的占位符。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    /** 落单（未加倍）的撇号：两侧都不是撇号。 */
    private static final Pattern LONE_APOSTROPHE = Pattern.compile("(?<!')'(?!')");

    /**
     * Java 源码里的完整取键写法：字面量后紧跟 {@code ,} 或 {@code )}。
     * 拼接式取键（{@code I18n.t("settings.secret." + token)}）的字面量后是 {@code +}，因此天然不匹配。
     */
    private static final Pattern STATIC_KEY_USAGE = Pattern.compile("I18n\\.t\\(\\s*\"([A-Za-z0-9_.]+)\"\\s*[,)]");

    @Test
    void allLocalesHaveTheSameKeys() {
        Set<String> reference = keys(Locale.ROOT);
        List<String> problems = new ArrayList<>();
        for (Locale locale : LOCALES) {
            Set<String> k = keys(locale);
            for (String missing : reference) {
                if (!k.contains(missing)) {
                    problems.add(locale + " 缺键: " + missing);
                }
            }
            for (String extra : k) {
                if (!reference.contains(extra)) {
                    problems.add(locale + " 多余键: " + extra);
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> "语言包键集不一致（共 " + problems.size() + " 处）:\n"
                + String.join("\n", problems.subList(0, Math.min(20, problems.size()))));
    }

    @Test
    void apostrophesMatchPlaceholderUsage() {
        List<String> problems = new ArrayList<>();
        for (Locale locale : LOCALES) {
            ResourceBundle bundle = bundle(locale);
            for (String key : bundle.keySet()) {
                String value = bundle.getString(key);
                boolean formatted = PLACEHOLDER.matcher(value).find();
                if (formatted && LONE_APOSTROPHE.matcher(value).find()) {
                    problems.add(locale + " / " + key + " 带占位符却有未加倍的撇号，填充后会丢字: " + value);
                }
                if (!formatted && value.contains("''")) {
                    problems.add(locale + " / " + key + " 无占位符却写了双撇号，将原样显示: " + value);
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> "撇号写法与占位符不匹配（共 " + problems.size() + " 处）:\n"
                + String.join("\n", problems.subList(0, Math.min(20, problems.size()))));
    }

    @Test
    void placeholderIndexesAreContiguousFromZero() {
        List<String> problems = new ArrayList<>();
        for (Locale locale : LOCALES) {
            ResourceBundle bundle = bundle(locale);
            for (String key : bundle.keySet()) {
                String value = bundle.getString(key);
                Matcher m = PLACEHOLDER.matcher(value);
                int max = -1;
                while (m.find()) {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                }
                for (int i = 0; i <= max; i++) {
                    if (!value.contains("{" + i + "}")) {
                        problems.add(locale + " / " + key + " 缺占位符 {" + i + "}（最大为 {" + max + "}）: " + value);
                        break;
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> "占位符编号不连续（共 " + problems.size() + " 处）:\n"
                + String.join("\n", problems.subList(0, Math.min(20, problems.size()))));
    }

    @Test
    void everyStaticKeyUsedInCodeExists() throws Exception {
        Path src = Path.of("src/main/java");
        Assumptions.assumeTrue(Files.isDirectory(src), "非模块工作目录，跳过取键扫描: " + src.toAbsolutePath());
        Set<String> reference = keys(Locale.ROOT);
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = STATIC_KEY_USAGE.matcher(Files.readString(f));
                while (m.find()) {
                    String key = m.group(1);
                    if (!reference.contains(key)) {
                        problems.add(f + " 引用了不存在的键: " + key);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> "源码取用了缺失键（共 " + problems.size() + " 处）:\n"
                + String.join("\n", problems.subList(0, Math.min(20, problems.size()))));
    }

    private static Set<String> keys(Locale locale) {
        return bundle(locale).keySet();
    }

    private static ResourceBundle bundle(Locale locale) {
        try {
            return ResourceBundle.getBundle(BUNDLE_BASE, locale);
        } catch (MissingResourceException e) {
            throw new AssertionError("语言包不可加载: " + BUNDLE_BASE + " " + locale, e);
        }
    }
}
