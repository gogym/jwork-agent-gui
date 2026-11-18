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

package com.gettyio.jwork.agent.ui.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.security.SecretCipher;
import com.gettyio.jwork.agent.domain.util.HttpMedia;
import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * 语音朗读服务：OpenAI 兼容 {@code POST /audio/speech} 合成 MP3 → 临时文件 → JavaFX MediaPlayer 播放。
 * <p>配置走 {@code settings} 键值表（API Key 加密存储），改配无需重启。合成在后台线程，
 * 播放与状态都在 FX 线程；全局单播放器——新一轮朗读先打断旧的，被打断方经结束回调查收
 * （自然播完 / 播放出错 / 被打断 / 合成失败统一从 {@code onEnded} 传出，错误消息为空表示正常）。
 * 朗读前正文先经 {@link #markdownToPlainText} 清理，避免把 Markdown 标记读出声。</p>
 *
 * @author gogym
 */
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String KEY_BASEURL = "voice.tts.baseUrl";
    private static final String KEY_MODEL = "voice.tts.model";
    private static final String KEY_VOICE = "voice.tts.voice";
    private static final String KEY_APIKEY = "voice.tts.apiKey";

    /** speech 接口单次输入上限（字符）：超长在句读边界回退截断，第一版不做分段拼接。 */
    private static final int MAX_INPUT_CHARS = 4000;

    /** 未配置音色时的回退值（多数 OpenAI 兼容服务端要求 voice 必填）。 */
    private static final String DEFAULT_VOICE = "alloy";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final VoiceSettingsStore settings;

    /** 当前播放器；null 表示无在播音频。仅 FX 线程读写。 */
    private MediaPlayer player;
    /** 当前在播音频的临时文件；随播放终结删除。仅 FX 线程读写。 */
    private Path currentFile;
    /** 当前一轮朗读的结束回调（含合成在途阶段）；null 表示空闲。仅 FX 线程读写。 */
    private Consumer<String> currentEnded;
    /** 朗读代际：打断/终结时自增，使在途合成链与陈旧播放回调失效。仅 FX 线程读写。 */
    private long generation;

    public TtsService(JworkDatabase db, SecretCipher cipher) {
        this.settings = new VoiceSettingsStore(db, cipher);
    }

    // ===================== 朗读配置（settings 持久化，Key 密文） =====================

    /** 合成服务协议根（OpenAI 兼容；未配置返回空串）。 */
    public String baseUrl() {
        return settings.get(KEY_BASEURL);
    }

    public void saveBaseUrl(String url) {
        settings.put(KEY_BASEURL, url, "语音朗读服务地址");
    }

    /** 合成模型编码（如 tts-1）。 */
    public String model() {
        return settings.get(KEY_MODEL);
    }

    public void saveModel(String model) {
        settings.put(KEY_MODEL, model, "语音朗读模型");
    }

    /** 音色名（如 alloy / nova；未配置回退 {@link #DEFAULT_VOICE}）。 */
    public String voice() {
        String v = settings.get(KEY_VOICE);
        return v.isEmpty() ? DEFAULT_VOICE : v;
    }

    public void saveVoice(String voice) {
        settings.put(KEY_VOICE, voice, "语音朗读音色");
    }

    /** 明文 API Key（密文存储，取出即解密）；未配置或解密失败返回空串。 */
    public String apiKeyPlain() {
        return settings.apiKeyPlain(KEY_APIKEY);
    }

    public void saveApiKey(String plain) {
        settings.saveApiKey(KEY_APIKEY, plain, "语音朗读 API Key（密文）");
    }

    /** 是否已配置可用的朗读服务（地址 + 模型齐备；Key 视服务端可匿名）。 */
    public boolean isConfigured() {
        return !baseUrl().isEmpty() && !model().isEmpty();
    }

    // ===================== 朗读（合成 + 播放） =====================

    /**
     * 朗读一段 Markdown 文本（须在 FX 线程调用）：先打断当前朗读，再后台合成并播放。
     *
     * @param markdown 原文（自动清理为纯文本；空白内容不发声、立即回调）
     * @param onEnded  结束回调（FX 线程）：参数为错误消息，正常结束/被打断为 {@code null}
     */
    public void speak(String markdown, Consumer<String> onEnded) {
        stopInternal();
        String text = markdownToPlainText(markdown);
        if (text.isBlank()) {
            onEnded.accept(null);
            return;
        }
        if (text.length() > MAX_INPUT_CHARS) {
            text = truncate(text, MAX_INPUT_CHARS);
        }
        long gen = ++generation;
        currentEnded = onEnded;
        String input = text;
        Thread synth = new Thread(() -> {
            try {
                byte[] audio = synthesize(input);
                Platform.runLater(() -> play(gen, audio));
            } catch (Exception e) {
                log.warn("语音合成失败", e);
                String msg = e.getMessage() == null ? "合成失败" : e.getMessage();
                Platform.runLater(() -> finish(gen, msg));
            }
        }, "tts-synth");
        synth.setDaemon(true);
        synth.start();
    }

    /** 停止当前朗读（若有）：被打断方经其结束回调收到通知。须在 FX 线程调用。 */
    public void stop() {
        stopInternal();
    }

    /** 打断并终结当前一轮（若有）：作废在途合成、释放播放器、回调旧监听。FX 线程。 */
    private void stopInternal() {
        if (currentEnded == null) {
            return;
        }
        generation++;
        Consumer<String> ended = currentEnded;
        currentEnded = null;
        disposePlayer();
        ended.accept(null);
    }

    /** 合成完成：把音频写入临时文件并起播（FX 线程）；本轮已被打断则丢弃。 */
    private void play(long gen, byte[] audio) {
        if (gen != generation || currentEnded == null) {
            return;
        }
        try {
            Path file = Files.createTempFile("jwork-tts-", ".mp3");
            Files.write(file, audio);
            MediaPlayer p = new MediaPlayer(new Media(file.toUri().toString()));
            player = p;
            currentFile = file;
            p.setOnEndOfMedia(() -> finish(gen, null));
            p.setOnError(() -> finish(gen, "音频播放失败"));
            p.play();
        } catch (Exception e) {
            log.warn("语音播放启动失败", e);
            finish(gen, e.getMessage() == null ? "播放失败" : e.getMessage());
        }
    }

    /** 一轮朗读的统一终结出口：正常播完 / 播放出错 / 合成失败共用；陈旧代际的回调直接丢弃。 */
    private void finish(long gen, String error) {
        if (gen != generation) {
            return;
        }
        generation++;
        Consumer<String> ended = currentEnded;
        currentEnded = null;
        disposePlayer();
        if (ended != null) {
            ended.accept(error);
        }
    }

    /** 释放播放器与临时文件（尽力而为；文件句柄未完全释放时交由系统临时目录回收）。 */
    private void disposePlayer() {
        if (player != null) {
            player.stop();
            player.dispose();
            player = null;
        }
        if (currentFile != null) {
            try {
                Files.deleteIfExists(currentFile);
            } catch (Exception ignored) {
                // 播放器仍持有句柄时无法删除，留待系统清理
            }
            currentFile = null;
        }
    }

    // ===================== 合成与文本清理 =====================

    /** 后台线程：POST /audio/speech 取音频字节；非 2xx 抛带响应摘要的异常。 */
    private byte[] synthesize(String text) throws Exception {
        String base = baseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", model());
        payload.put("input", text);
        payload.put("voice", voice());
        payload.put("response_format", "mp3");

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/audio/speech"))
                .timeout(Duration.ofSeconds(60))
                .header(HttpMedia.CONTENT_TYPE, HttpMedia.APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
        String key = apiKeyPlain();
        if (!key.isEmpty()) {
            builder.header("Authorization", "Bearer " + key);
        }
        HttpResponse<byte[]> resp = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            String body = new String(resp.body(), StandardCharsets.UTF_8);
            String brief = body.isBlank() ? "" : "：" + body.substring(0, Math.min(200, body.length()));
            throw new IllegalStateException("合成服务返回 HTTP " + resp.statusCode() + brief);
        }
        byte[] audio = resp.body();
        if (audio == null || audio.length == 0) {
            throw new IllegalStateException("合成服务返回空音频");
        }
        log.info("语音合成完成: {} 字符 -> {} 字节", text.length(), audio.length);
        return audio;
    }

    /** 超长文本按句读边界回退截断（含截断点字符）；找不到合适边界则硬截。 */
    private static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int cut = Math.max(
                Math.max(text.lastIndexOf('。', max), text.lastIndexOf('\n', max)),
                Math.max(text.lastIndexOf('！', max), text.lastIndexOf('？', max)));
        if (cut < max / 2) {
            cut = max - 1;
        }
        return text.substring(0, cut + 1);
    }

    /**
     * Markdown 源文转朗读纯文本：围栏代码块整段跳过（含围栏行），图片移除，链接/强调/行内代码/
     * 标题/引用/列表/表格标记清淡化，HTML 标签移除。纯函数，包私有便于单测。
     */
    static String markdownToPlainText(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean inFence = false;
        for (String line : markdown.split("\\R", -1)) {
            String t = line.strip();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            if (t.isEmpty()) {
                out.append('\n');
                continue;
            }
            if (t.matches("([-*_])\\1{2,}")) {
                continue; // 水平线
            }
            t = t.replaceFirst("^#{1,6}\\s*", "");
            t = t.replaceFirst("^>\\s?", "");
            t = t.replaceFirst("^([-*+]|\\d+[.)])\\s+", "");
            t = t.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "");
            t = t.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
            t = t.replaceAll("`([^`]*)`", "$1");
            t = t.replaceAll("\\*{1,3}([^*]+)\\*{1,3}", "$1");
            t = t.replaceAll("_{1,3}([^_]+)_{1,3}", "$1");
            t = t.replaceAll("<[^>]+>", "");
            t = t.replace('|', ' ');
            t = t.replaceAll("\\s{2,}", " ").strip();
            if (!t.isEmpty()) {
                out.append(t).append('\n');
            }
        }
        return out.toString().strip();
    }
}
