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

/**
 * 语音能力：朗读合成与语音输入（转写）。
 * <p>{@link TtsService} 通过 OpenAI 兼容 {@code /audio/speech} 合成 MP3 并播放；
 * {@link VoiceInputService} 以「按住说话」录音（16kHz PCM）并经 {@code /audio/transcriptions}
 * 转写为文本；{@link MicHoldButton} 为输入条上的按住说话按钮；
 * {@link VoiceSettingsStore} 收敛两服务共用的 settings 键值读写与 API Key 加解密存取。</p>
 */
package com.gettyio.gim.agent.ui.voice;
