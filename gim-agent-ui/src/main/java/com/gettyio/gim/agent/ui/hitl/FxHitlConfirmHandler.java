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

package com.gettyio.gim.agent.ui.hitl;

import com.gettyio.gim.agent.core.chat.ToolCallsCodec;
import com.gettyio.gim.agent.core.chat.ToolCallsCodec.ToolCallView;
import com.gettyio.gim.agent.core.chat.ToolConfirmRequest;
import com.gettyio.gim.agent.domain.enums.PermissionDecision;
import com.gettyio.gim.agent.tools.hitl.HitlConfirmHandler;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.window.AppShell;
import com.gettyio.gim.agent.ui.window.dialog.GimDialog;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * JavaFX 弹窗式人工确认处理器（应用默认的 HITL 通道）。
 * <p>在 FX 线程弹出确认对话框，用户选择"允许/总是允许/拒绝"回填异步决策。
 * 按钮与文案按当前语言即时构建。对话框以主窗口为 owner，居中弹出并把背景磨玻璃化。</p>
 * <p>文件类工具（{@code edit_file}/{@code write_file}）升级为 diff 预览卡：
 * 从 {@link ToolCallsCodec} 还原的结构化入参里提取 path 与 old_string/new_string，
 * 以红删绿增的等宽行展示变更内容，其余工具维持原文本确认框。</p>
 *
 * @author gogym
 */
public class FxHitlConfirmHandler implements HitlConfirmHandler {

    /** 走 diff 预览卡的文件工具（工具名与 FilesystemTool 注册名一致）。 */
    private static final Set<String> FILE_EDIT_TOOLS = Set.of("edit_file", "write_file");
    /** 单个字段最多渲染的行数，超出截断，避免超长文件把弹窗撑爆。 */
    private static final int MAX_DIFF_LINES = 60;

    private static final Font MONO = Font.font("Consolas", 12.5);
    private static final Color DIFF_REMOVED = Color.web("#D12F2C");
    private static final Color DIFF_ADDED = Color.web("#2da84b");
    private static final Color DIFF_PATH = Color.web("#0EA5E9");

    private final AppShell appShell;

    /** 由组合根装配（主窗外壳提供对话框 owner 与磨玻璃背景）。 */
    public FxHitlConfirmHandler(AppShell appShell) {
        this.appShell = appShell;
    }

