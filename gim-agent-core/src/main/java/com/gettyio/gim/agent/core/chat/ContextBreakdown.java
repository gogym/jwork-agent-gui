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

package com.gettyio.gim.agent.core.chat;

/**
 * 上下文占用的分类估算（token）：供占用浮层画分段条与图例，口径与占用条同为粗估。
 * <p>各项来源：系统提示词 = 智能体提示词 + 工作区注入文档（AGENTS/KNOWLEDGE/MEMORY）；
 * 系统工具 = 内置工具集经验估值 + 按声明的 MCP 服务数叠加；技能 = 白名单内技能名称与描述体量；
 * 消息 = 会话历史字符粗估（不含待发输入，由 UI 侧实时叠加）。展示前按占用条总量等比缩放，
 * 保证图例百分比之和与条体百分比一致。</p>
 *
 * @param systemTokens  系统提示词与工作区注入文档的估算 token
 * @param toolTokens    工具 schema 的估算 token（内置经验值 + MCP 服务叠加）
 * @param skillTokens   技能索引（名称+描述）的估算 token
 * @param skillCount    该智能体声明的技能个数（图例「Skill（N个）」用）
 * @param messageTokens 会话历史的估算 token（不含待发输入）
 * @author gogym
 */
public record ContextBreakdown(int systemTokens, int toolTokens, int skillTokens,
                               int skillCount, int messageTokens) {
}