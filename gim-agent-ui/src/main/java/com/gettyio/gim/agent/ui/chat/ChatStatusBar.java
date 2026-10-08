package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.StreamEvent;
import com.gettyio.gim.agent.core.chat.TokenUsage;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.enums.ConversationRole;
import com.gettyio.gim.agent.domain.enums.StreamEventType;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.throttle.ModelRateGovernor;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;

import java.util.List;
import java.util.function.Supplier;

/**
 * 输入区底栏的两条状态条协作类：本轮 token 用量条与上下文占用条（预估当前提示词体量 / 上限）。
 * <p>抽自 {@code MainController}：控制器交出底栏三个节点与只读服务，并在构造时注入两个取值函数
 * （当前会话、工具条手选模型），本类据此刷新条体并把真实用量回报给速率治理（TPM 滑窗）。
 * 用量只在流式收尾/切换会话时刷新；上下文条在输入变化、发送、重放会话三处刷新。</p>
 */
public final class ChatStatusBar {

    /** 上下文上限默认值（token）：未配置 {@code context.max.tokens} 时用。 */
    private static final int DEFAULT_CONTEXT_TOKENS = 128_000;
    /** 应用级设置键：上下文窗口上限（token），供占用条分母。 */
    private static final String KEY_CONTEXT_MAX_TOKENS = "context.max.tokens";
    /** 占用率达到该阈值即转警示色并提示将触发历史压缩。 */
    private static final double CONTEXT_WARN_RATIO = 0.85;
    /** 字符→token 粗估系数（中英混排经验值），仅在拿不到真实回报时兜底。 */
    private static final double CHARS_PER_TOKEN = 2.5;

    private final Label usageBar;
    private final Label contextBar;
    private final ProgressBar contextGauge;
    private final TextArea inputField;
    private final ChatService chatService;
    private final ModelRateGovernor rateGovernor;
    private final GimDatabase database;
    /** 工具条手选模型（用量条成本估算用）；控制器接线 {@code toolbar::selectedModelCode}。 */
    private final Supplier<String> selectedModelCode;
    /** 当前会话 id；控制器接线 {@code this::currentSessionId}。 */
    private final Supplier<String> currentSessionId;

    public ChatStatusBar(Label usageBar, Label contextBar, ProgressBar contextGauge,
                         TextArea inputField, ChatService chatService,
                         ModelRateGovernor rateGovernor, GimDatabase database,
                         Supplier<String> selectedModelCode, Supplier<String> currentSessionId) {
        this.usageBar = usageBar;
        this.contextBar = contextBar;
        this.contextGauge = contextGauge;
        this.inputField = inputField;
        this.chatService = chatService;
        this.rateGovernor = rateGovernor;
        this.database = database;
        this.selectedModelCode = selectedModelCode;
        this.currentSessionId = currentSessionId;
    }

    // ===================== 本轮用量条（输入区底栏）=====================

    /** 扫描本帧窗口内的 USAGE 事件（一轮只在收尾时下发一次），刷新用量条。 */
    public void updateUsageFromBatch(List<StreamEvent> batch) {
        TokenUsage usage = null;
        for (StreamEvent e : batch) {
            if (e.getType() == StreamEventType.USAGE && e.getUsage() != null) {
                usage = e.getUsage();
            }
        }
        if (usage != null) {
            showUsage(usage, selectedModelCode.get());
        }
    }

