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
 * 工作区视图：聊天页右侧栏的产物浏览与阅读、HTML/SVG 代码块预览。
 * <p>{@link WorkspacePanel} 把 {@code ~/.jwork/workspace} 下的产物 / 文件以「文件树 + 就地阅读」
 * 形式挂进聊天页右侧分栏（Markdown / HTML / 图片 / 文档 / 纯文本按扩展名分派阅读器，
 * 选中路径经归一化校验限定在工作区根内）；{@link ArtifactPreviewWindow} 提供 HTML / SVG
 * 代码块的内嵌预览（优先导入右侧栏面板，无面板钩子时回退独立弹窗）。</p>
 */
package com.gettyio.gim.agent.ui.chat.workspace;
