package com.gettyio.gim.agent.core.chat;

/**
 * 一次「压缩当前会话」的执行结果。
 *
 * @param compacted       是否实际执行了压缩；{@code false} 表示会话过短或上下文为空，无需压缩
 * @param estimatedTokens 压缩后上下文的 token 粗估（框架计数器口径，仅供 UI 即时展示）
 */
public record ContextCompactionResult(boolean compacted, int estimatedTokens) {

    /** 未压缩（会话过短 / 上下文为空 / 不支持）的通用结果。 */
    public static ContextCompactionResult skipped() {
        return new ContextCompactionResult(false, 0);
    }
}
