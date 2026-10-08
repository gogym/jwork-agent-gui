package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.core.plan.PlanService;
import com.gettyio.gim.agent.ui.I18n;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.stage.Window;

/**
 * 计划确认对话框：展示规划轮产出的 {@code plans/PLAN.md}，用户可直接编辑后保存。
 * <p>保存写回同一文件，下一轮取消勾选规划模式即按此计划执行；取消则保留原文件不动。
 * 从 {@code MainController} 抽出的独立组件，规划轮完成回调与后续计划模式 UI 共用。</p>
 */
public final class PlanReviewDialog {

    private PlanReviewDialog() {
    }

    /**
     * 弹出计划预览/编辑框；计划文件不存在时静默返回。
     *
     * @param owner     归属窗口（模态与居中定位）
     * @param planService 计划文件读写服务（路径与运行时装配口径一致）
     * @param agentCode 智能体编码（决定读哪个工作区的计划）
     */
    public static void show(Window owner, PlanService planService, String agentCode) {
        if (planService == null || !planService.exists(agentCode)) {
            return;
        }
        GimDialog.Shell shell = GimDialog.shell(owner, I18n.t("plan.review.title"));
        Label hint = new Label(I18n.t("plan.review.hint"));
        hint.getStyleClass().add("dialog-message");
        hint.setWrapText(true);
        hint.setMaxWidth(640);
        TextArea area = new TextArea(planService.read(agentCode));
        area.setWrapText(true);
        area.getStyleClass().add("memory-area");
        area.setPrefColumnCount(72);
        area.setPrefRowCount(20);
        area.setMaxWidth(Double.MAX_VALUE);
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setMinWidth(76);
        cancel.setOnAction(e -> shell.stage().close());
        Button save = new Button(I18n.t("plan.review.save"));
        save.getStyleClass().add("btn-primary");
        save.setMinWidth(76);
        save.setOnAction(e -> {
            planService.write(agentCode, area.getText());
            shell.stage().close();
        });
        HBox actions = new HBox(8, cancel, save);
        actions.getStyleClass().add("dialog-actions");
        actions.setAlignment(Pos.CENTER_RIGHT);
        shell.body().getChildren().addAll(hint, area, actions);
        shell.showAndWait();
    }
}