    @Override
    public CompletableFuture<PermissionDecision> requestConfirm(ToolConfirmRequest request) {
        CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            List<ToolCallView> calls = ToolCallsCodec.decode(request.getToolInput());
            PermissionDecision decision = isFileEdit(calls)
                    ? showDiffDialog(request, calls)
                    : showPlainDialog(request);
            future.complete(decision);
        });
        return future;
    }

    /** 原行为：风险摘要 + 入参文本的通用确认框。 */
    private PermissionDecision showPlainDialog(ToolConfirmRequest request) {
        int choice = GimDialog.choose(appShell.getStage(),
                I18n.t("hitl.title"),
                I18n.t("hitl.header", request.getToolName()),
                buildContent(request),
                I18n.t("hitl.always"), I18n.t("hitl.session"), I18n.t("hitl.allow"), I18n.t("hitl.deny"));
        return switch (choice) {
            case 0 -> PermissionDecision.ALWAYS_ALLOW;
            case 1 -> PermissionDecision.SESSION_ALLOW;
            case 2 -> PermissionDecision.ALLOW;
            default -> PermissionDecision.DENY;
        };
    }

    /**
     * diff 预览确认卡：文件路径 + 红删绿增的变更行，附风险摘要；
     * 关闭（X / ESC）不选任何按钮，按拒绝处理。
     */
    private PermissionDecision showDiffDialog(ToolConfirmRequest request, List<ToolCallView> calls) {
        GimDialog.Shell shell = GimDialog.shell(appShell.getStage(), I18n.t("hitl.title"));

        Label header = new Label(I18n.t("hitl.header", request.getToolName()));
        header.getStyleClass().add("dialog-header");
        header.setWrapText(true);
        shell.body().getChildren().add(header);

        if (request.getRiskSummary() != null && !request.getRiskSummary().isBlank()) {
            Label risk = new Label(I18n.t("hitl.risk", request.getRiskSummary()));
            risk.getStyleClass().add("dialog-message");
            risk.setWrapText(true);
            risk.setMaxWidth(600);
            shell.body().getChildren().add(risk);
        }

        TextFlow diff = new TextFlow();
        diff.setMaxWidth(600);
        for (ToolCallView call : calls) {
            appendFileDiff(diff, call);
        }
        ScrollPane scroll = new ScrollPane(diff);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(false);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMaxHeight(380);
        scroll.setPrefWidth(640);
        scroll.setStyle("-fx-background-insets: 0; -fx-padding: 6;");
        shell.body().getChildren().add(scroll);

        int[] picked = {-1};
        HBox actions = new HBox(8);
        actions.getStyleClass().add("dialog-actions");
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getChildren().addAll(
                diffButton(I18n.t("hitl.always"), shell.stage(), 0, picked),
                diffButton(I18n.t("hitl.session"), shell.stage(), 3, picked),
                diffButton(I18n.t("hitl.deny"), shell.stage(), 1, picked),
                diffButton(I18n.t("hitl.allow"), shell.stage(), 2, picked));
        shell.body().getChildren().add(actions);

        shell.showAndWait();
        // 按钮序：总是允许 / 本会话允许 / 拒绝 / 允许（默认项）；关闭或 ESC 落到拒绝
        return switch (picked[0]) {
            case 0 -> PermissionDecision.ALWAYS_ALLOW;
            case 3 -> PermissionDecision.SESSION_ALLOW;
            case 2 -> PermissionDecision.ALLOW;
            default -> PermissionDecision.DENY;
        };
    }

    private Button diffButton(String text, javafx.stage.Stage stage, int index, int[] picked) {
        Button b = new Button(text);
        if (index == 2) {
            b.getStyleClass().add("btn-primary");
        }
        b.setMinWidth(76);
        b.setOnAction(e -> {
            picked[0] = index;
            stage.close();
        });
        return b;
    }

    private static boolean isFileEdit(List<ToolCallView> calls) {
        return !calls.isEmpty()
                && calls.stream().allMatch(c -> FILE_EDIT_TOOLS.contains(c.name()));
    }

    /** 渲染单个文件调用：路径标题行 + edit_file 的删/增两块，或 write_file 的新增一块（覆盖写先亮一行提醒）。 */
    private static void appendFileDiff(TextFlow flow, ToolCallView call) {
        String path = call.text("path");
        Text head = mono((path == null || path.isBlank() ? call.name() : path) + "\n", DIFF_PATH);
        head.setStyle("-fx-font-weight: bold;");
        flow.getChildren().add(head);
        if ("edit_file".equals(call.name())) {
            appendLines(flow, call.text("old_string"), "- ", DIFF_REMOVED);
            appendLines(flow, call.text("new_string"), "+ ", DIFF_ADDED);
        } else {
            if (call.flag("overwrite")) {
                // 覆盖写看不见被抹掉的旧内容，确认框里先亮一行提醒
                appendLines(flow, I18n.t("hitl.overwriteNote"), "- ", DIFF_REMOVED);
            }
            appendLines(flow, call.text("content"), "+ ", DIFF_ADDED);
        }
        flow.getChildren().add(mono("\n", Color.TRANSPARENT));
    }

    /** 按行加前缀渲染，空内容显示占位，超限截断。 */
    private static void appendLines(TextFlow flow, String content, String prefix, Color color) {
        if (content == null || content.isEmpty()) {
            flow.getChildren().add(mono(prefix + I18n.t("hitl.empty") + "\n", color));
            return;
        }
        String[] lines = content.split("\n", -1);
        int shown = Math.min(lines.length, MAX_DIFF_LINES);
        for (int i = 0; i < shown; i++) {
            flow.getChildren().add(mono(prefix + lines[i] + "\n", color));
        }
        if (lines.length > shown) {
            flow.getChildren().add(mono("… " + I18n.t("hitl.truncated") + "\n", Color.GRAY));
        }
    }

    private static Text mono(String text, Color fill) {
        Text t = new Text(text);
        t.setFont(MONO);
        t.setFill(fill);
        return t;
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
