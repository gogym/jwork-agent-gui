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
}
