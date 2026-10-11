package com.gettyio.gim.agent.ui.chat;

/**
 * 消息气泡操作回调：由 {@code MainController} 实现，气泡上的悬停按钮与右键菜单据此触发。
 * <p>各能力对适用消息生效：{@link #onCopy} 所有消息；{@link #onSpeak} 已完成的助手消息；
 * {@link #onRegenerate} 仅最后一条助手消息；
 * {@link #onEditResend} 仅最后一条用户消息；{@link #onDelete}/{@link #onFork}
 * 任意已落盘（有主键）消息。</p>
 */
public interface MessageActionListener {

    /** 复制原文（助手为 Markdown 源文，用户为纯文本）。 */
    void onCopy(String rawText);

    /**
     * 朗读文本：实现方接管合成与播放；结束（播完/出错/被打断）时回调 {@code onEnded}（FX 线程）。
     */
    void onSpeak(String rawText, Runnable onEnded);

    /** 重新生成：删除最后一条助手消息，就同一用户请求再跑一轮。 */
    void onRegenerate();

    /** 编辑重发：截断该用户消息及其后全部，以新文本重发。 */
    void onEditResend(long conversationId, String currentText);

    /** 删除该条消息。 */
    void onDelete(long conversationId);

    /** 从该条消息分支：复制当前会话至此条（含）为新独立会话并打开。 */
    void onFork(long conversationId);
}
