package com.gettyio.gim.agent.ui.hitl;

import com.gettyio.gim.agent.core.chat.ToolConfirmRequest;
import com.gettyio.gim.agent.domain.enums.PermissionDecision;
import com.gettyio.gim.agent.tools.hitl.HitlConfirmHandler;
import com.gettyio.gim.agent.ui.I18n;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * JavaFX 弹窗式人工确认处理器（@Primary 覆盖无 UI 的默认拒绝实现）。
 * <p>在 FX 线程弹出确认对话框，用户选择"允许/总是允许/拒绝"回填异步决策。
 * 按钮与文案按当前语言即时构建。</p>
 */
@Component
public class FxHitlConfirmHandler implements HitlConfirmHandler {

    @Override
    public CompletableFuture<PermissionDecision> requestConfirm(ToolConfirmRequest request) {
        CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            ButtonType allow = new ButtonType(I18n.t("hitl.allow"), ButtonType.APPLY.getButtonData());
            ButtonType always = new ButtonType(I18n.t("hitl.always"), ButtonType.APPLY.getButtonData());
            ButtonType deny = new ButtonType(I18n.t("hitl.deny"), ButtonType.CANCEL.getButtonData());

            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle(I18n.t("hitl.title"));
            alert.setHeaderText(I18n.t("hitl.header", request.getToolName()));
            alert.setContentText(buildContent(request));
            alert.getButtonTypes().setAll(always, allow, deny);

            Optional<ButtonType> chosen = alert.showAndWait();
            PermissionDecision decision = chosen.filter(b -> b == allow).map(b -> PermissionDecision.ALLOW)
                    .or(() -> chosen.filter(b -> b == always).map(b -> PermissionDecision.ALWAYS_ALLOW))
                    .orElse(PermissionDecision.DENY);
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
