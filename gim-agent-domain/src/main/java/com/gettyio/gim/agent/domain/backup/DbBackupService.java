package com.gettyio.gim.agent.domain.backup;

import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.persist.PersistException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 数据库备份：用 SQLite 的 {@code VACUUM INTO} 生成一致性的在线快照
 * （WAL 模式下直接复制文件可能丢未合并页，故不采用文件拷贝）。
 * <p>备份文件命名 {@code jwork-yyyyMMdd-HHmmss.db}，滚动保留最近 {@value #KEEP} 份；
 * 上次备份时间记录在 settings 表（键 {@value #LAST_BACKUP_KEY}），启动时超 24h 自动补一次。</p>
 */
public class DbBackupService {

    private static final Logger log = LoggerFactory.getLogger(DbBackupService.class);

    /** 滚动保留份数 */
    public static final int KEEP = 7;
    /** 上次备份时间的设置键 */
    public static final String LAST_BACKUP_KEY = "db.last_backup_at";
    /** 自动备份的最小间隔 */
    public static final Duration MIN_INTERVAL = Duration.ofHours(24);

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String PREFIX = "jwork-";
    private static final String SUFFIX = ".db";

    private final GimDatabase db;
    private final Path backupsDir;

    public DbBackupService(GimDatabase db, Path backupsDir) {
        this.db = db;
        this.backupsDir = backupsDir.toAbsolutePath();
    }

    /**
     * 立即备份一份。
     *
     * @return 生成的备份文件；当前库无表时（内存库/未初始化）跳过并返回空
     */
    public synchronized Optional<Path> backupNow() {
        if (!hasTables()) {
            log.info("当前数据库为空（无表），跳过备份");
            return Optional.empty();
        }
        ensureDir();
        Path target = backupsDir.resolve(PREFIX + LocalDateTime.now().format(FILE_TS) + SUFFIX);
        try (var conn = db.getRunner().getConnectionProvider().getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
        } catch (java.sql.SQLException e) {
            throw new PersistException("数据库备份失败: " + target, e);
        }
        purgeOldBackups();
        db.settingDao().upsert(LAST_BACKUP_KEY, target.getFileName().toString() + "@"
                + Instant.now().toString(), "最近一次数据库备份");
        log.info("数据库已备份: {}", target);
        return Optional.of(target);
    }

    /** 距上次备份超过 {@link #MIN_INTERVAL} 则执行一次（首次无记录也执行）。 */
    public Optional<Path> backupIfDue() {
        Optional<Instant> last = lastBackupAt();
        if (last.isPresent() && Duration.between(last.get(), Instant.now()).compareTo(MIN_INTERVAL) < 0) {
            return Optional.empty();
        }
        return backupNow();
    }

    /** backups 目录下现有备份，按时间戳降序（最新在前）。 */
    public List<Path> listBackups() {
        if (!Files.isDirectory(backupsDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(backupsDir)) {
            return s.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(PREFIX) && n.endsWith(SUFFIX);
                    })
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("列出备份失败", e);
        }
    }

    /** 最近一次备份时间：解析设置值里的 ISO instant（格式 {@code 文件名@instant}）。 */
    public Optional<Instant> lastBackupAt() {
        return db.settingDao().getValue(LAST_BACKUP_KEY).flatMap(raw -> {
            int at = raw.lastIndexOf('@');
            if (at < 0) {
                return Optional.empty();
            }
            try {
                return Optional.of(Instant.parse(raw.substring(at + 1)));
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    /** 超出保留份数的旧备份按文件名序删除（时间戳命名保证字典序即时间序）。 */
    private void purgeOldBackups() {
        List<Path> backups = listBackups();
        for (Path stale : backups.subList(Math.min(KEEP, backups.size()), backups.size())) {
            try {
                Files.deleteIfExists(stale);
                log.info("删除过期备份: {}", stale);
            } catch (IOException e) {
                log.warn("删除过期备份失败: {}", stale, e);
            }
        }
    }

    private boolean hasTables() {
        Integer count = db.getRunner().queryOne(
                "SELECT COUNT(*) AS n FROM sqlite_master WHERE type='table'",
                rs -> rs.getInt("n")).orElse(0);
        return count != null && count > 0;
    }

    private void ensureDir() {
        try {
            Files.createDirectories(backupsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建备份目录: " + backupsDir, e);
        }
    }
}