    /** 切换/重放会话时，取该会话最后一条带用量的助手消息刷新用量条；无则隐藏。 */
    public void updateUsageFromSession(String sessionId) {
        if (sessionId == null) {
            hideUsage();
            return;
        }
        List<ChatConversation> history = chatService.history(sessionId);
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatConversation c = history.get(i);
            if (ConversationRole.ASSISTANT.getCode().equalsIgnoreCase(c.getRole())
                    && (c.getTokensInput() != null || c.getTokensOutput() != null)) {
                TokenUsage u = new TokenUsage(nz(c.getTokensInput()), nz(c.getTokensOutput()),
                        nz(c.getTokensCached()), nz(c.getLlmCalls()),
                        c.getLlmElapsedMillis() == null ? 0L : c.getLlmElapsedMillis());
                if (u.isEmpty()) {
                    hideUsage();
                } else {
                    showUsage(u, c.getModelCode());
                }
                return;
            }
        }
        hideUsage();
    }

    private void showUsage(TokenUsage usage, String modelCode) {
        if (usage == null || usage.isEmpty()) {
            hideUsage();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append('\u2191').append(usage.inputTokens())
                .append(" \u2193").append(usage.outputTokens());
        if (usage.cachedTokens() > 0) {
            sb.append(" \u21e2").append(usage.cachedTokens());
        }
        if (usage.calls() > 0) {
            sb.append(" \u00b7 ").append(I18n.t("chat.usage.calls", usage.calls()));
        }
        double[] cost = chatService.modelCostPer1k(modelCode);
        if (cost != null) {
            double money = usage.inputTokens() / 1000.0 * cost[0] + usage.outputTokens() / 1000.0 * cost[1];
            sb.append(" \u00b7 ").append(I18n.t("chat.usage.cost", String.format("%.2f", money)));
        }
        usageBar.setText(sb.toString());
        usageBar.setVisible(true);
        usageBar.setManaged(true);
        // 回报本轮真实 token 用量给速率治理（TPM 滑窗计数）
        rateGovernor.recordTokens(modelCode, (long) usage.inputTokens() + usage.outputTokens());
    }

    private void hideUsage() {
        if (usageBar != null) {
            usageBar.setVisible(false);
            usageBar.setManaged(false);
        }
    }

    // ===================== 上下文占用条（输入区底栏）=====================

    /**
     * 刷新上下文占用条：以会话上一轮助手回报的 {@code tokens_input}（即真实提示词体量）为基准，
     * 叠加待发送输入框的估算 token；拿不到回报时退而按全部历史字符粗估。接近上限时转红并提示将压缩。
     */
    public void updateContext() {
        if (contextBar == null || contextGauge == null) {
            return;
        }
        String sessionId = currentSessionId.get();
        if (sessionId == null) {
            hideContextBar();
            return;
        }
        List<ChatConversation> history = chatService.history(sessionId);
        Integer lastInput = null;
        long chars = 0;
        for (ChatConversation c : history) {
            if (c.getContent() != null) {
                chars += c.getContent().length();
            }
            if (ConversationRole.ASSISTANT.getCode().equalsIgnoreCase(c.getRole())
                    && c.getTokensInput() != null && c.getTokensInput() > 0) {
                lastInput = c.getTokensInput();
            }
        }
        int pending = estimateTokens(inputField.getText());
        int estimated = (lastInput != null && lastInput > 0)
                ? lastInput + pending
                : (int) Math.round(chars / CHARS_PER_TOKEN) + pending;
        int limit = contextTokenLimit();
        double ratio = limit > 0 ? (double) estimated / limit : 0;
        boolean near = ratio >= CONTEXT_WARN_RATIO;

        contextGauge.setProgress(Math.max(0.0, Math.min(1.0, ratio)));
        contextBar.setText(I18n.t("context.usage", formatK(estimated), formatK(limit)));
        contextBar.setTooltip(new Tooltip(I18n.t(near ? "context.warn" : "context.info",
                formatK(estimated), formatK(limit))));
        if (contextBar.getStyleClass().contains("context-bar-warn")) {
            contextBar.getStyleClass().remove("context-bar-warn");
        }
        if (near) {
            contextBar.getStyleClass().add("context-bar-warn");
        }
        contextBar.setVisible(true);
        contextBar.setManaged(true);
        contextGauge.setVisible(true);
        contextGauge.setManaged(true);
    }

    private void hideContextBar() {
        if (contextBar != null) {
            contextBar.setVisible(false);
            contextBar.setManaged(false);
        }
        if (contextGauge != null) {
            contextGauge.setVisible(false);
            contextGauge.setManaged(false);
        }
    }

    /** 读上下文 token 上限：应用级设置优先，非法或未配置回退默认。 */
    private int contextTokenLimit() {
        try {
            return database.settingDao().getValue(KEY_CONTEXT_MAX_TOKENS)
                    .map(s -> {
                        int v = Integer.parseInt(s.trim());
                        return v > 0 ? v : DEFAULT_CONTEXT_TOKENS;
                    })
                    .orElse(DEFAULT_CONTEXT_TOKENS);
        } catch (RuntimeException e) {
            return DEFAULT_CONTEXT_TOKENS;
        }
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) Math.round(text.length() / CHARS_PER_TOKEN);
    }

    /** token 数简写：≥ 1000 显示为 x.yk，否则原样。 */
    private static String formatK(int tokens) {
        if (tokens < 1000) {
            return String.valueOf(tokens);
        }
        return String.format(java.util.Locale.ROOT, "%.1fk", tokens / 1000.0);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
