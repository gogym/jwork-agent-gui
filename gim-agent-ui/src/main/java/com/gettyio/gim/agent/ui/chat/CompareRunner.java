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

import com.gettyio.gim.agent.core.chat.AgentGateway;
import com.gettyio.gim.agent.core.chat.ChatRequest;
import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.StreamEvent;
import com.gettyio.gim.agent.core.chat.TokenUsage;
import com.gettyio.gim.agent.domain.enums.StreamEventType;
import com.gettyio.gim.agent.domain.security.LocalAuthService;
import com.gettyio.gim.agent.ui.chat.payload.CompareColumn;
import com.gettyio.gim.agent.ui.chat.payload.CompareFoot;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.config.ProviderAdminService;
import com.gettyio.gim.agent.ui.jcef.JcefComposer;
import com.gettyio.gim.agent.ui.throttle.ModelRateGovernor;
import com.gettyio.gim.agent.ui.window.dialog.GimDialog;
import io.agentscope.core.permission.PermissionMode;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 多模型对比运行器：组装正文 → 用户气泡上屏 → 挂对比卡片 → 每列独立订阅网关流 → 全部列终态后退出流式态。
 * <p>从 {@code MainController} 拆出的协作类：对比固定只读模式（EXPLORE：读类工具正常、副作用工具被拒
 * 且不弹确认），不落库——每列走临时会话 id，与真实会话状态互不影响；文档附件暂不支持（正文抽取链路在
 * {@code ChatService} 内）。</p>
 *
 * @author gogym
 */
public final class CompareRunner {

    private static final Logger log = LoggerFactory.getLogger(CompareRunner.class);

    /** 帧合并窗口：至少每 33ms 刷新一次（与 MainController.FRAME_WINDOW 保持一致）。 */
    private static final java.time.Duration FRAME_WINDOW = java.time.Duration.ofMillis(33);

    /** 宿主回调：流式态切换与智能体归属由控制器提供（对比借用同一套发送按钮状态机）。 */
    public interface Host {

        /** 进入流式态：发送按钮原地变「停止」（对比传 {@code null} sessionId）。 */
        void enterStreamingUi(String sessionId);

        /** 退出流式态：恢复发送图标并释放在途句柄。 */
        void exitStreamingUi();

        /** 新建会话的归属智能体编码（对比归属当前选中者）。 */
        String currentAgentCode();
    }

    private final Host host;
    private final ToolbarPickers toolbar;
    private final ProviderAdminService providerAdminService;
    private final ModelRateGovernor rateGovernor;
    private final AgentGateway agentGateway;
    private final ChatService chatService;
    private final ChatWebView chatView;
    private final ChatMessagePayload payload;
    private final AttachmentBar attachmentBar;
    private final JcefComposer inputField;
    private final ChatStatusBar statusBar;
    private final LocalAuthService localAuthService;
    private final BooleanProperty chatEmpty;

    /** 在途多模型对比：逐列订阅句柄（停止时全部取消）；仅在 FX 线程读写 */
    private final List<Disposable> compareStreams = new ArrayList<>();
    /** 在途对比的临时会话 id（每列一个，停止时逐个通知网关中断） */
    private final List<String> compareSessions = new ArrayList<>();
    /** 在途对比归属的智能体编码（停止时用）；无对比在途时为 null */
    private String compareAgentCode;
    /** 是否有对比在途（isBusy 判定与停止分流） */
    private boolean running;
    /** 本轮对比的总列数与已完成列数：全部列终结（完成/出错/停止）后退出流式态 */
    private int compareDoneTotal;
    private int compareCompleted;

    public CompareRunner(Host host, ToolbarPickers toolbar, ProviderAdminService providerAdminService,
                         ModelRateGovernor rateGovernor, AgentGateway agentGateway, ChatService chatService,
                         ChatWebView chatView, ChatMessagePayload payload, AttachmentBar attachmentBar,
                         JcefComposer inputField, ChatStatusBar statusBar, LocalAuthService localAuthService,
                         BooleanProperty chatEmpty) {
        this.host = host;
        this.toolbar = toolbar;
        this.providerAdminService = providerAdminService;
        this.rateGovernor = rateGovernor;
        this.agentGateway = agentGateway;
        this.chatService = chatService;
        this.chatView = chatView;
        this.payload = payload;
        this.attachmentBar = attachmentBar;
        this.inputField = inputField;
        this.statusBar = statusBar;
        this.localAuthService = localAuthService;
        this.chatEmpty = chatEmpty;
    }

