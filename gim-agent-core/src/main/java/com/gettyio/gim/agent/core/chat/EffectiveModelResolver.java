package com.gettyio.gim.agent.core.chat;

/**
 * 生效模型解析：把「消息级覆盖 → 智能体自带 → 应用默认」这条实际选模链解析成模型编码，
 * 供对话落盘写入 {@code conversations.model_code}，使「模型维度」用量统计能归到真实使用的模型上。
 * <p>定义在 core，实现由装配方（UI 层持有 {@code DefaultModelResolver}）提供，避免 core 反向依赖 UI。
 * 解析失败（如尚未配置默认模型）应返回 {@code null} 而非抛出——用量统计不应因选模链断裂而丢消息。</p>
 */
@FunctionalInterface
public interface EffectiveModelResolver {

    /**
     * @return 本轮实际使用的模型编码；无法确定时返回 {@code null}
     */
    String resolveModelCode(String agentCode, String providerCode, String modelCode);
}
