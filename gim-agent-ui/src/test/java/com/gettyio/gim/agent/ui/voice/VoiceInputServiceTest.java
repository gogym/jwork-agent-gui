package com.gettyio.gim.agent.ui.voice;

import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import com.gettyio.gim.agent.domain.persist.SqlSchemaInitializer;
import com.gettyio.gim.agent.domain.security.AesGcmSecretCipher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VoiceInputService} 单测：转写配置存取（Key 加密往返）、WAV 头封装结构、
 * multipart 转写请求（内嵌 HttpServer 模拟 OpenAI 兼容端点）。录音依赖麦克风，不在单测范围。
 */
class VoiceInputServiceTest {

    @TempDir
    Path tempDir;

    private GimDatabase db;
    private VoiceInputService service;
    private HttpServer server;
    /** 模拟转写端点收到的请求：method + path + headers + body */
    private final List<Captured> captured = new ArrayList<>();

    /** 一次被捕获的 HTTP 请求快照。 */
    private record Captured(String method, String path, String contentType, String auth, byte[] body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(tempDir.resolve("gim.db"));
        new SqlSchemaInitializer(cp).initialize();
        db = new GimDatabase(cp);
        service = new VoiceInputService(db, new AesGcmSecretCipher(tempDir.resolve("secret.key")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void mountTranscription(int status, String responseBody) {
        server.createContext("/v1/audio/transcriptions", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            captured.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
            byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
    }

    @Test
    @DisplayName("配置存取：地址/模型齐备才算已配置，API Key 加密存储且可解密回读")
    void configRoundTrip() {
        assertFalse(service.isConfigured());
        assertTrue(service.transcriptionBaseUrl().isEmpty());

        service.saveTranscriptionBaseUrl(" http://127.0.0.1:9999/v1/ ");
        assertFalse(service.isConfigured(), "只有地址、缺模型时不应视为已配置");

        service.saveTranscriptionModel("whisper-1");
        assertTrue(service.isConfigured());

        service.saveApiKey("sk-voice-secret");
        assertEquals("sk-voice-secret", service.apiKeyPlain());
        assertNotEquals("sk-voice-secret",
                db.settingDao().getValue("voice.transcription.apiKey").orElseThrow(), "Key 应密文入库");

        // 空串清除；URL 首尾空白被裁剪（尾部斜杠由转写时归一化，存储原样保留）
        service.saveApiKey("");
        assertEquals("", service.apiKeyPlain());
        assertEquals("http://127.0.0.1:9999/v1/", service.transcriptionBaseUrl());
    }

    @Test
    @DisplayName("WAV 头封装：44 字节标准头（RIFF/WAVE/PCM/单声道/16k 采样率）+ 原样 PCM")
    void wavHeaderStructure() {
        byte[] pcm = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        byte[] wav = VoiceInputService.withWavHeader(pcm);
        assertEquals(44 + pcm.length, wav.length);

        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        // chunkSize = total - 8，小端
        int chunkSize = (wav[4] & 0xFF) | (wav[5] & 0xFF) << 8 | (wav[6] & 0xFF) << 16 | (wav[7] & 0xFF) << 24;
        assertEquals(44 + pcm.length - 8, chunkSize);
        assertEquals("WAVEfmt ", new String(wav, 8, 8, StandardCharsets.US_ASCII));
        // fmtChunkSize=16, audioFormat=1(PCM), channels=1
        assertEquals(16, wav[16] & 0xFF);
        assertEquals(1, wav[20] & 0xFF);
        assertEquals(1, wav[22] & 0xFF);
        // sampleRate=16000, byteRate=32000, blockAlign=2, bits=16
        int sampleRate = (wav[24] & 0xFF) | (wav[25] & 0xFF) << 8 | (wav[26] & 0xFF) << 16 | (wav[27] & 0xFF) << 24;
        assertEquals(16000, sampleRate);
        assertEquals(16000 * 2, (wav[28] & 0xFF) | (wav[29] & 0xFF) << 8);
        assertEquals(2, wav[32] & 0xFF);
        assertEquals(16, wav[34] & 0xFF);
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII));
        int dataSize = (wav[40] & 0xFF) | (wav[41] & 0xFF) << 8 | (wav[42] & 0xFF) << 16 | (wav[43] & 0xFF) << 24;
        assertEquals(pcm.length, dataSize);
        assertEquals(1, wav[44]);
        assertEquals(8, wav[51]);
    }

    @Test
    @DisplayName("转写请求：multipart 含 file/model 字段与 Bearer 头，解析响应 text 字段")
    void transcribeMultipart() throws Exception {
        mountTranscription(200, "{\"text\": \" 你好，世界 \"}");
        service.saveTranscriptionBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        service.saveTranscriptionModel("whisper-1");
        service.saveApiKey("sk-test");

        String text = service.transcribe(VoiceInputService.withWavHeader(new byte[3200]));
        assertEquals("你好，世界", text);

        assertEquals(1, captured.size());
        Captured c = captured.get(0);
        assertEquals("POST", c.method());
        assertEquals("/v1/audio/transcriptions", c.path());
        assertTrue(c.contentType().startsWith("multipart/form-data; boundary="), "应为 multipart 请求");
        assertEquals("Bearer sk-test", c.auth());
        String body = new String(c.body(), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("name=\"file\"; filename=\"speech.wav\""), "应含 WAV 文件字段");
        assertTrue(body.contains("name=\"model\"\r\n\r\nwhisper-1"), "应含模型字段");
        assertTrue(body.contains("WAVE"), "体首应为 WAV 字节流");
    }

    @Test
    @DisplayName("未配置 Key 时不带 Authorization；非 2xx 抛出含状态码的异常")
    void transcribeWithoutKeyAndError() {
        mountTranscription(401, "{\"error\": \"bad key\"}");
        service.saveTranscriptionBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        service.saveTranscriptionModel("whisper-1");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.transcribe(VoiceInputService.withWavHeader(new byte[3200])));
        assertTrue(ex.getMessage().contains("401"), "异常应含 HTTP 状态码");
        assertEquals(1, captured.size());
        assertTrue(captured.get(0).auth() == null, "未配置 Key 时不应带 Authorization 头");
    }

    @Test
    @DisplayName("未在录音时 stopRecordingAndTranscribe 抛 IllegalStateException")
    void stopWithoutRecordingThrows() {
        assertFalse(service.isRecording());
        assertThrows(IllegalStateException.class, service::stopRecordingAndTranscribe);
    }
}
