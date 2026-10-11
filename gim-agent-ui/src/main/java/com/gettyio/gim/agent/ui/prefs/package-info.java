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
 * 用户偏好存储：{@code ~/.jwork/theme.properties} 的键值读写。
 * <p>{@link UserPrefs} 供 {@code UiTheme}（模式 / 字号）与 {@code I18n}（语言）共享，
 * 避免多文件互相覆盖；进程内缓存一次加载，写操作立即落盘。读写失败静默回退默认值，
 * 不阻断启动与当前使用。</p>
 */
package com.gettyio.gim.agent.ui.prefs;
