package com.gettyio.gim.agent.core.chat;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Agent 对话网关：屏蔽底层 AgentScope 细节，向 UI 提供统一调用面。
 */
public interface AgentGateway {

    /**
     * 单轮问答，返回完整应答文本（M1 先提供非流式，M2 补齐流式映射）
     */
    Mono<String> ask(String sessionId, String userText);

    /**
     * 流式对话，产出归一化事件（M2 里程碑实现）
     */
    Flux<StreamEvent> stream(String sessionId, String userText);
}
