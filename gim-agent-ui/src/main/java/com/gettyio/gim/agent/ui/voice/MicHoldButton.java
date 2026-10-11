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

package com.gettyio.gim.agent.ui.voice;

import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.theme.UiTheme;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 「按住说话」麦克风按钮：按下起录、松开停录并转写，识别文本经回调交还宿主输入框。
 * <p>主聊天页与 QuickAsk 小窗共用同一安装逻辑；转写在后台线程执行，回 UI 线程后填入。
 * 未配置转写服务时按下即给引导（onMissingConfig），不进入录音。</p>
 *
 * @author gogym
 */
public final class MicHoldButton {

    private MicHoldButton() {
    }

    /**
     * 把普通按钮改装成按住说话麦克风钮。
     *
     * @param onText 识别文本回填（FX 线程回调）
     * @param onMissingConfig 未配置转写服务时的引导（FX 线程回调）
     * @param onError 错误提示 (标题, 消息)（FX 线程回调）
     */
    public static void install(Button mic, VoiceInputService voice, Consumer<String> onText,
                               Runnable onMissingConfig, BiConsumer<String, String> onError) {
        mic.getStyleClass().add("attach-button");
        mic.setGraphic(UiTheme.icon("\uE720"));
        mic.setTooltip(new Tooltip(I18n.t("voice.mic.hold")));
        mic.setMinWidth(36);

        mic.setOnMousePressed(e -> {
            if (!voice.isConfigured()) {
                onMissingConfig.run();
                return;
            }
            try {
                voice.startRecording();
                mic.setText(I18n.t("voice.mic.recording"));
            } catch (RuntimeException ex) {
                onError.accept(I18n.t("voice.title"), ex.getMessage());
            }
        });
        mic.setOnMouseReleased(e -> {
            if (!voice.isRecording()) {
                return;
            }
            mic.setText("");
            Thread t = new Thread(() -> {
                try {
                    String text = voice.stopRecordingAndTranscribe();
                    if (text != null && !text.isEmpty()) {
                        Platform.runLater(() -> onText.accept(text));
                    }
                } catch (RuntimeException ex) {
                    Platform.runLater(() -> onError.accept(I18n.t("voice.title"), ex.getMessage()));
                }
            }, "voice-transcribe");
            t.setDaemon(true);
            t.start();
        });
    }
}
