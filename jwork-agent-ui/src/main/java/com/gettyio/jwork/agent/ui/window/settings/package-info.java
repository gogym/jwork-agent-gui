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
 * 设置窗口及其页面拆分：整窗编排、共享布局构件与按设置页拆分的子面板。
 * <p>{@link SettingsWindow} 为薄编排层：左侧分类菜单（含搜索）+ 右侧内容容器，
 * 仅负责整窗骨架与各设置页子面板的装配及整表刷新驱动，语言切换时整窗重建；
 * {@link SettingsLayout} 提供页面骨架、分组卡片、设置行、胶囊下拉等纯节点构造构件
 * （全部为纯函数、不持有窗口状态）。各 {@code *SettingsPanel} 分别承载具体设置页内容——
 * 账号 / 智能体 / 外观 / 通用（启动与关闭）/ 对话与用量 / 语音 / 图像生成 / 网络 /
 * 数据与备份 / 更新 / 记忆 / 模型 / 提示词模板 / 安全。</p>
 */
package com.gettyio.jwork.agent.ui.window.settings;
