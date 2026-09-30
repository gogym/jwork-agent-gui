package com.gettyio.gim.agent.core.chat;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Agent 对话网关：屏蔽底层 AgentScope 细节，向 UI 提供统一调用面。
 * <p>调用方传入 agentCode 指定由哪个智能体应答，具体路由见 {@link MultiAgentGateway}。</p>
 */
public interface AgentGateway {

    /**
     * 单轮问答，返回完整应答文本（非流式，供定时任务等无 UI 场景使用）
     */
    Mono<String> ask(String agentCode, String sessionId, String userText);

    /**
     * 流式对话，产出归一化事件
     */
    Flux<StreamEvent> stream(String agentCode, String sessionId, String userText);
}
