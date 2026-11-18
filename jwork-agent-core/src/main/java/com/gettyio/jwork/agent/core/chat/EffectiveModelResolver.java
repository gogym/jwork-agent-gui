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

package com.gettyio.jwork.agent.core.chat;

/**
 * 生效模型解析：把「消息级覆盖 → 智能体自带 → 应用默认」这条实际选模链解析成模型编码，
 * 供对话落盘写入 {@code conversations.model_code}，使「模型维度」用量统计能归到真实使用的模型上。
 * <p>定义在 core，实现由装配方（UI 层持有 {@code DefaultModelResolver}）提供，避免 core 反向依赖 UI。
 * 解析失败（如尚未配置默认模型）应返回 {@code null} 而非抛出——用量统计不应因选模链断裂而丢消息。</p>
 *
 * @author gogym
 */
@FunctionalInterface
public interface EffectiveModelResolver {

    /**
     * @return 本轮实际使用的模型编码；无法确定时返回 {@code null}
     */
    String resolveModelCode(String agentCode, String providerCode, String modelCode);
}
