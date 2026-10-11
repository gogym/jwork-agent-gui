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

/**
 * 界面国际化（i18n）：简体中文 / 繁体中文 / 英文 三语支持。
 * <p>{@link I18n} 是全应用文案的唯一入口——界面代码一律经 {@code I18n.t(key[, args])}
 * 取文案、不硬编码展示文本；文案表为 classpath 资源 {@code i18n/messages*.properties}
 * （UTF-8，按 JDK 默认 UTF-8 读取）。当前语言经 {@code UserPrefs}
 * （{@code ~/.jwork/theme.properties} 的 {@code lang} 键）持久化；切换语言时重载资源包
 * 并在 FX 线程通知全部注册监听器，已打开界面据此就地重译或整窗重建。</p>
 */
package com.gettyio.gim.agent.ui.i18n;
