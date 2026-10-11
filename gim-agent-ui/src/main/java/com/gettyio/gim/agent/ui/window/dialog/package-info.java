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
 * 模态对话框集合：统一无边框圆角壳（透明 Stage + 投影 + 圆角裁剪 + 磨玻璃遮罩 + 自绘标题栏）。
 * <p>{@link GimDialog} 提供通用消息 / 确认 / 多选项 / 单行输入与自定义内容壳（{@code Shell}），
 * 是全部业务对话框的底座；其余对话框承载具体场景：{@link AboutDialog}（关于与版本信息）、
 * {@link AgentDialog}（智能体新建 / 编辑表单）、{@link ImageGenerateDialog}（生图提示词输入）、
 * {@link PlanReviewDialog}（计划文件预览与编辑）、{@link SessionSearchDialog}（会话搜索命令面板）。</p>
 */
package com.gettyio.gim.agent.ui.window.dialog;
