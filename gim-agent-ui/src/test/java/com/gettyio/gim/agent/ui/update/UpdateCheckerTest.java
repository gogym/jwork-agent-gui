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

package com.gettyio.gim.agent.ui.update;

import com.gettyio.gim.agent.ui.i18n.I18n;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UpdateChecker} 纯逻辑单测：三段式更新的下载包校验（SHA-256/MD5 按长度判别、
 * 未知长度跳过、校验失败删文件）与版本归一化/比较。不触网、不依赖 Spring。
 *
 * @author gogym
 */
class UpdateCheckerTest {

    @TempDir
    Path tempDir;

    /** 计算文件摘要十六进制串（与生产实现同规格，用于生成期望值）。 */
    private static String computeHex(Path file, String algorithm) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 预置内容为 "jwork-update" 的文件并返回其 SHA-256 十六进制串。 */
    private String writeAndGetSha256() throws Exception {
        Path f = tempDir.resolve("pkg.msi");
        Files.writeString(f, "jwork-update", StandardCharsets.UTF_8);
        return computeHex(f, "SHA-256");
    }

    @Test
    @DisplayName("校验值按长度判别算法：64 位 SHA-256、32 位 MD5，正确值通过")
    void verifyChecksumByLength() throws Exception {
        Path f = tempDir.resolve("pkg.msi");
        Files.writeString(f, "jwork-update", StandardCharsets.UTF_8);

        // SHA-256（64 hex），大小写不敏感
        String sha = computeHex(f, "SHA-256");
        assertEquals(64, sha.length());
        UpdateChecker checker = new UpdateChecker(null, null);
        assertDoesNotThrow(() -> checker.verifyChecksum(f, sha));
        assertDoesNotThrow(() -> checker.verifyChecksum(f, sha.toUpperCase()));

        // MD5（32 hex）：旧服务端兼容
        String md5 = computeHex(f, "MD5");
        assertEquals(32, md5.length());
        assertDoesNotThrow(() -> checker.verifyChecksum(f, md5));
    }

    @Test
    @DisplayName("下载入口先过地址策略：更新接口被劫持时不让本机去取内网地址的字节")
    void downloadRefusesInternalAddress() {
        UpdateChecker checker = new UpdateChecker(null, null);
        UpdateChecker.UpdateInfo viaMetadata = new UpdateChecker.UpdateInfo(
                "9.9.9", "http://169.254.169.254/latest/user-data", -1, "", "desc", false);

        IOException ex = assertThrows(IOException.class, () -> checker.download(viaMetadata, null));
        assertTrue(ex.getMessage().contains("内网"), ex.getMessage());
    }

    @Test
    @DisplayName("校验失败抛 IOException 并删除本地文件；未知长度/空值跳过校验")
    void verifyChecksumFailuresAndSkips() throws Exception {
        UpdateChecker checker = new UpdateChecker(null, null);
        String sha = writeAndGetSha256();

        // 错误 SHA-256（末位换一个字符）：抛异常且文件被删
        Path bad = tempDir.resolve("bad.msi");
        Files.writeString(bad, "jwork-update", StandardCharsets.UTF_8);
        String wrong = sha.substring(0, 63) + (sha.charAt(63) == 'a' ? 'b' : 'a');
        IOException ex = assertThrows(IOException.class, () -> checker.verifyChecksum(bad, wrong));
        assertTrue(ex.getMessage() != null && !ex.getMessage().isBlank(), "异常应携带提示文案");
        assertFalse(Files.exists(bad), "校验失败的包应被删除");

        // 未知长度 / 空 / null：跳过校验不抛
        Path ok = tempDir.resolve("ok.msi");
        Files.writeString(ok, "jwork-update", StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> checker.verifyChecksum(ok, "deadbeef"));
        assertDoesNotThrow(() -> checker.verifyChecksum(ok, ""));
        assertDoesNotThrow(() -> checker.verifyChecksum(ok, null));
        assertTrue(Files.exists(ok), "跳过校验时不应删文件");
    }

    @Test
    @DisplayName("网络异常转友好文案：DNS/超时/拒连接归类，null 消息不落到界面")
    void describeErrorFriendlyMessage() {
        // 包装链中定位根因（外层只是 "send failed"，根因是域名解析失败）
        IOException wrapped = new IOException("send failed", new UnknownHostException("api.example.com"));
        assertEquals(I18n.t("settings.update.error.dns"), UpdateChecker.describeError(wrapped));

        assertEquals(I18n.t("settings.update.error.timeout"), UpdateChecker.describeError(
                new HttpConnectTimeoutException("HTTP connect timed out")));
        assertEquals(I18n.t("settings.update.error.unreachable"), UpdateChecker.describeError(
                new ConnectException("Connection refused")));

        // 消息为 null / 无消息：给通用网络文案而不是字面量 "null"
        String generic = UpdateChecker.describeError(new IOException((String) null));
        assertEquals(I18n.t("settings.update.error.network"), generic);
        assertFalse(generic.contains("null"), "界面不得出现 null");
        assertEquals(I18n.t("settings.update.error.network"), UpdateChecker.describeError(null));

        // 有意义的自定义消息（如未配置地址、校验失败）原样保留
        assertEquals("custom hint", UpdateChecker.describeError(new IOException("custom hint")));
    }

    @Test
    @DisplayName("版本归一化与语义化比较：去 v 前缀/预发布后缀、缺位补 0")
    void versionNormalizeAndCompare() {
        assertEquals("1.2.3", UpdateChecker.normalize(" v1.2.3-SNAPSHOT "));
        assertEquals("0.0.0", UpdateChecker.normalize(null));
        assertEquals("0.0.0", UpdateChecker.normalize(""));

        assertEquals(0, UpdateChecker.compareVersions("1.2.3", "1.2.3"));
        assertEquals(0, UpdateChecker.compareVersions("1.2", "1.2.0"), "缺位补 0");
        assertTrue(UpdateChecker.compareVersions("1.10.0", "1.9.9") > 0, "数值比较而非字典序");
        assertTrue(UpdateChecker.compareVersions("2.0.0", "1.99.99") > 0);
        assertTrue(UpdateChecker.compareVersions("1.2.3", "1.2.4") < 0);
        assertTrue(UpdateChecker.compareVersions("1.2.3", "1.2.3-SNAPSHOT") == 0,
                "比较前各自归一化，预发布后缀不影响");
    }
}
