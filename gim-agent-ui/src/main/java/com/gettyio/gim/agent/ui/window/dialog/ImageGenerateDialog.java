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

package com.gettyio.gim.agent.ui.window.dialog;

import com.gettyio.gim.agent.ui.i18n.I18n;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

/**
 * 「生成图片」提示词输入对话框：无边框圆角壳（{@link GimDialog#shell}）+ 多行输入框 + 取消/生成。
 * <p>生图提示词通常较长，单行走 {@link GimDialog#promptText} 不够，这里用 TextArea 承载；
 * 首尾空白剔除后为空时「生成」不可点，Esc/关闭等价取消。</p>
 *
 * @author gogym
 */
public final class ImageGenerateDialog {

    /** 提示词字符上限：生图服务的普遍口径。 */
    private static final int MAX_LENGTH = 2000;

    private ImageGenerateDialog() {
    }

    /** 弹出对话框；确定返回去首尾空白的提示词，取消/关闭返回空。 */
    public static Optional<String> show(Window owner) {
        String[] saved = {null};
        GimDialog.Shell shell = GimDialog.shell(owner, I18n.t("image.dialog.title"));

        Label cap = new Label(I18n.t("image.dialog.label"));
        cap.getStyleClass().add("form-label");
        TextArea area = new TextArea();
        area.setPromptText(I18n.t("image.dialog.prompt"));
        area.setPrefRowCount(4);
        area.setWrapText(true);
        area.setMaxWidth(420);
        // TextArea 没有 setMaxLength，超限的改动直接交给过滤器丢掉
        area.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().length() <= MAX_LENGTH ? change : null));

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setMinWidth(76);
        Button ok = new Button(I18n.t("image.dialog.confirm"));
        ok.getStyleClass().add("btn-primary");
        ok.setMinWidth(76);
        ok.setDisable(true);
        area.textProperty().addListener((obs, o, n) -> ok.setDisable(n == null || n.isBlank()));
        cancel.setOnAction(e -> shell.stage().close());
        ok.setOnAction(e -> {
            String text = area.getText() == null ? "" : area.getText().trim();
            if (!text.isEmpty()) {
                saved[0] = text;
                shell.stage().close();
            }
        });

        HBox actions = new HBox(8, cancel, ok);
        actions.getStyleClass().add("dialog-actions");
        VBox form = new VBox(4, cap, area);
        shell.body().getChildren().addAll(form, actions);
        shell.showAndWait();
        return Optional.ofNullable(saved[0]);
    }
}
