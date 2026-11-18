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
 * 聊天区内容渲染：Markdown 富文本、代码高亮与 Mermaid 图表。
 * <p>{@link MarkdownRenderer} 把助手消息的 Markdown 源文渲染为 JavaFX 节点序列
 * （代码块附复制 / 预览操作，支持表格、行内样式与 emoji 字体分簇处理）；
 * {@link CodeHighlighter} 提供代码语法着色；{@link MermaidView} 以 JCEF(Chromium OSR)
 * 加载打包资源中的 {@code mermaid.min.js} 离线渲染图表并自适应宿主高度，
 * 资源缺失时由调用方回退普通代码块渲染。</p>
 */
package com.gettyio.jwork.agent.ui.chat.render;
