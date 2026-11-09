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

package com.gettyio.gim.agent.domain.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
 *
 * @author gogym
 */
public final class RestoreApplier {

    private static final Logger log = LoggerFactory.getLogger(RestoreApplier.class);

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private RestoreApplier() {
    }

    /**
     * 若待恢复快照存在则应用：头校验 → 完整性校验 → 旧库 VACUUM 备份 → 原子替换 → 清理 pending。
     * <p>两道校验都只读不写原文件；校验失败时 pending 保留，交设置页的「待恢复」行呈现与撤销，
     * 而不是悄悄吞掉用户明确排队过的一次恢复。</p>
     *
     * @param dbFile          当前数据库文件路径
     * @param pendingFile     待恢复快照路径（不存在表示无需恢复）
     * @param pendingInfoFile 待恢复快照的来源信息（可为空，随 pending 一同清理）
     * @param backupsDir      备份目录（恢复前的旧库快照存这里）
     * @return 是否执行了替换
     */
    public static boolean applyIfPresent(Path dbFile, Path pendingFile, Path pendingInfoFile, Path backupsDir) {
        if (pendingFile == null || !Files.isRegularFile(pendingFile)) {
            return false;
        }
        try {
            if (!SqliteSnapshotValidator.hasValidHeader(pendingFile)) {
                log.warn("待恢复快照头校验失败，放弃应用: {}", pendingFile);
                return false;
            }
            if (!SqliteSnapshotValidator.passesIntegrityCheck(pendingFile)) {
                log.error("待恢复快照未通过完整性校验，放弃应用（可在设置页的「待恢复」行撤销）: {}", pendingFile);
                return false;
            }
            backupCurrent(dbFile, backupsDir);
            replace(dbFile, pendingFile);
            Files.deleteIfExists(pendingFile);
            deleteQuietly(pendingInfoFile);
            log.info("已应用云端恢复快照: {}", dbFile);
            return true;
        } catch (Exception e) {
            log.error("应用云端恢复快照失败（原库保持不变）: {}", e.getMessage(), e);
            return false;
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("清理待恢复信息失败: {}", file, e);
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
}
