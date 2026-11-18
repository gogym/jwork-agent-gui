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

import com.gettyio.jwork.agent.ui.control.CardKit;
import com.gettyio.jwork.agent.ui.i18n.I18n;
import com.gettyio.jwork.agent.ui.voice.TtsService;
import com.gettyio.jwork.agent.ui.voice.VoiceInputService;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音设置子面板（独立菜单页，抽自 {@link GeneralSettingsPanel}）：语音输入（转写）与语音朗读（TTS）两张卡片。
 * <p>两者同属语音服务域——输入卡片配置 OpenAI 兼容转写服务（地址/模型/Key），朗读卡片配置合成服务
 * （地址/模型/音色/Key）并带「试听」。文本框失焦/回车即保存，改后按住说话或朗读按钮即生效；
 * API Key 经 SecretCipher 加密存 settings。</p>
 *
 * @author gogym
 */
final class VoiceSettingsPanel {

    private final SettingsWindow host;
    private final VoiceInputService voiceInputService;
    private final TtsService ttsService;

    VoiceSettingsPanel(SettingsWindow host, VoiceInputService voiceInputService, TtsService ttsService) {
        this.host = host;
        this.voiceInputService = voiceInputService;
        this.ttsService = ttsService;
    }

    VBox build() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.voice"), I18n.t("settings.voice.subtitle"));
        box.getChildren().add(voiceCard());
        box.getChildren().add(ttsCard());
        return box;
    }

    /**
     * 语音输入卡片：转写服务地址 / 模型 / API Key（SecretCipher 加密存 settings），
     * 文本框失焦/回车即保存，改后按住说话按钮即生效。
     */
    private VBox voiceCard() {
        TextField urlField = new TextField(voiceInputService.transcriptionBaseUrl());
        urlField.setPromptText(I18n.t("settings.voice.url.prompt"));
        urlField.setMaxWidth(360);
        TextField modelField = new TextField(voiceInputService.transcriptionModel());
        modelField.setPromptText(I18n.t("settings.voice.model.prompt"));
        modelField.setMaxWidth(200);
        PasswordField keyField = new PasswordField();
        keyField.setText(voiceInputService.apiKeyPlain());
        keyField.setMaxWidth(200);

        Runnable save = () -> {
            voiceInputService.saveTranscriptionBaseUrl(urlField.getText());
            voiceInputService.saveTranscriptionModel(modelField.getText());
            voiceInputService.saveApiKey(keyField.getText());
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
        rows.add(SettingsLayout.settingRow(I18n.t("settings.voice.url"),
                I18n.t("settings.voice.url.desc"), urlField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.voice.model"),
                I18n.t("settings.voice.model.desc"), modelField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.voice.apiKey"),
                I18n.t("settings.voice.apiKey.desc"), keyField));
        return SettingsLayout.groupCard(I18n.t("settings.voice.group"), rows);
    }

    /**
     * 语音朗读卡片：合成服务地址 / 模型 / 音色 / API Key（SecretCipher 加密存 settings），
     * 文本框失焦/回车即保存；「试听」先落存当前输入再合成一段示例文本（播放中再点会被新一轮打断）。
     */
    private VBox ttsCard() {
        TextField urlField = new TextField(ttsService.baseUrl());
        urlField.setPromptText(I18n.t("settings.tts.url.prompt"));
        urlField.setMaxWidth(360);
        TextField modelField = new TextField(ttsService.model());
        modelField.setPromptText(I18n.t("settings.tts.model.prompt"));
        modelField.setMaxWidth(200);
        TextField voiceField = new TextField(ttsService.voice());
        voiceField.setPromptText(I18n.t("settings.tts.voice.prompt"));
        voiceField.setMaxWidth(200);
        PasswordField keyField = new PasswordField();
        keyField.setText(ttsService.apiKeyPlain());
        keyField.setMaxWidth(200);

        Runnable save = () -> {
            ttsService.saveBaseUrl(urlField.getText());
            ttsService.saveModel(modelField.getText());
            ttsService.saveVoice(voiceField.getText());
            ttsService.saveApiKey(keyField.getText());
        };
        for (TextField f : List.of(urlField, modelField, voiceField, keyField)) {
            f.setOnAction(e -> save.run());
            f.focusedProperty().addListener((obs, was, now) -> {
                if (!now) {
                    save.run();
                }
            });
        }
        Button preview = CardKit.iconTextButton("\uE768", I18n.t("settings.tts.preview"), () -> {
            save.run();
            if (!ttsService.isConfigured()) {
                host.notify("settings.tts.group", I18n.t("voice.tts.notConfigured"));
                return;
            }
            ttsService.speak(I18n.t("settings.tts.preview.text"), err -> {
                if (err != null && !err.isBlank()) {
                    host.notify("settings.tts.group", I18n.t("voice.tts.failed", err));
                }
            });
        });

        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.settingRow(I18n.t("settings.tts.url"),
                I18n.t("settings.tts.url.desc"), urlField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.tts.model"),
                I18n.t("settings.tts.model.desc"), modelField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.tts.voice"),
                I18n.t("settings.tts.voice.desc"), voiceField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.tts.apiKey"),
                I18n.t("settings.tts.apiKey.desc"), keyField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.tts.preview"), null, preview));
        return SettingsLayout.groupCard(I18n.t("settings.tts.group"), rows);
    }
}
