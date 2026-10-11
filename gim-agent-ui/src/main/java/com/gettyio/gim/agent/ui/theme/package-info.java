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
 * 界面主题中心：浅色 / 深色模式与字体大小三档（小 / 中 / 大）。
 * <p>{@link UiTheme} 以「基础样式表 + 深色覆盖表 + 字号覆盖表」组合出场景样式；
 * 主题与字号偏好经 {@code UserPrefs} 持久化，切换即时作用于全部已打开窗口，
 * 并通知 WebView 渲染层同步 {@code data-theme} / {@code data-font} 属性。
 * 本包同时负责聊天区字体（Noto Sans SC）的运行时注册与 Segoe 图标字体 Label 工厂。</p>
 */
package com.gettyio.gim.agent.ui.theme;