    /** 是否有对比在途（发送互斥与停止分流的统一判定）。 */
    public boolean isRunning() {
        return running;
    }

    /**
     * 对比发送：组装正文 → 上屏用户气泡 → 挂对比卡片 → 每列独立订阅网关流。
     */
    public void send(String text, boolean hasText, boolean hasImages, boolean hasRefs, boolean hasDocs) {
        List<ToolbarPickers.CompareTarget> targets = toolbar.compareTargets();
        if (hasDocs) {
            GimDialog.message(ownerWindow(), I18n.t("compare.menu.title"), null, I18n.t("compare.doc.unsupported"));
            return;
        }
        if (hasImages) {
            List<String> noVision = targets.stream()
                    .filter(t -> !providerAdminService.modelSupportsVision(t.modelCode()))
                    .map(ToolbarPickers.CompareTarget::label)
                    .toList();
            if (!noVision.isEmpty()) {
                GimDialog.message(ownerWindow(), I18n.t("vision.blocked.title"), I18n.t("vision.blocked.title"),
                        I18n.t("compare.vision.blocked", String.join("、", noVision)));
                return;
            }
        }
        for (ToolbarPickers.CompareTarget t : targets) {
            String rateBlocked = rateGovernor.blockedReason(t.modelCode());
            if (rateBlocked != null) {
                GimDialog.message(ownerWindow(), I18n.t("rate.limit.title"),
                        I18n.t("rate.limit.title"), rateBlocked);
                return;
            }
        }
        String body = hasText ? text : "";
        if (hasRefs) {
            String refBlock = attachmentBar.renderRefBlock();
            if (!refBlock.isEmpty()) {
                body = body.isEmpty() ? refBlock : body + "\n\n" + refBlock;
            }
        }
        List<com.gettyio.gim.agent.core.chat.ImageAttachment> images = attachmentBar.snapshotImages();
        inputField.clear();
        List<com.gettyio.gim.agent.core.chat.StoredAttachment> liveFiles = attachmentBar.previewDocRefs();
        attachmentBar.clearPending();
        // 与普通发送一致：用户气泡先上屏（不落库，主键 0）
        chatEmpty.set(false);
        chatView.appendMessage(payload.userLive(0, body, images, liveFiles));
        runCompare(body, images, targets);
        statusBar.updateContext();
    }

