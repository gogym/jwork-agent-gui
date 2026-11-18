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

package com.gettyio.jwork.agent.domain.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 快照文件头校验：判断给定文件前 16 字节是否为标准 SQLite 数据库魔数。
 * <p>云端同步下载校验（{@code WebDavSyncService}）与启动时待恢复快照校验
 * （{@link RestoreApplier}）共用同一口径：读取失败或长度不足一律视为无效快照，
 * 避免把损坏或错误内容替换进现网库。</p>
 *
 * @author gogym
 */
public final class SqliteSnapshotValidator {

    private static final Logger log = LoggerFactory.getLogger(SqliteSnapshotValidator.class);

    /** SQLite 文件头魔数（前 16 字节）。 */
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII);

    private SqliteSnapshotValidator() {
    }

    /** 前 16 字节是否为 SQLite 文件头；读取失败或长度不足返回 {@code false}。 */
    public static boolean hasValidHeader(Path file) {
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

    /**
     * 结构完整性校验：复制一份副本后跑 {@code PRAGMA integrity_check}，输出为 {@code ok} 才算通过。
     * <p>头校验只证明「像是数据库」，页指针错乱、上传中断造成的尾部截断都能骗过它，而这些库
     * 一旦替换进现网就是打开即报错或静默丢数据。刻意校验副本而非原文件：SQLite 打开 WAL 库会在
     * 同目录落下 {@code -wal}/{@code -shm} 边车，恢复流程只搬主文件，边车里未合并的已提交页就丢了。</p>
     *
     * @return 通过返回 {@code true}；打不开、执行报错或报告任何问题都返回 {@code false}
     */
    public static boolean passesIntegrityCheck(Path file) {
        Path probe = null;
        try {
            probe = Files.createTempFile("jwork-integrity-", ".db");
            Files.copy(file, probe, StandardCopyOption.REPLACE_EXISTING);
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + probe.toAbsolutePath());
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA integrity_check")) {
                return rs.next() && "ok".equalsIgnoreCase(String.valueOf(rs.getString(1)).trim());
            }
        } catch (IOException | SQLException | RuntimeException e) {
            log.warn("快照未通过完整性校验: {} -> {}", file, e.getMessage());
            return false;
        } finally {
            deleteProbe(probe);
        }
    }

    /** 删副本及其 WAL/SHM 边车：临时目录里的残留不该靠下次启动清理。 */
    private static void deleteProbe(Path probe) {
        if (probe == null) {
            return;
        }
        String name = probe.getFileName().toString();
        try {
            Files.deleteIfExists(probe);
            Files.deleteIfExists(probe.resolveSibling(name + "-wal"));
            Files.deleteIfExists(probe.resolveSibling(name + "-shm"));
        } catch (IOException e) {
            log.warn("清理完整性校验副本失败: {}", probe, e);
        }
    }
}
