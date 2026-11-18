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

package com.gettyio.jwork.agent.ui.window.settings;

import com.gettyio.jwork.agent.ui.i18n.I18n;
import com.gettyio.jwork.agent.ui.config.SecurityAdminService;
import com.gettyio.jwork.agent.ui.control.SecretField;
import javafx.scene.Node;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 图像生成设置子面板（独立菜单页，抽自 {@link GeneralSettingsPanel}）：生成服务地址 / 模型 / API Key。
 * <p>OpenAI 兼容 {@code /images/generations} 协议；地址与模型齐备即视为可用（无鉴权的本地服务不必录 Key）。
 * 聊天页「生成图片」入口与 Agent 的 {@code generate_image} 工具共用这份配置，文本框失焦/回车即保存、改配即生效。</p>
 * <p>API Key 走 {@link SecretField}：已存密钥不回显，留空只表示「不修改」。</p>
 *
 * @author gogym
 */
final class ImageSettingsPanel {

    /** 图像生成配置（地址/模型/Key）的读写后端：保存即生效。 */
    private final SecurityAdminService securityAdminService;

    ImageSettingsPanel(SecurityAdminService securityAdminService) {
        this.securityAdminService = securityAdminService;
    }

    VBox build() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.image"), I18n.t("settings.image.subtitle"));

        TextField urlField = new TextField(securityAdminService.getImageBaseUrl());
        urlField.setPromptText(I18n.t("settings.image.url.prompt"));
        urlField.setMaxWidth(360);
        TextField modelField = new TextField(securityAdminService.getImageModel());
        modelField.setPromptText(I18n.t("settings.image.model.prompt"));
        modelField.setMaxWidth(200);
        SecretField apiKey = new SecretField(securityAdminService::getImageApiKeyState,
                securityAdminService::setImageApiKey, securityAdminService::clearImageApiKey);
        apiKey.field().setPromptText(I18n.t("settings.secret.prompt"));

        Runnable save = () -> {
            securityAdminService.setImageBaseUrl(urlField.getText());
            securityAdminService.setImageModel(modelField.getText());
        };
        for (TextField f : List.of(urlField, modelField)) {
            f.setOnAction(e -> save.run());
            f.focusedProperty().addListener((obs, was, now) -> {
                if (!now) {
                    save.run();
                }
            });
        }
        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.settingRow(I18n.t("settings.image.url"),
                I18n.t("settings.image.url.desc"), urlField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.image.model"),
                I18n.t("settings.image.model.desc"), modelField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.image.apiKey"),
                I18n.t("settings.image.apiKey.desc"), apiKey.control()));
        box.getChildren().add(SettingsLayout.groupCard(I18n.t("settings.image.group"), rows));
        return box;
    }
}
