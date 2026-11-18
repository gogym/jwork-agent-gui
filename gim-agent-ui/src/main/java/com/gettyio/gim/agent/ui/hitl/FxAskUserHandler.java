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

import com.gettyio.gim.agent.tools.hitl.AskUserHandler;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.util.UiStyles;
import com.gettyio.gim.agent.ui.window.AppShell;
import com.gettyio.gim.agent.ui.window.dialog.GimDialog;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * JavaFX 反问处理器（{@code ask_user} 的界面实现）。
 * <p>问题占一行正文，候选项竖排各占一个按钮（横向排六个按钮会把弹窗拉变形），
 * 底部固定「其他… / 由你判断」两颗退路：前者让模型自己拍板，后者关窗后再开单行输入框——
 * 两层模态嵌套着弹不稳，串成两次 showAndWait 才靠得住。</p>
 * <p>主窗口没在显示（后台定时任务、尚未登录的启动期）时不弹窗也不挂线程，直接回 null，
 * 由工具侧换成「自己判断并把假设写进回答」。</p>
 *
 * @author gogym
 */
public class FxAskUserHandler implements AskUserHandler {

    /** 「其他…」的中转值：点它先关掉选项卡，再开单行输入框（只在类内流转，不外传）。 */
    private static final String CUSTOM = "::custom::";

    /** 自由输入的答案上限：一句话说清即可，长文该走聊天输入框而不是这个弹窗。 */
    private static final int ANSWER_MAX_CHARS = 200;

    /** 弹窗正文宽度上限，与 HITL 确认卡的文本区保持一致。 */
    private static final double CONTENT_MAX_WIDTH = 520;

    private final AppShell appShell;

    /** 由组合根装配（主窗外壳提供对话框 owner 与「窗口是否在显示」的判据）。 */
    public FxAskUserHandler(AppShell appShell) {
        this.appShell = appShell;
    }

    @Override
    public CompletableFuture<String> ask(String question, List<String> options) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Platform.runLater(() -> future.complete(show(question, options)));
        return future;
    }

    /** 弹一次问题，返回答案；关窗（X / ESC）与无人可问都返回 null。 */
    private String show(String question, List<String> options) {
        Stage stage = appShell.getStage();
        if (stage == null || !stage.isShowing()) {
            return null;
        }
        GimDialog.Shell shell = GimDialog.shell(stage, I18n.t("ask.title"));

        Label head = new Label(question);
        head.getStyleClass().add(UiStyles.DIALOG_HEADER);
        head.setWrapText(true);
        head.setMaxWidth(CONTENT_MAX_WIDTH);
        shell.body().getChildren().add(head);

        String[] picked = {null};
        if (!options.isEmpty()) {
            VBox choices = new VBox(8);
            for (String option : options) {
                choices.getChildren().add(answerButton(shell.stage(), option, option, picked));
            }
            shell.body().getChildren().add(choices);
        }

        HBox actions = new HBox(8);
        actions.getStyleClass().add(UiStyles.DIALOG_ACTIONS);
        actions.getChildren().addAll(
                answerButton(shell.stage(), I18n.t("ask.other"), CUSTOM, picked),
                answerButton(shell.stage(), I18n.t("ask.youDecide"), AskUserHandler.YOU_DECIDE, picked));
        shell.body().getChildren().add(actions);

        shell.showAndWait();
        if (!CUSTOM.equals(picked[0])) {
            return picked[0];
        }
        // 选项里没有想要的答法：换单行输入框现写一条，取消即等于没答
        return GimDialog.promptText(
                        stage, I18n.t("ask.title"), I18n.t("ask.customPrompt"), "", ANSWER_MAX_CHARS)
                .orElse(null);
    }

    private static Button answerButton(Stage stage, String text, String answer, String[] picked) {
        Button button = new Button(text);
        button.setMinWidth(76);
        button.setMaxWidth(CONTENT_MAX_WIDTH);
        // 候选项常是半句话，撑宽不如折行：按钮默认不折字，得显式打开
        button.setWrapText(true);
        button.setOnAction(e -> {
            picked[0] = answer;
            stage.close();
        });
        return button;
    }
}
