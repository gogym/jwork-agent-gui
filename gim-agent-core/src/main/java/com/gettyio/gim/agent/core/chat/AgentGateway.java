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

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Agent 对话网关：屏蔽底层 AgentScope 细节，向 UI 提供统一调用面。
 * <p>调用方传入 agentCode 指定由哪个智能体应答，具体路由见 {@link MultiAgentGateway}。</p>
 *
 * @author gogym
 */
public interface AgentGateway {

    /**
     * 单轮问答，返回完整应答文本（非流式，供定时任务等无 UI 场景使用）
     */
    Mono<String> ask(String agentCode, String sessionId, String userText);

    /**
     * 流式对话，产出归一化事件。入参 {@link ChatRequest} 承载消息级模型覆盖、图片附件与权限模式。
     */
    Flux<StreamEvent> stream(ChatRequest request);

    /**
     * 纯文本流式对话的便捷入口：无附件、无模型覆盖、权限模式取默认。
     * <p>委托到 {@link #stream(ChatRequest)}，使定时任务等既有调用零改动。</p>
     */
    default Flux<StreamEvent> stream(String agentCode, String sessionId, String userText) {
        return stream(ChatRequest.builder()
                .agentCode(agentCode)
                .sessionId(sessionId)
                .text(userText)
                .build());
    }

    /**
     * 中断指定会话在途的生成（尽力而为：无对应运行时则静默）。
     * <p>调用方需另行取消订阅；本方法只负责打断 Agent 侧的模型/工具循环。</p>
     */
    default void stop(String agentCode, String sessionId) {
    }

    /**
     * 手动压缩指定会话的模型上下文（LLM 摘要式，与框架自动压缩同一条链路）。
     * <p>默认空实现：不支持手动压缩的网关返回 {@link Mono#empty()}，调用方按「未压缩」处理。</p>
     *
     * @param agentCode 会话归属的智能体编码
     * @param sessionId 目标会话
     * @return 压缩结果；空 Mono 表示无对应运行时或不支持压缩
     */
    default Mono<ContextCompactionResult> compact(String agentCode, String sessionId) {
        return Mono.empty();
    }
}