    /** 对比流的生命周期编排：逐列订阅 → 全部列终态后退出流式态；发出即清勾选（对比为一次性动作）。 */
    private void runCompare(String body, List<com.gettyio.gim.agent.core.chat.ImageAttachment> images,
                            List<ToolbarPickers.CompareTarget> targets) {
        // 对比发送同样视为活跃使用（与 runStream 同一续期口径）
        localAuthService.touch();
        String agentCode = host.currentAgentCode();
        // 逐列累计明文/用量/起始时间（收尾定格到底部行），与 WebView 内的列同序
        final List<StringBuilder> raws = new ArrayList<>();
        final List<TokenUsage> usages = new ArrayList<>();
        final List<Long> starts = new ArrayList<>();
        List<CompareColumn> cols = new ArrayList<>();
        for (ToolbarPickers.CompareTarget t : targets) {
            raws.add(new StringBuilder());
            usages.add(null);
            starts.add(System.currentTimeMillis());
            cols.add(new CompareColumn(t.label(), I18n.t("compare.running")));
        }
        chatView.compareStart(cols);

        running = true;
        compareAgentCode = agentCode;
        compareSessions.clear();
        compareStreams.clear();
        compareDoneTotal = targets.size();
        compareCompleted = 0;
        host.enterStreamingUi(null);

        for (int i = 0; i < targets.size(); i++) {
            final int idx = i;
            ToolbarPickers.CompareTarget t = targets.get(i);
            // 临时会话 id：与真实会话隔离（不进历史，停止时按它逐个中断网关侧生成）
            String tempSession = "cmp-" + java.util.UUID.randomUUID();
            compareSessions.add(tempSession);
            ChatRequest req = ChatRequest.builder()
                    .agentCode(agentCode)
                    .sessionId(tempSession)
                    .text(body)
                    .providerCode(t.providerCode())
                    .modelCode(t.modelCode())
                    .images(images)
                    .permissionMode(PermissionMode.EXPLORE)
                    .build();
            rateGovernor.recordRequest(t.modelCode());
            Disposable stream = agentGateway.stream(req)
                    .bufferTimeout(256, FRAME_WINDOW)
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnCancel(() -> Platform.runLater(() -> {
                        chatView.compareEnd(idx, "stopped", compareFoot("stopped", usages.get(idx), starts.get(idx)));
                        compareColumnDone();
                    }))
                    .subscribe(
                            batch -> Platform.runLater(() -> {
                                for (StreamEvent e : batch) {
                                    if (e.getType() == StreamEventType.TEXT_DELTA && e.getText() != null) {
                                        raws.get(idx).append(e.getText());
                                        chatView.compareDelta(idx, e.getText());
                                    } else if (e.getType() == StreamEventType.TOOL_START && e.getToolName() != null
                                            && e.getToolInput() == null) {
                                        // 带真实入参的补发事件（第二次 TOOL_START）跳过：对比列窄，一行提示够了
                                        raws.get(idx).append('\n').append(I18n.t("chat.toolCall", e.getToolName()));
                                        chatView.compareNote(idx, "\n" + I18n.t("chat.toolCall", e.getToolName()));
                                    } else if (e.getType() == StreamEventType.NOTICE && e.getText() != null) {
                                        String notice = com.gettyio.gim.agent.ui.util.UiFormat.notice(e);
                                        raws.get(idx).append('\n').append(notice);
                                        chatView.compareNote(idx, "\n" + notice);
                                    } else if (e.getType() == StreamEventType.USAGE) {
                                        if (e.getUsage() != null && !e.getUsage().isEmpty()) {
                                            usages.set(idx, e.getUsage());
                                        }
                                    }
                                }
                            }),
                            err -> Platform.runLater(() -> {
                                log.warn("对比流失败: model={}", t.modelCode(), err);
                                String msg = err.getMessage() == null || err.getMessage().isBlank()
                                        ? I18n.t("common.error.unknown") : err.getMessage();
                                String note = "\n" + I18n.t("chat.failed", msg);
                                raws.get(idx).append(note);
                                chatView.compareDelta(idx, note);
                                chatView.compareEnd(idx, "failed", compareFoot("failed", usages.get(idx), starts.get(idx)));
                                compareColumnDone();
                            }),
                            () -> Platform.runLater(() -> {
                                chatView.compareEnd(idx, "done", compareFoot("done", usages.get(idx), starts.get(idx)));
                                compareColumnDone();
                            }));
            compareStreams.add(stream);
        }
        toolbar.clearCompareSelection();
        toolbar.renderCompareButton();
    }

    /** 对比列定格页脚：徽章文案 + 耗时（秒，一位小数）· 总 tokens（无用量回报时只显耗时）。 */
    private CompareFoot compareFoot(String state, TokenUsage usage, long startMillis) {
        String badge = I18n.t(switch (state) {
            case "done" -> "compare.done";
            case "failed" -> "compare.failed";
            default -> "compare.stopped";
        });
        double seconds = Math.max(0, System.currentTimeMillis() - startMillis) / 1000.0;
        String time = String.format(Locale.ROOT, "%.1f", seconds);
        String text = usage == null
                ? I18n.t("compare.foot.time", time)
                : I18n.t("compare.foot", time, String.valueOf(usage.inputTokens() + usage.outputTokens()));
        return new CompareFoot(badge, text);
    }

    /** 停止对比：先打断各列网关侧的模型/工具循环，再取消订阅（doOnCancel 逐列收尾为「已停止」）。 */
    public void stop() {
        String agent = compareAgentCode;
        for (String sid : List.copyOf(compareSessions)) {
            if (agent != null) {
                chatService.stop(agent, sid);
            }
        }
        for (Disposable stream : List.copyOf(compareStreams)) {
            stream.dispose();
        }
    }

    /** 一列终结（完成/出错/停止）：全部列终结后退出流式态并清在途记录。 */
    private void compareColumnDone() {
        compareCompleted++;
        if (compareCompleted < compareDoneTotal) {
            return;
        }
        running = false;
        compareAgentCode = null;
        compareStreams.clear();
        compareSessions.clear();
        host.exitStreamingUi();
    }

    /** 弹框归属窗口：取输入框所在场景的窗口（与控制器同一口径）。 */
    private javafx.stage.Window ownerWindow() {
        return inputField != null && inputField.getScene() != null ? inputField.getScene().getWindow() : null;
    }
}
