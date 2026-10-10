package com.gettyio.gim.agent.domain.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 云端恢复的启动应用器：把「下载校验后的待恢复快照」（pending 文件）在应用引导早期
 * 替换为当前数据库，并先把旧库做一份 VACUUM INTO 快照存入备份目录，保证可回退。
 * <p>必须在任何 SQLite 连接打开前调用（SQLite 文件未被占用时才能安全替换）；
 * 替换采用 临时文件 + 原子移动，失败时原库保持不变。</p>
 */
public final class RestoreApplier {

    private static final Logger log = LoggerFactory.getLogger(RestoreApplier.class);

    /** SQLite 文件头魔数（前 16 字节） */
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII);
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private RestoreApplier() {
    }

    /**
     * 若待恢复快照存在则应用：头校验 → 旧库 VACUUM 备份 → 原子替换 → 清理 pending。
     *
     * @param dbFile      当前数据库文件路径
     * @param pendingFile 待恢复快照路径（不存在表示无需恢复）
     * @param backupsDir  备份目录（恢复前的旧库快照存这里）
     * @return 是否执行了替换
     */
    public static boolean applyIfPresent(Path dbFile, Path pendingFile, Path backupsDir) {
        if (pendingFile == null || !Files.isRegularFile(pendingFile)) {
            return false;
        }
        try {
            if (!hasSqliteHeader(pendingFile)) {
                log.warn("待恢复快照头校验失败，放弃应用: {}", pendingFile);
                return false;
            }
            backupCurrent(dbFile, backupsDir);
            replace(dbFile, pendingFile);
            Files.deleteIfExists(pendingFile);
            log.info("已应用云端恢复快照: {}", dbFile);
            return true;
        } catch (Exception e) {
            log.error("应用云端恢复快照失败（原库保持不变）: {}", e.getMessage(), e);
            return false;
        }
    }

    /** 旧库一致性备份：VACUUM INTO 到备份目录（与 {@link DbBackupService} 同命名，纳入滚动清理）。 */
    private static void backupCurrent(Path dbFile, Path backupsDir) throws SQLException, IOException {
        if (dbFile == null || !Files.isRegularFile(dbFile)) {
            return;
        }
        Files.createDirectories(backupsDir);
        Path target = backupsDir.resolve("jwork-" + LocalDateTime.now().format(FILE_TS) + ".db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
        }
        log.info("恢复前已备份当前数据库: {}", target);
    }

    /** 原子替换库文件，并清理旧库残留的 WAL/SHM 边车文件（与新库不配套）。 */
    private static void replace(Path dbFile, Path pendingFile) throws IOException {
        Path tmp = dbFile.resolveSibling(dbFile.getFileName() + ".restoring");
        Files.copy(pendingFile, tmp, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(tmp, dbFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dbFile, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-wal"));
        Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-shm"));
    }

    /** 前 16 字节是否为 SQLite 文件头。 */
    private static boolean hasSqliteHeader(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] header = in.readNBytes(SQLITE_MAGIC.length);
            if (header.length != SQLITE_MAGIC.length) {
                return false;
            }
            for (int i = 0; i < SQLITE_MAGIC.length; i++) {
                if (header[i] != SQLITE_MAGIC[i]) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
