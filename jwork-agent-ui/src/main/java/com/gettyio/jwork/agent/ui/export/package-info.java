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
 * 会话导出：Markdown / HTML / PDF / DOCX / JSON 多格式落盘与长图快照。
 * <p>{@link ExportService} 编排各格式导出与全库 JSON 备份，{@link SessionHtmlRenderer}
 * 渲染话题卡片式自包含 HTML（本地图片内联为 {@code data:} URI），
 * {@link SessionImageExporter} 以长图快照复用同一份 HTML 内容；
 * {@link ExportFormats} 收敛各导出器共用的入参摘要与角色标签文案，避免展示漂移。</p>
 */
package com.gettyio.jwork.agent.ui.export;
