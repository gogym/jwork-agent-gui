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

package com.gettyio.gim.agent.domain.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalAuthService} 单测：首次设置 / 登录 / 会话过期与续期 / 冷却 / reset 标志 / 损坏兜底 /
 * 锁定与改密。时钟经 {@link MutableClock} 注入，会话时间线完全可控。
 *
 * @author gogym
 */
class LocalAuthServiceTest {

    private static final String USER = "gogy";
    private static final String PASSWORD = "secret-123";

    @TempDir
    Path tempDir;

    private MutableClock clock;
    private Path authFile;
    private LocalAuthService auth;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        authFile = tempDir.resolve("security").resolve("auth.json");
        auth = new LocalAuthService(authFile, clock);
    }

    // ===================== 首次设置 =====================

    @Test
    @DisplayName("首次设置：创建账号并立即开启会话，密码不落明文")
    void setupCreatesAccountAndSession() throws IOException {
        auth.setupAccount(USER, PASSWORD);
        assertEquals(LocalAuthService.Status.AUTHENTICATED, auth.status());
        assertEquals(USER, auth.username());
        assertTrue(Files.exists(authFile));

        String raw = Files.readString(authFile, StandardCharsets.UTF_8);
        assertFalse(raw.contains(PASSWORD), "文件中不得出现密码明文");
    }

    @Test
    @DisplayName("首次设置：账号空白或密码过短被拒")
    void setupValidatesInput() {
        assertThrows(IllegalArgumentException.class, () -> auth.setupAccount("  ", PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> auth.setupAccount(USER, "12345"));
        assertEquals(LocalAuthService.Status.SETUP_REQUIRED, auth.status(), "校验失败不应产生凭证文件");
    }

    // ===================== 登录与冷却 =====================

    @Test
    @DisplayName("登录：正确密码通过并续期，错误密码拒绝")
    void loginVerifiesPassword() {
        auth.setupAccount(USER, PASSWORD);
        auth.lock();
        assertEquals(LocalAuthService.Status.LOGIN_REQUIRED, auth.status());

        assertFalse(auth.verifyAndLogin("wrong-pass"));
        assertTrue(auth.verifyAndLogin(PASSWORD));
        assertEquals(LocalAuthService.Status.AUTHENTICATED, auth.status());
    }

    @Test
    @DisplayName("冷却：第 5 次错误起 30s，冷却期内正确密码也不放行，期满恢复")
    void cooldownAfterFiveFailures() {
        auth.setupAccount(USER, PASSWORD);
        auth.lock();

        for (int i = 0; i < 4; i++) {
            assertFalse(auth.verifyAndLogin("wrong-" + i));
            assertEquals(0, auth.cooldownRemainingSeconds(), "第 " + (i + 1) + " 次失败不应触发冷却");
        }
        assertFalse(auth.verifyAndLogin("wrong-4"));
        assertTrue(auth.cooldownRemainingSeconds() > 0, "第 5 次失败应进入冷却");
        assertFalse(auth.verifyAndLogin(PASSWORD), "冷却期内即使密码正确也不放行");

        clock.advance(Duration.ofSeconds(31));
        assertEquals(0, auth.cooldownRemainingSeconds());
        assertTrue(auth.verifyAndLogin(PASSWORD));
    }

    @Test
    @DisplayName("冷却：逐次翻倍（30s → 60s），封顶 10 分钟")
    void cooldownDoubles() {
        auth.setupAccount(USER, PASSWORD);
        auth.lock();

        for (int i = 0; i < 5; i++) {
            auth.verifyAndLogin("wrong");
        }
        assertEquals(30, auth.cooldownRemainingSeconds());

        clock.advance(Duration.ofSeconds(31));
        auth.verifyAndLogin("wrong");
        assertEquals(60, auth.cooldownRemainingSeconds(), "第 6 次失败冷却应翻倍到 60s");

        // 连续翻倍直至封顶：60 → 120 → 240 → 480 → 600
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(601));
            auth.verifyAndLogin("wrong");
        }
        assertEquals(600, auth.cooldownRemainingSeconds(), "冷却封顶 10 分钟");
    }

    // ===================== 会话过期与续期 =====================

    @Test
    @DisplayName("会话：超过有效期未使用 → 需重新登录，登录后重新计时")
    void sessionExpiresAfterIdle() {
        auth.setupAccount(USER, PASSWORD);
        clock.advance(Duration.ofDays(7).plusSeconds(1));
        assertEquals(LocalAuthService.Status.LOGIN_REQUIRED, auth.status());

        assertTrue(auth.verifyAndLogin(PASSWORD));
        clock.advance(Duration.ofDays(6));
        assertEquals(LocalAuthService.Status.AUTHENTICATED, auth.status(), "登录后应重新计 7 天");
    }

    @Test
    @DisplayName("续期：touch 把到期时间顺延；已过期会话不会被 touch 复活")
    void touchRenewsActiveSessionOnly() {
        auth.setupAccount(USER, PASSWORD);
        clock.advance(Duration.ofDays(3));
        auth.touch();
        clock.advance(Duration.ofDays(5));
        assertEquals(LocalAuthService.Status.AUTHENTICATED, auth.status(), "touch 后有效期应从新活跃点顺延");

        auth.lock();
        auth.touch();
        assertEquals(LocalAuthService.Status.LOGIN_REQUIRED, auth.status(), "被锁定的会话不得被 touch 复活");
    }

    @Test
    @DisplayName("续期节流：1 小时内重复 touch 不重写文件")
    void touchIsThrottled() throws IOException {
        auth.setupAccount(USER, PASSWORD);
        String expiresBefore = readExpiresAt();

        clock.advance(Duration.ofMinutes(10));
        auth.touch();
        assertEquals(expiresBefore, readExpiresAt(), "节流窗口内不应顺延");

        clock.advance(Duration.ofHours(2));
        auth.touch();
        assertFalse(expiresBefore.equals(readExpiresAt()), "超过节流窗口应顺延");
    }

    @Test
    @DisplayName("有效期设置：钳制到 1–90，会话有效时按新天数重算")
    void setSessionDaysClampsAndRecomputes() {
        auth.setupAccount(USER, PASSWORD);
        assertEquals(LocalAuthService.DEFAULT_SESSION_DAYS, auth.sessionDays());

        auth.setSessionDays(30);
        assertEquals(30, auth.sessionDays());
        clock.advance(Duration.ofDays(8));
        assertEquals(LocalAuthService.Status.AUTHENTICATED, auth.status(), "30 天有效期内第 8 天仍免登录");

        auth.setSessionDays(0);
        assertEquals(LocalAuthService.MIN_SESSION_DAYS, auth.sessionDays(), "天数钳制到下限 1");
        auth.setSessionDays(999);
        assertEquals(LocalAuthService.MAX_SESSION_DAYS, auth.sessionDays(), "天数钳制到上限 90");
    }

    // ===================== 重置与损坏兜底 =====================

    @Test
    @DisplayName("reset 标志：保留账号名仅重设密码，旧密码立即失效")
    void resetFlagKeepsUsername() throws IOException {
        auth.setupAccount(USER, PASSWORD);
        setResetFlag(true);
        assertEquals(LocalAuthService.Status.RESET_REQUIRED, auth.status());

        auth.resetPassword("brand-new-1");
        assertEquals(LocalAuthService.Status.AUTHENTICATED, auth.status());
        assertEquals(USER, auth.username(), "重置后账号名保持不变");
        assertFalse(readResetFlag(), "重置完成后标志位应复位");

        auth.lock();
        assertFalse(auth.verifyAndLogin(PASSWORD), "旧密码应失效");
        assertTrue(auth.verifyAndLogin("brand-new-1"));
    }

    @Test
    @DisplayName("损坏兜底：文件无法解析按未设置处理，可重新设置且不会锁死")
    void corruptFileFallsBackToSetup() throws IOException {
        Files.createDirectories(authFile.getParent());
        Files.writeString(authFile, "{ not valid json ...", StandardCharsets.UTF_8);

        assertEquals(LocalAuthService.Status.SETUP_REQUIRED, auth.status());
        auth.setupAccount(USER, PASSWORD);
        assertEquals(LocalAuthService.Status.AUTHENTICATED, auth.status());
    }

    @Test
    @DisplayName("无凭证时重设密码抛 IllegalStateException；删除文件后回到首次设置")
    void resetWithoutAccountFails() throws IOException {
        assertThrows(IllegalStateException.class, () -> auth.resetPassword("whatever-123"));

        auth.setupAccount(USER, PASSWORD);
        Files.delete(authFile);
        assertEquals(LocalAuthService.Status.SETUP_REQUIRED, auth.status(), "删除凭证文件 = 重置登录");
    }

    // ===================== 锁定与改密 =====================

    @Test
    @DisplayName("锁定：清除会话但保留账号，登录后恢复")
    void lockClearsSessionOnly() {
        auth.setupAccount(USER, PASSWORD);
        auth.lock();
        assertEquals(LocalAuthService.Status.LOGIN_REQUIRED, auth.status());
        assertEquals(USER, auth.username(), "锁定不应删除账号");

        assertTrue(auth.verifyAndLogin(PASSWORD));
    }

    @Test
    @DisplayName("改密：旧密码校验失败不放行；通过后清除会话，新密码可用、旧密码失效")
    void changePasswordClearsSession() {
        auth.setupAccount(USER, PASSWORD);
        assertFalse(auth.changePassword("wrong-old", "new-pass-1"));

        assertTrue(auth.changePassword(PASSWORD, "new-pass-1"));
        assertEquals(LocalAuthService.Status.LOGIN_REQUIRED, auth.status(), "改密后会话应被清除");

        assertFalse(auth.verifyAndLogin(PASSWORD), "旧密码应失效");
        assertTrue(auth.verifyAndLogin("new-pass-1"));
    }

    @Test
    @DisplayName("改密：新密码过短被拒")
    void changePasswordValidatesNewPassword() {
        auth.setupAccount(USER, PASSWORD);
        assertThrows(IllegalArgumentException.class, () -> auth.changePassword(PASSWORD, "123"));
    }

    // ===================== 辅助 =====================

    private void setResetFlag(boolean value) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = (ObjectNode) mapper.readTree(Files.readString(authFile, StandardCharsets.UTF_8));
        node.put("reset", value);
        Files.writeString(authFile, mapper.writeValueAsString(node), StandardCharsets.UTF_8);
    }

    private boolean readResetFlag() throws IOException {
        return new ObjectMapper().readTree(Files.readString(authFile, StandardCharsets.UTF_8))
                .path("reset").asBoolean(false);
    }

    private String readExpiresAt() throws IOException {
        return new ObjectMapper().readTree(Files.readString(authFile, StandardCharsets.UTF_8))
                .path("session").path("expiresAt").asText("");
    }

    /** 可控时钟：测试里随意推进会话时间线。 */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
