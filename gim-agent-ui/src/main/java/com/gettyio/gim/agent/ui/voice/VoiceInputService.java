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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.security.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 语音输入服务：麦克风录音（javax.sound.sampled，16kHz/16bit/单声道 PCM）→ WAV 封装 →
 * OpenAI 兼容 {@code POST /audio/transcriptions} 转写为文本。
 * <p>配置走 {@code settings} 键值表（Key 加密存储），改配无需重启即生效。录音为「按住说话」
 * 语义：{@link #startRecording} 起录、{@link #stopRecordingAndTranscribe} 停录并转写，
 * 同一时刻至多一段在录。转写端点兼容 OpenAI / Groq / 各类 whisper 服务端。</p>
 *
 * @author gogym
 */
public class VoiceInputService {

    private static final Logger log = LoggerFactory.getLogger(VoiceInputService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String KEY_BASEURL = "voice.transcription.baseUrl";
    private static final String KEY_MODEL = "voice.transcription.model";
    private static final String KEY_APIKEY = "voice.transcription.apiKey";

    /** 采样率/位深/声道：whisper 系服务端通用输入档。 */
    private static final float SAMPLE_RATE = 16000f;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final VoiceSettingsStore settings;

    /** 在录会话；null 表示空闲。 */
    private volatile Recording recording;

    public VoiceInputService(GimDatabase db, SecretCipher cipher) {
        this.settings = new VoiceSettingsStore(db, cipher);
    }

    /** 在录会话状态（仅供内部与测试窥视）。 */
    boolean isRecording() {
        return recording != null;
    }

    // ===================== 转写配置（settings 持久化，Key 密文） =====================

    /** 转写服务协议根（OpenAI 兼容；未配置返回空串）。 */
    public String transcriptionBaseUrl() {
        return settings.get(KEY_BASEURL);
    }

    public void saveTranscriptionBaseUrl(String url) {
        settings.put(KEY_BASEURL, url, "语音转写服务地址");
    }

    /** 转写模型编码（如 whisper-1）。 */
    public String transcriptionModel() {
        return settings.get(KEY_MODEL);
    }

    public void saveTranscriptionModel(String model) {
        settings.put(KEY_MODEL, model, "语音转写模型");
    }

    /** 明文 API Key（密文存储，取出即解密）；未配置或解密失败返回空串。 */
    public String apiKeyPlain() {
        return settings.apiKeyPlain(KEY_APIKEY);
    }

    public void saveApiKey(String plain) {
        settings.saveApiKey(KEY_APIKEY, plain, "语音转写 API Key（密文）");
    }

    /** 是否已配置可用的转写服务（地址 + 模型齐备；Key 视服务端可匿名）。 */
    public boolean isConfigured() {
        return !transcriptionBaseUrl().isEmpty() && !transcriptionModel().isEmpty();
    }

    // ===================== 录音 =====================

    /** 起录：打开 16kHz/16bit/单声道麦克风，后台线程持续写临时 PCM 文件。 */
    public void startRecording() {
        if (recording != null) {
            return;
        }
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new IllegalStateException("未检测到可用麦克风");
        }
        try {
            TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
            Path pcm = Files.createTempFile("jwork-voice-", ".pcm");
            AtomicBoolean stop = new AtomicBoolean(false);
            Thread writer = new Thread(() -> {
                try (var out = Files.newOutputStream(pcm)) {
                    byte[] buf = new byte[4096];
                    while (!stop.get()) {
                        int n = line.read(buf, 0, buf.length);
                        if (n > 0) {
                            out.write(buf, 0, n);
                        }
                    }
                } catch (Exception e) {
                    log.warn("录音写入中断", e);
                }
            }, "voice-record");
            writer.setDaemon(true);
            writer.start();
            recording = new Recording(line, pcm, stop, writer);
            log.info("开始录音: {}", pcm);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("打开麦克风失败：" + e.getMessage(), e);
        }
    }

    /**
     * 停录并转写：封 WAV、multipart 上传到转写端点，返回识别文本。
     *
     * @return 识别文本；录音过短（&lt;0.2s）返回空串
     * @throws IllegalStateException 未在录音 / 服务返回非 2xx / 麦克风异常
     */
    public String stopRecordingAndTranscribe() {
        Recording r = recording;
        if (r == null) {
            throw new IllegalStateException("当前没有正在进行的录音");
        }
        recording = null;
        r.stop.set(true);
        r.line.stop();
        r.line.close();
        try {
            r.writer.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            byte[] pcm = Files.readAllBytes(r.pcm);
            Files.deleteIfExists(r.pcm);
            if (pcm.length < (long) (SAMPLE_RATE * 2 * 0.2)) {
                return "";
            }
            return transcribe(withWavHeader(pcm));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("转写失败：" + e.getMessage(), e);
        }
    }

    // ===================== WAV 封装与转写 =====================

    /** 44 字节标准 WAV 头 + PCM 数据（单声道 16bit）；包私有便于单测断言结构。 */
    static byte[] withWavHeader(byte[] pcm) {
        int total = 44 + pcm.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        out.writeBytes(new byte[]{'R', 'I', 'F', 'F'});
        writeLeInt(out, total - 8);
        out.writeBytes(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        writeLeInt(out, 16);
        writeLeShort(out, 1);                       // PCM
        writeLeShort(out, 1);                       // mono
        writeLeInt(out, (int) SAMPLE_RATE);
        writeLeInt(out, (int) SAMPLE_RATE * 2);     // byte rate
        writeLeShort(out, 2);                       // block align
        writeLeShort(out, 16);                      // bits per sample
        out.writeBytes(new byte[]{'d', 'a', 't', 'a'});
        writeLeInt(out, pcm.length);
        out.writeBytes(pcm);
        return out.toByteArray();
    }

    private static void writeLeInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeLeShort(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    /** multipart/form-data 上传 WAV，解析响应 {@code text} 字段；包私有便于单测。 */
    String transcribe(byte[] wav) throws Exception {
        String base = transcriptionBaseUrl();
        String model = transcriptionModel();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String boundary = "----jwork" + System.nanoTime();
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(wav);
        body.write(("\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n"
                + "--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/audio/transcriptions"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        String key = apiKeyPlain();
        if (!key.isEmpty()) {
            builder.header("Authorization", "Bearer " + key);
        }
        HttpResponse<String> resp = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("转写服务返回 HTTP " + resp.statusCode());
        }
        JsonNode root = MAPPER.readTree(resp.body());
        String text = root.path("text").asText("").trim();
        log.info("语音转写完成: {} 字符", text.length());
        return text;
    }

    /** 一次在录会话的句柄。 */
    private record Recording(TargetDataLine line, Path pcm, AtomicBoolean stop, Thread writer) {
    }
}
