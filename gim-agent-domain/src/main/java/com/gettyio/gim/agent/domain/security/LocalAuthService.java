package com.gettyio.gim.agent.domain.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 本地登录认证（软锁）：凭证与会话存 {@code ~/.jwork/security/auth.json}，不进数据库、不加密数据。
 * <p>定位：挡住「别人随手打开你电脑翻应用」的日常场景（类似手机锁屏），不防御能直接访问文件的攻击者；
 * 因此设计上救援优先——忘密码时编辑文件 {@code reset:true}（或删文件/文件损坏走兜底）即可重设，数据无损。</p>
 *
 * <ul>
 *   <li><b>密码存储</b>：PBKDF2WithHmacSHA256 + 16 字节随机盐 + 210k 迭代，单向哈希、恒定时间比较；
 *       加盐哈希明文存文件（不引入 DPAPI——其绑定 Windows 账户，重装系统会造成「密码正确却无法验证」的锁死）。</li>
 *   <li><b>会话</b>：默认 7 天免登录，{@code expiresAt = lastActiveAt + sessionDays}；启动校验通过、
 *       主窗唤回、发送消息等活跃事件经 {@link #touch()} 续期（写盘节流 1 小时）。</li>
 *   <li><b>重置</b>：文件置 {@code reset:true} → {@link Status#RESET_REQUIRED}，保留账号名仅重设密码；
 *       文件缺失或损坏 → {@link Status#SETUP_REQUIRED}（重新设置账号，同样不影响数据）。</li>
 *   <li><b>失败冷却</b>：连续错误达 {@link #FREE_ATTEMPTS} 次起 30s 并逐次翻倍（封顶 10 分钟）；
 *       计数在内存态，重启即失效（软锁定位下不追求跨重启防爆破）。</li>
 * </ul>
 *
 * <p>全部公开方法同步；文件读写量级极小，供 UI 线程直接调用。</p>
 */
public class LocalAuthService {

    /** 登录门状态：决定启动/唤回时进入哪种窗口。 */
    public enum Status {
        /** 无凭证文件或文件损坏无法解析：进入首次设置（数据不受影响）。 */
        SETUP_REQUIRED,
        /** 文件带 {@code reset:true} 标志：保留账号名，仅重设密码。 */
        RESET_REQUIRED,
        /** 凭证有效但会话缺失/已过期：需输入密码登录。 */
        LOGIN_REQUIRED,
        /** 会话有效：可免登录进入。 */
        AUTHENTICATED
    }

    private static final Logger log = LoggerFactory.getLogger(LocalAuthService.class);

    /** 密码最短长度（UI 提示与校验共用）。 */
    public static final int MIN_PASSWORD_LENGTH = 6;
    public static final int DEFAULT_SESSION_DAYS = 7;
    public static final int MIN_SESSION_DAYS = 1;
    public static final int MAX_SESSION_DAYS = 90;

    /** PBKDF2 迭代次数：参数随文件保存，后续可整体升级。 */
    static final int PBKDF2_ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    /** 活跃续期的最小写盘间隔：发消息/唤回等高频事件不至于反复落盘。 */
    private static final Duration RENEW_THROTTLE = Duration.ofHours(1);

    /** 连续错误达该次数起进入冷却。 */
    private static final int FREE_ATTEMPTS = 5;
    private static final long COOLDOWN_BASE_SECONDS = 30;
    private static final long COOLDOWN_MAX_SECONDS = 600;

    private final Path authFile;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    /** 失败计数与冷却截止：内存态，重启即失效。 */
    private int failedAttempts;
    private Instant cooldownUntil;

    public LocalAuthService(Path authFile) {
        this(authFile, Clock.systemUTC());
    }

    /** 测试用：注入可控时钟。 */
    public LocalAuthService(Path authFile, Clock clock) {
        this.authFile = authFile;
        this.clock = clock;
    }

    // ===================== 状态查询 =====================

    /** 当前登录门状态（文件损坏按未设置兜底，永不锁死）。 */
    public synchronized Status status() {
        AuthDoc doc = read();
        if (doc == null) {
            return Status.SETUP_REQUIRED;
        }
        if (doc.reset()) {
            return Status.RESET_REQUIRED;
        }
        return isSessionActive(doc) ? Status.AUTHENTICATED : Status.LOGIN_REQUIRED;
    }

    /** 会话是否有效（免登录进入应用）。 */
    public synchronized boolean isSessionValid() {
        return status() == Status.AUTHENTICATED;
    }

    /** 已设置的账号名；未设置/文件损坏返回 null。 */
    public synchronized String username() {
        AuthDoc doc = read();
        return doc == null ? null : doc.username();
    }

    /** 当前免登录有效期（天）；未设置返回默认值。 */
    public synchronized int sessionDays() {
        AuthDoc doc = read();
        return doc == null ? DEFAULT_SESSION_DAYS : doc.sessionDays();
    }

    /** 凭证文件路径（登录窗「忘记密码」指引需向用户展示）。 */
    public Path authFile() {
        return authFile;
    }

    /** 登录冷却剩余秒数（0=可尝试）。 */
    public synchronized long cooldownRemainingSeconds() {
        if (cooldownUntil == null) {
            return 0;
        }
        long millis = Duration.between(clock.instant(), cooldownUntil).toMillis();
        if (millis <= 0) {
            cooldownUntil = null;
            return 0;
        }
        return (millis + 999) / 1000;
    }

    // ===================== 生命周期操作 =====================

    /**
     * 首次设置：创建账号并立即开启会话。
     *
     * @throws IllegalArgumentException 账号空白或密码过短
     */
    public synchronized void setupAccount(String username, String password) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        requirePassword(password);
        int days = clampDays(sessionDays());
        byte[] salt = newSalt();
        Instant now = clock.instant();
        write(new AuthDoc(name, salt, hash(password, salt, PBKDF2_ITERATIONS), PBKDF2_ITERATIONS,
                false, days, now, now.plus(Duration.ofDays(days))));
        failedAttempts = 0;
        cooldownUntil = null;
    }

    /** 校验密码并在通过后续期会话；失败累计冷却，冷却期内始终返回 false。 */
    public synchronized boolean verifyAndLogin(String password) {
        if (cooldownRemainingSeconds() > 0) {
            return false;
        }
        AuthDoc doc = read();
        if (doc == null || doc.reset()) {
            return false;
        }
        byte[] candidate = hash(password == null ? "" : password, doc.salt(), doc.iterations());
        if (!MessageDigest.isEqual(candidate, doc.passwordHash())) {
            failedAttempts++;
            if (failedAttempts >= FREE_ATTEMPTS) {
                int extra = Math.min(failedAttempts - FREE_ATTEMPTS, 10);
                long seconds = Math.min(COOLDOWN_BASE_SECONDS << extra, COOLDOWN_MAX_SECONDS);
                cooldownUntil = clock.instant().plusSeconds(seconds);
            }
            return false;
        }
        failedAttempts = 0;
        cooldownUntil = null;
        Instant now = clock.instant();
        write(new AuthDoc(doc.username(), doc.salt(), doc.passwordHash(), doc.iterations(), false,
                doc.sessionDays(), now, now.plus(Duration.ofDays(doc.sessionDays()))));
        return true;
    }

    /**
     * 重设密码（reset 标志流程）：保留账号名，写入新密码并立即续期。
     *
     * @throws IllegalArgumentException 密码过短
     * @throws IllegalStateException    无凭证文件（调用方应先按状态分流）
     */
    public synchronized void resetPassword(String newPassword) {
        requirePassword(newPassword);
        AuthDoc doc = read();
        if (doc == null) {
            throw new IllegalStateException("no account to reset: " + authFile);
        }
        byte[] salt = newSalt();
        Instant now = clock.instant();
        write(new AuthDoc(doc.username(), salt, hash(newPassword, salt, PBKDF2_ITERATIONS), PBKDF2_ITERATIONS,
                false, doc.sessionDays(), now, now.plus(Duration.ofDays(doc.sessionDays()))));
        failedAttempts = 0;
        cooldownUntil = null;
    }

    /**
     * 修改密码：旧密码校验通过后写入新密码并清除会话（下次启动/唤回主窗要求重新登录）。
     *
     * @return false = 旧密码不正确；密码过短抛 {@link IllegalArgumentException}
     */
    public synchronized boolean changePassword(String oldPassword, String newPassword) {
        requirePassword(newPassword);
        AuthDoc doc = read();
        if (doc == null) {
            return false;
        }
        byte[] candidate = hash(oldPassword == null ? "" : oldPassword, doc.salt(), doc.iterations());
        if (!MessageDigest.isEqual(candidate, doc.passwordHash())) {
            return false;
        }
        byte[] salt = newSalt();
        write(new AuthDoc(doc.username(), salt, hash(newPassword, salt, PBKDF2_ITERATIONS), PBKDF2_ITERATIONS,
                false, doc.sessionDays(), null, null));
        return true;
    }

    /**
     * 活跃续期：会话有效时把到期时间顺延（写盘节流 {@link #RENEW_THROTTLE}）；
     * 会话缺失/已过期时不做任何事（必须重新登录，被清除的会话不会被活跃事件复活）。
     */
    public synchronized void touch() {
        AuthDoc doc = read();
        if (doc == null || doc.reset() || !isSessionActive(doc)) {
            return;
        }
        Instant now = clock.instant();
        if (doc.lastActiveAt() != null && Duration.between(doc.lastActiveAt(), now).compareTo(RENEW_THROTTLE) < 0) {
            return;
        }
        try {
            write(new AuthDoc(doc.username(), doc.salt(), doc.passwordHash(), doc.iterations(), false,
                    doc.sessionDays(), now, now.plus(Duration.ofDays(doc.sessionDays()))));
        } catch (RuntimeException e) {
            // 续期属非关键路径：写盘失败仅告警，不打断当前操作（会话过期后仍会要求登录）
            log.warn("会话续期写盘失败: {}", e.getMessage());
        }
    }

    /** 立即锁定：清除会话（不删除账号），下次启动或唤回主窗时要求重新登录。 */
    public synchronized void lock() {
        AuthDoc doc = read();
        if (doc == null) {
            return;
        }
        write(new AuthDoc(doc.username(), doc.salt(), doc.passwordHash(), doc.iterations(), false,
                doc.sessionDays(), null, null));
    }

    /** 设置免登录有效期（1–90 天，自动钳制）；会话有效时按新天数重算到期时间。 */
    public synchronized void setSessionDays(int days) {
        AuthDoc doc = read();
        if (doc == null) {
            return;
        }
        int clamped = clampDays(days);
        Instant lastActive = doc.lastActiveAt();
        Instant expires = isSessionActive(doc) && lastActive != null
                ? lastActive.plus(Duration.ofDays(clamped)) : doc.expiresAt();
        write(new AuthDoc(doc.username(), doc.salt(), doc.passwordHash(), doc.iterations(), false,
                clamped, lastActive, expires));
    }

    // ===================== 文件读写 =====================

    /** 凭证文件的内存快照；session 字段可为空（未登录/已锁定）。 */
    private record AuthDoc(String username, byte[] salt, byte[] passwordHash, int iterations,
                           boolean reset, int sessionDays, Instant lastActiveAt, Instant expiresAt) {
    }

    private boolean isSessionActive(AuthDoc doc) {
        return doc.expiresAt() != null && clock.instant().isBefore(doc.expiresAt());
    }

    /** 读凭证文件；缺失或损坏（含用户编辑出错）返回 null，由调用方按「未设置」兜底。 */
    private AuthDoc read() {
        if (!Files.exists(authFile)) {
            return null;
        }
        try {
            JsonNode n = mapper.readTree(Files.readString(authFile, StandardCharsets.UTF_8));
            String username = n.path("username").asText("").trim();
            String saltB64 = n.path("salt").asText("");
            String hashB64 = n.path("passwordHash").asText("");
            if (username.isEmpty() || saltB64.isEmpty() || hashB64.isEmpty()) {
                return null;
            }
            int iterations = n.path("iterations").asInt(PBKDF2_ITERATIONS);
            if (iterations < 1) {
                iterations = PBKDF2_ITERATIONS;
            }
            JsonNode session = n.path("session");
            return new AuthDoc(username,
                    Base64.getDecoder().decode(saltB64),
                    Base64.getDecoder().decode(hashB64),
                    iterations,
                    n.path("reset").asBoolean(false),
                    clampDays(n.path("sessionDays").asInt(DEFAULT_SESSION_DAYS)),
                    parseInstant(session.path("lastActiveAt").asText(null)),
                    parseInstant(session.path("expiresAt").asText(null)));
        } catch (IOException | RuntimeException e) {
            log.warn("auth.json 无法解析，按未设置兜底（数据不受影响）: {}", e.getMessage());
            return null;
        }
    }

    /** 写凭证文件：先写同目录临时文件再原子替换，避免写途崩溃留下半截 JSON。 */
    private void write(AuthDoc doc) {
        ObjectNode n = mapper.createObjectNode();
        n.put("version", 1);
        n.put("username", doc.username());
        n.put("iterations", doc.iterations());
        n.put("salt", Base64.getEncoder().encodeToString(doc.salt()));
        n.put("passwordHash", Base64.getEncoder().encodeToString(doc.passwordHash()));
        n.put("reset", doc.reset());
        n.put("sessionDays", doc.sessionDays());
        if (doc.expiresAt() != null) {
            ObjectNode session = n.putObject("session");
            if (doc.lastActiveAt() != null) {
                session.put("lastActiveAt", doc.lastActiveAt().toString());
            }
            session.put("expiresAt", doc.expiresAt().toString());
        }
        try {
            Files.createDirectories(authFile.getParent());
            Path tmp = authFile.resolveSibling(authFile.getFileName() + ".tmp");
            Files.writeString(tmp,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(n) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            try {
                Files.move(tmp, authFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, authFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法写入认证文件: " + authFile, e);
        }
    }

    private static Instant parseInstant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private byte[] newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return salt;
    }

    /** PBKDF2WithHmacSHA256 单向哈希（JDK 自带，零依赖）。 */
    private static byte[] hash(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 不可用", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static void requirePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    private static int clampDays(int days) {
        return Math.max(MIN_SESSION_DAYS, Math.min(MAX_SESSION_DAYS, days));
    }
}
