package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.config.SecurityAdminService;
import javafx.scene.Node;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 图像生成设置子面板（独立菜单页，抽自 {@link GeneralSettingsPanel}）：生成服务地址 / 模型 / API Key。
 * <p>OpenAI 兼容 {@code /images/generations} 协议；地址与模型齐备即视为可用（无鉴权的本地服务可留空 Key）。
 * 聊天页「生成图片」入口与 Agent 的 {@code generate_image} 工具共用这份配置，文本框失焦/回车即保存、改配即生效。</p>
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
        PasswordField keyField = new PasswordField();
        keyField.setText(securityAdminService.getImageApiKeyPlain());
        keyField.setMaxWidth(200);

        Runnable save = () -> {
            securityAdminService.setImageBaseUrl(urlField.getText());
            securityAdminService.setImageModel(modelField.getText());
            securityAdminService.setImageApiKey(keyField.getText());
        };
        for (TextField f : List.of(urlField, modelField, keyField)) {
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
                I18n.t("settings.image.apiKey.desc"), keyField));
        box.getChildren().add(SettingsLayout.groupCard(I18n.t("settings.image.group"), rows));
        return box;
    }
}
