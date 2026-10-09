package com.gettyio.gim.agent.tools.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageGenerationClient} 单测：端点拼接、两种响应形态（b64_json / url）的字段提取、
 * 落盘命名，以及未配置时的守卫路径（在发起 HTTP 前即返回，不触网）。
 */
class ImageGenerationClientTest {

    /** 伪图片字节：仅验证搬运过程不丢不改，不跑图片解码。 */
    private static final byte[] BYTES = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    @Test
    @DisplayName("endpoint：去掉尾斜杠后追加 /images/generations")
    void endpointJoinsBase() {
        assertEquals("https://api.openai.com/v1/images/generations",
                ImageGenerationClient.endpoint("https://api.openai.com/v1"));
        assertEquals("https://api.openai.com/v1/images/generations",
                ImageGenerationClient.endpoint("https://api.openai.com/v1/"));
        assertEquals("https://api.openai.com/v1/images/generations",
                ImageGenerationClient.endpoint("  https://api.openai.com/v1//  "));
    }

    @Test
    @DisplayName("b64Of/urlOf：提取 data[0] 对应字段；缺失、空数组或非法 JSON 返回 null")
    void extractsFirstImageFields() {
        String both = "{\"data\":[{\"b64_json\":\"AA==\",\"url\":\"https://cdn/a.png\"}]}";
        assertEquals("AA==", ImageGenerationClient.b64Of(both));
        assertEquals("https://cdn/a.png", ImageGenerationClient.urlOf(both));
        assertNull(ImageGenerationClient.b64Of("{\"data\":[{\"url\":\"https://cdn/a.png\"}]}"));
        assertNull(ImageGenerationClient.urlOf("{\"data\":[]}"));
        assertNull(ImageGenerationClient.b64Of("not json"));
    }

    @Test
    @DisplayName("save：自动建目录、以 gim- 前缀 png 命名并原样写字节")
    void savesToDir(@TempDir Path dir) throws Exception {
        Path file = ImageGenerationClient.save(dir.resolve("generated"), BYTES);
        assertTrue(Files.isRegularFile(file));
        assertTrue(file.getFileName().toString().startsWith("gim-"));
        assertTrue(file.getFileName().toString().endsWith(".png"));
        assertArrayEquals(BYTES, Files.readAllBytes(file));
    }

    @Test
    @DisplayName("generate：未配置（null / 空地址 / 空模型 / 供给抛异常）抛带指引的 IllegalStateException，不触网")
    void unconfiguredThrowsGuide() {
        ImageGenerationClient none = new ImageGenerationClient(() -> null);
        assertTrue(assertThrows(IllegalStateException.class, () -> none.generate("一只猫"))
                .getMessage().contains("未配置"));

        ImageGenerationClient blankUrl = new ImageGenerationClient(
                () -> new ImageGenerationClient.Config("  ", "dall-e-3", null));
        assertThrows(IllegalStateException.class, () -> blankUrl.generate("一只猫"));

        ImageGenerationClient blankModel = new ImageGenerationClient(
                () -> new ImageGenerationClient.Config("https://api.example.com/v1", "", null));
        assertThrows(IllegalStateException.class, () -> blankModel.generate("一只猫"));

        ImageGenerationClient boom = new ImageGenerationClient(() -> {
            throw new IllegalStateException("解密失败");
        });
        assertThrows(IllegalStateException.class, () -> boom.generate("一只猫"));
    }
}
