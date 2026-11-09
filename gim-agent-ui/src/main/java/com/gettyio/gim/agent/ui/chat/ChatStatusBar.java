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

package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.ContextCompactionResult;
import com.gettyio.gim.agent.core.chat.HarnessAgentGateway;
import com.gettyio.gim.agent.core.chat.StreamEvent;
import com.gettyio.gim.agent.core.chat.TokenUsage;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ChatConversation;
import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.enums.ConversationRole;
import com.gettyio.gim.agent.domain.enums.StreamEventType;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.jcef.JcefComposer;
import com.gettyio.gim.agent.ui.throttle.ModelRateGovernor;
import com.gettyio.gim.agent.ui.window.dialog.GimDialog;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import javafx.util.Duration;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 输入区底栏的两条状态条协作类：本轮 token 用量条与上下文占用条（预估当前提示词体量 / 上限）。
 * <p>抽自 {@code MainController}：控制器交出底栏节点与只读服务，并在构造时注入两个取值函数
 * （当前会话、工具条手选模型），本类据此刷新条体并把真实用量回报给速率治理（TPM 滑窗）。
 * 用量只在流式收尾/切换会话时刷新；上下文条在输入变化、发送、重放会话三处刷新。</p>
 * <p>上下文占用以百分比展示，悬停弹出 {@link ContextPopover} 明细浮层（已用量/上限），
 * 浮层内可一键压缩当前会话（压缩能力经 {@link ContextCompactor} 由控制器桥接到服务层）。</p>
 *
 * @author gogym
 */
public final class ChatStatusBar {

    /** 上下文上限默认值（token）：与运行体自动压缩阈值同源（{@link HarnessAgentGateway#DEFAULT_MAX_CONTEXT_TOKENS}）。 */
    private static final int DEFAULT_CONTEXT_TOKENS = HarnessAgentGateway.DEFAULT_MAX_CONTEXT_TOKENS;
    /** 应用级设置键：上下文窗口上限（token），供占用条分母；与运行体压缩阈值同一键。 */
    private static final String KEY_CONTEXT_MAX_TOKENS = HarnessAgentGateway.SETTING_CONTEXT_MAX_TOKENS;
    /** 占用率达到该阈值即转警示色。 */
    private static final double CONTEXT_WARN_RATIO = 0.85;
    /** 手动压缩的可用门槛：占用超过该比例才允许压缩，未达时按钮禁用。 */
    private static final double COMPACT_MIN_RATIO = 0.25;
    /** 字符→token 粗估系数（中英混排经验值），仅在拿不到真实回报时兜底。 */
    private static final double CHARS_PER_TOKEN = 2.5;
    /** 历史扫描节流：输入连打期间每这么久最多扫一次库（扫描本身不占 FX 线程）。 */
    private static final Duration CONTEXT_SCAN_DEBOUNCE = Duration.millis(250);

    /** 手动压缩的宿主接线：忙判定与执行入口（控制器桥接到 {@code ChatService.compact}）。 */
    public interface ContextCompactor {
        /** 是否有回复正在流式生成；生成中拒绝手动压缩。 */
        boolean busy();

        /** 压缩指定会话的上下文；空 Mono 视为跳过（如骨架实现未支持）。 */
        Mono<ContextCompactionResult> compact(String sessionId);
    }

    private final Label usageBar;
    private final Label contextBar;
    private final ProgressBar contextGauge;
    private final JcefComposer inputField;
    private final ChatService chatService;
    private final ModelRateGovernor rateGovernor;
    private final GimDatabase database;
    /** 工具条手选模型（用量条成本估算用）；控制器接线 {@code toolbar::selectedModelCode}。 */
    private final Supplier<String> selectedModelCode;
    /** 当前会话 id；控制器接线 {@code this::currentSessionId}。 */
    private final Supplier<String> currentSessionId;
    /** 压缩能力宿主；可为 null（禁用压缩按钮）。 */
    private final ContextCompactor compactor;
    /** 上下文占用区的悬停明细浮层。 */
    private final ContextPopover popover;

