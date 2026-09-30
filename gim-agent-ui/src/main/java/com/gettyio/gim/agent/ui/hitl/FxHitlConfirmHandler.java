package com.gettyio.gim.agent.ui.hitl;

import com.gettyio.gim.agent.core.chat.ToolConfirmRequest;
import com.gettyio.gim.agent.domain.enums.PermissionDecision;
import com.gettyio.gim.agent.tools.hitl.HitlConfirmHandler;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.window.AppShell;
import com.gettyio.gim.agent.ui.window.GimDialog;
import javafx.application.Platform;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * JavaFX 弹窗式人工确认处理器（@Primary 覆盖无 UI 的默认拒绝实现）。
 * <p>在 FX 线程弹出确认对话框，用户选择"允许/总是允许/拒绝"回填异步决策。
 * 按钮与文案按当前语言即时构建。对话框以主窗口为 owner，居中弹出并把背景磨玻璃化。</p>
 */
@Component
public class FxHitlConfirmHandler implements HitlConfirmHandler {

    @Autowired
    private AppShell appShell;

    @Override
    public CompletableFuture<PermissionDecision> requestConfirm(ToolConfirmRequest request) {
        CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            int choice = GimDialog.choose(appShell.getStage(),
                    I18n.t("hitl.title"),
                    I18n.t("hitl.header", request.getToolName()),
                    buildContent(request),
                    I18n.t("hitl.always"), I18n.t("hitl.allow"), I18n.t("hitl.deny"));
            PermissionDecision decision = switch (choice) {
                case 0 -> PermissionDecision.ALWAYS_ALLOW;
                case 1 -> PermissionDecision.ALLOW;
                default -> PermissionDecision.DENY;
            };
            future.complete(decision);
        });
        return future;
    }

    private static String buildContent(ToolConfirmRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request.getRiskSummary() != null && !request.getRiskSummary().isBlank()) {
            sb.append(I18n.t("hitl.risk", request.getRiskSummary())).append("\n\n");
        }
        if (request.getToolInput() != null && !request.getToolInput().isBlank()) {
            sb.append(I18n.t("hitl.input", request.getToolInput()));
        }
        return sb.isEmpty() ? I18n.t("hitl.default") : sb.toString();
    }
}