    /** 压缩进行中的会话 id；非空即压缩在途。 */
    private String compactingSessionId;
    /** 手动压缩后的估算基准 + 其归属会话：优先于上一轮真实回报，待新一轮 USAGE 覆盖后弃用。 */
    private String compactedSessionId;
    private Integer compactedBaseTokens;
    /** 浮层明细当前归属的会话：切换会话时清掉上一会话的压缩结果回显。 */
    private String popoverSessionId;
    /** 最近一次历史扫描的结果与归属会话：每敲一个字重读整段会话历史实在没必要。 */
    private HistoryScan contextScan;
    private String contextScanSessionId;
    /** 在途扫描归属的会话：同一会话不排两轮。 */
    private String contextScanInFlight;
    /** 扫描节流定时器（FX 线程上驱动，仅负责延后发起，不在其上读库）。 */
    private PauseTransition contextScanTimer;

    /** 一次历史扫描的产物（在 FX 线程外算好）：上一轮真实提示体量、历史字符数、上限设置。 */
    private record HistoryScan(Integer lastInputTokens, long chars, int tokenLimit) {
    }

    public ChatStatusBar(Label usageBar, Label contextBar, ProgressBar contextGauge, HBox contextStats,
                         JcefComposer inputField, ChatService chatService,
                         ModelRateGovernor rateGovernor, GimDatabase database,
                         Supplier<String> selectedModelCode, Supplier<String> currentSessionId,
                         ContextCompactor compactor) {
        this.usageBar = usageBar;
        this.contextBar = contextBar;
        this.contextGauge = contextGauge;
        this.inputField = inputField;
        this.chatService = chatService;
        this.rateGovernor = rateGovernor;
        this.database = database;
        this.selectedModelCode = selectedModelCode;
        this.currentSessionId = currentSessionId;
        this.compactor = compactor;
        this.popover = new ContextPopover();
        this.popover.attachTrigger(contextStats);
        // 弹出前重算占用：浮层首行与按钮态始终反映最新估算
        this.popover.setOnWillShow(this::updateContext);
        this.popover.setOnCompact(this::runCompaction);
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
            String sid = currentSessionId.get();
            if (sid != null && sid.equals(compactedSessionId)) {
                // 压缩后的新一轮真实回报已覆盖估算基准，弃用手动压缩的粗估（含持久化）
                compactedSessionId = null;
                compactedBaseTokens = null;
                persistCompactedBase(sid, null);
            }
            showUsage(usage, selectedModelCode.get());
        }
    }

    // 本轮步数与当前动作不再出现在底栏：过程统一在气泡内的工作日志里呈现
    // （见 chat.js 的 buildTurn）——两处同时报同一状态是「显示不协调」的直接来源。

    /** 切换/重放会话时，取该会话最后一条带用量的助手消息刷新用量条；无则隐藏。 */    public void updateUsageFromSession(String sessionId) {
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
                        c.getLlmElapsedMillis() == null ? 0L : c.getLlmElapsedMillis(),
                        nz(c.getLastInputTokens()));
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
     * 刷新上下文占用条：以会话最后一条助手行记录的 {@code last_input_tokens}（= 上一轮最后一次模型调用的
     * 真实提示词体量）为基准，叠加待发送输入框的估算 token；拿不到回报时退而按全部历史字符粗估。
     * 条体只显示百分比，已用量/上限与压缩入口在悬停浮层内呈现；接近上限时百分比转警示色。
     * <p>不用 {@code tokens_input}：它是整轮（含工具循环多次调用）的累加值，会把占用条放大数倍。</p>
     */
    public void updateContext() {
        if (contextBar == null || contextGauge == null) {
            return;
        }
        String sessionId = currentSessionId.get();
        if (sessionId == null) {
            popover.hide();
            hideContextBar();
            return;
        }
        if (!sessionId.equals(popoverSessionId)) {
            // 会话切换：清掉上一会话的压缩结果回显，并恢复本会话持久化的压缩基准（若有）
            popoverSessionId = sessionId;
            popover.setStatus(null, false);
            restoreCompactedBase(sessionId);
        }
        renderContext(sessionId, sessionId.equals(contextScanSessionId) ? contextScan : null);
        scheduleContextScan(sessionId);
    }

    /**
     * 按已扫好的历史估算画占用条与浮层（纯 FX 线程计算，不读库，除首次那一下的上限读取）。
     *
     * @param scan 该会话的历史扫描结果；{@code null} 表示还没扫过（会话首次进入），
     *             此时先按「只算待发输入」画一版，等扫描回来再改正，免得为估算冻住输入
     */
    private void renderContext(String sessionId, HistoryScan scan) {
        int pending = estimateTokens(inputField.getText());
        int estimated;
        int limit;
        if (compactedBaseTokens != null && sessionId.equals(compactedSessionId)) {
            // 手动压缩后的基准：压缩器回传的精确估算 + 待发输入估量
            estimated = compactedBaseTokens + pending;
            limit = scan != null ? scan.tokenLimit() : contextTokenLimit();
        } else if (scan == null) {
            limit = contextTokenLimit();
            estimated = pending;
        } else if (scan.lastInputTokens() != null && scan.lastInputTokens() > 0) {
            estimated = scan.lastInputTokens() + pending;
            limit = scan.tokenLimit();
        } else {
            estimated = (int) Math.round(scan.chars() / CHARS_PER_TOKEN) + pending;
            limit = scan.tokenLimit();
        }
        double ratio = limit > 0 ? (double) estimated / limit : 0;
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        boolean near = ratio >= CONTEXT_WARN_RATIO;
        boolean compactingNow = sessionId.equals(compactingSessionId);

        contextGauge.setProgress(ratio);
        contextBar.setText(compactingNow
                ? I18n.t("context.compacting")
                : I18n.t("context.percent", (int) Math.round(ratio * 100)));
        if (contextBar.getStyleClass().contains("context-bar-warn")) {
            contextBar.getStyleClass().remove("context-bar-warn");
        }
        if (near) {
            contextBar.getStyleClass().add("context-bar-warn");
        }
        popover.setInfo(I18n.t("context.usage", formatK(estimated), formatK(limit)));
        boolean compactAllowed = compactor != null && compactingSessionId == null && ratio > COMPACT_MIN_RATIO;
        if (compactingNow) {
            popover.setCompactLabel(I18n.t("context.compacting"));
        } else if (compactor != null && compactingSessionId == null && ratio <= COMPACT_MIN_RATIO) {
            // 占用未达门槛：按钮禁用，并以按钮文字说明原因（disabled 控件收不到鼠标事件，用不了提示气泡）
            popover.setCompactLabel(I18n.t("context.compact.limited",
                    (int) Math.round(COMPACT_MIN_RATIO * 100) + "%"));
        } else {
            popover.setCompactLabel(I18n.t("context.compact"));
        }
        popover.setCompactEnabled(compactAllowed);
        contextBar.setVisible(true);
        contextBar.setManaged(true);
        contextGauge.setVisible(true);
        contextGauge.setManaged(true);
    }

    /**
     * 排一次历史扫描：输入连打时合帧，同一会话不排两轮。
     * <p>扫描要把 {@code conversations} 整段读出来（无 LIMIT），留在 FX 线程上就是每敲一个字
     * 卡一次窗口；结果回来后仅当用户仍停在那个会话才重绘。</p>
     */
    private void scheduleContextScan(String sessionId) {
        if (sessionId.equals(contextScanInFlight)) {
            return;
        }
        if (contextScanTimer == null) {
            contextScanTimer = new PauseTransition(CONTEXT_SCAN_DEBOUNCE);
            contextScanTimer.setOnFinished(e -> startContextScan(currentSessionId.get()));
        }
        contextScanTimer.playFromStart();
    }

    private void startContextScan(String sessionId) {
        if (sessionId == null || sessionId.equals(contextScanInFlight)) {
            return;
        }
        contextScanInFlight = sessionId;
        Mono.fromCallable(() -> scanHistory(sessionId))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(scan -> Platform.runLater(() -> {
                    contextScanInFlight = null;
                    contextScan = scan;
                    contextScanSessionId = sessionId;
                    if (sessionId.equals(currentSessionId.get())) {
                        renderContext(sessionId, scan);
                    }
                }), err -> Platform.runLater(() -> {
                    // 读库失败就沿用上一次估算：占用条不值得为此打断输入
                    contextScanInFlight = null;
                }));
    }

    /** 会话历史的估算原料：最后一条助手行回报的真实提示体量、全部历史字符数与上限设置。 */
    private HistoryScan scanHistory(String sessionId) {
        Integer lastInput = null;
        long chars = 0;
        for (ChatConversation c : chatService.history(sessionId)) {
            if (c.getContent() != null) {
                chars += c.getContent().length();
            }
            if (ConversationRole.ASSISTANT.getCode().equalsIgnoreCase(c.getRole())
                    && c.getLastInputTokens() != null && c.getLastInputTokens() > 0) {
                lastInput = c.getLastInputTokens();
            }
        }
        return new HistoryScan(lastInput, chars, contextTokenLimit());
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

    // ===================== 手动压缩当前会话 =====================

    /** 是否正有手动压缩在途（压缩期间拒绝发送/重生成等会改写上下文的操作）。 */
    public boolean isCompacting() {
        return compactingSessionId != null;
    }

    /** 浮层「压缩当前会话」：忙检查 → 置忙 → 后台执行压缩，结果/失败回写浮层并刷新占用条。 */
    private void runCompaction() {
        String sessionId = currentSessionId.get();
        if (compactor == null || sessionId == null || compactingSessionId != null) {
            return;
        }
        if (compactor.busy()) {
            GimDialog.message(windowOf(contextBar), I18n.t("context.compact.title"), null,
                    I18n.t("context.compact.busy"));
            return;
        }
        compactingSessionId = sessionId;
        popover.setStatus(null, false);
        updateContext();
        // defer + boundedElastic：运行体装配与 LLM 摘要都不占 FX 线程
        Mono.defer(() -> compactor.compact(sessionId))
                .defaultIfEmpty(ContextCompactionResult.skipped())
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(result -> Platform.runLater(() -> finishCompaction(sessionId, result)),
                        err -> Platform.runLater(() -> failCompaction(sessionId, err)));
    }

    private void finishCompaction(String sessionId, ContextCompactionResult result) {
        if (sessionId.equals(compactingSessionId)) {
            compactingSessionId = null;
        }
        if (result != null && result.compacted()) {
            int base = Math.max(0, result.estimatedTokens());
            // 基准随会话行持久化：重启或重进会话后仍能显示压缩后的占用
            persistCompactedBase(sessionId, base);
            if (sessionId.equals(currentSessionId.get())) {
                compactedSessionId = sessionId;
                compactedBaseTokens = base;
            }
            popover.setStatus(I18n.t("context.compact.done", formatK(base)), false);
        } else {
            popover.setStatus(I18n.t("context.compact.skip"), false);
        }
        updateContext();
    }

    private void failCompaction(String sessionId, Throwable error) {
        if (sessionId.equals(compactingSessionId)) {
            compactingSessionId = null;
        }
        String reason = error == null || error.getMessage() == null ? "" : error.getMessage();
        String text = I18n.t("context.compact.failed", reason);
        popover.setStatus(text, true);
        updateContext();
        // 浮层可能已收起，失败另以弹窗兜底告知
        Window owner = windowOf(contextBar);
        if (owner != null) {
            GimDialog.message(owner, I18n.t("context.compact.title"), null, text);
        }
    }

    /** 持久化压缩基准：随会话行长期保留，重启或重进会话后恢复显示；{@code tokens} 传 null 即清除。 */
    private void persistCompactedBase(String sessionId, Integer tokens) {
        try {
            database.sessionDao().updateCompactedTokens(sessionId, tokens);
        } catch (RuntimeException e) {
            // 落库失败不影响本进程内的显示（内存态基准仍有效）
        }
    }

    /** 恢复持久化的压缩基准（覆盖应用重启后内存态丢失的场景）；内存槽已是该会话则跳过。 */
    private void restoreCompactedBase(String sessionId) {
        if (sessionId.equals(compactedSessionId)) {
            return;
        }
        Integer stored;
        try {
            stored = database.sessionDao().findBySessionId(sessionId)
                    .map(ChatSession::getCompactedTokens)
                    .orElse(null);
        } catch (RuntimeException e) {
            return; // 读失败退回常规估算
        }
        if (stored != null && stored > 0) {
            compactedSessionId = sessionId;
            compactedBaseTokens = stored;
        }
    }

    private static Window windowOf(Node node) {
        return node == null || node.getScene() == null ? null : node.getScene().getWindow();
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

    /** token 数简写：整千 → {@code 128k}；非整千 → {@code 115.4k}；百万级 → {@code 1M} / {@code 1.2M}。 */
    private static String formatK(int tokens) {
        if (tokens < 1000) {
            return String.valueOf(tokens);
        }
        if (tokens >= 1_000_000) {
            double m = tokens / 1_000_000.0;
            if (m >= 10 || m == Math.floor(m)) {
                return String.format(Locale.ROOT, "%.0fM", m);
            }
            return String.format(Locale.ROOT, "%.1fM", m);
        }
        if (tokens % 1000 == 0) {
            return (tokens / 1000) + "k";
        }
        return String.format(Locale.ROOT, "%.1fk", tokens / 1000.0);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
