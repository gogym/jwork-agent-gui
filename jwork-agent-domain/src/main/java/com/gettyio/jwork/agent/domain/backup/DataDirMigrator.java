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

import com.gettyio.jwork.agent.domain.JworkDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

/**
 * 数据根目录迁移器：设置页「更改数据目录」的存储层实现。
 * <p>两阶段提交，全程不改旧目录，天然可回退：</p>
 * <ol>
 *   <li>{@link #stage}：递归拷贝旧根全部数据（数据库除外）→ {@code VACUUM INTO} 生成一致性库快照
 *       → 完整性校验 → 在新根写入旧目录标记 {@value #MARKER_NAME}；</li>
 *   <li>{@link #commit}：把新根写入注册表（与安装向导同一键，升级安装时向导亦读新值），重启后
 *       {@code JworkPaths.defaultRoot()} 即解析到新根。</li>
 * </ol>
 * <p>拷贝排除：{@code jwork.db} 及其 WAL/SHM（由快照取代）、旧根的迁移标记。
 * {@code cef/} 必须随迁：它是 JCEF 原生库安装目录（并非缓存），缺失会触发 jcefmaven
 * 在 FX 主线程联网下载数百 MB 安装包，表现为迁移后重启主窗体长时间不出现。</p>
 *
 * @author gogym
 */
public class DataDirMigrator {

    private static final Logger log = LoggerFactory.getLogger(DataDirMigrator.class);

    /** 新根下的旧目录标记：首行旧根绝对路径，次行迁移时间（ISO-8601）；设置页据此提供清理入口 */
    public static final String MARKER_NAME = "previous-root.info";

    /** 注册表位置与安装向导写入位置对齐（HKCU\Software\JavaSoft\Prefs\com\gettyio\jwork\dataDir） */
    private static final String PREFS_NODE = "com/gettyio/jwork";
    private static final String PREFS_KEY = "dataDir";

    private static final Set<String> EXCLUDED_ROOT_FILES = Set.of("jwork.db", "jwork.db-wal", "jwork.db-shm", MARKER_NAME);

    /** 旧目录标记内容：迁移前的根目录与迁移时刻 */
    public record PreviousRoot(Path dir, Instant at) {
    }

    /** 迁移进度回调（拷贝文件计数，不含数据库快照）；实现方负责线程切换 */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long done, long total);
    }

    private final JworkDatabase db;
    private final Path root;

    public DataDirMigrator(JworkDatabase db, Path root) {
        this.db = db;
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * 目标目录合法性校验：通过返回 null，否则返回错误码
     * （{@code sameRoot}/{@code insideRoot}/{@code ancestorOfRoot}/{@code notWritable}，UI 按码取文案）。
     * 嵌套双向都禁：目标在旧根内会递归自拷，目标包着旧根会把旧根混进新根。
     */
    public String checkTarget(Path target) {
        Path t = target.toAbsolutePath().normalize();
        if (t.equals(root)) {
            return "sameRoot";
        }
        if (t.startsWith(root)) {
            return "insideRoot";
        }
        if (root.startsWith(t)) {
            return "ancestorOfRoot";
        }
        try {
            Files.createDirectories(t);
            Path probe = Files.createTempFile(t, "jwork-probe-", ".tmp");
            Files.deleteIfExists(probe);
        } catch (IOException e) {
            return "notWritable";
        }
        return null;
    }

    /** 目录不存在或无内容视为空（非空目标需 UI 另行确认覆盖） */
    public static boolean isEmptyDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return true;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 迁移暂存：拷贝 + 数据库快照 + 校验 + 旧目录标记；不写注册表（{@link #commit} 独立调用，单测只跑暂存）。
     * 任一环节失败即抛异常，注册表未动、旧目录未改，用户可换目标重试（重试覆盖上次残留）。
     *
     * @param cancelled 取消信号（UI 取消按钮置位）；命中即抛 {@link CancellationException}
     */
    public void stage(Path target, ProgressListener progress, BooleanSupplier cancelled) throws IOException {
        Path t = target.toAbsolutePath().normalize();
        String err = checkTarget(t);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        List<Path> files = collectFiles();
        long total = files.size();
        long done = 0;
        for (Path src : files) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("migration cancelled");
            }
            Path dst = t.resolve(root.relativize(src).toString());
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            done++;
            progress.onProgress(done, total);
        }
        // 上次失败残留的快照先删：VACUUM INTO 不允许目标已存在
        Files.deleteIfExists(t.resolve("jwork.db"));
        vacuumInto(t.resolve("jwork.db"));
        verifySnapshot(t.resolve("jwork.db"));
        Files.writeString(t.resolve(MARKER_NAME), root + "\n" + Instant.now());
        log.info("数据目录迁移暂存完成: {} -> {}（{} 个文件 + 数据库快照）", root, t, total);
    }

    /** 提交：新根写注册表，重启后生效。与安装向导同键，两侧始终一致 */
    public static void commit(Path target) throws IOException {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.put(PREFS_KEY, target.toAbsolutePath().normalize().toString());
            prefs.flush();
        } catch (BackingStoreException e) {
            throw new IOException("写入数据目录注册表失败", e);
        }
    }

    /** 注册表当前配置的数据根（未配置/不可读返回空）；UI 据此识别「已提交待重启」状态 */
    public static Optional<Path> registryRoot() {
        try {
            String v = Preferences.userRoot().node(PREFS_NODE).get(PREFS_KEY, null);
            if (v == null || v.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Path.of(v.trim()).toAbsolutePath().normalize());
        } catch (SecurityException e) {
            return Optional.empty();
        }
    }

    /** 读旧目录标记（内容非法视为无标记） */
    public static Optional<PreviousRoot> readPrevious(Path root) {
        Path marker = root.resolve(MARKER_NAME);
        if (!Files.isRegularFile(marker)) {
            return Optional.empty();
        }
        try {
            List<String> lines = Files.readAllLines(marker);
            if (lines.size() < 2 || lines.get(0).isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new PreviousRoot(Path.of(lines.get(0).trim()), Instant.parse(lines.get(1).trim())));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 清除旧目录标记（清理旧目录成功后调用） */
    public static void clearPrevious(Path root) throws IOException {
        Files.deleteIfExists(root.resolve(MARKER_NAME));
    }

    /** 递归删除目录树（清理旧目录用；安全性由调用方确认对话框保障） */
    public static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 旧根下待拷贝文件清单：排除数据库三件套与旧标记（cef 原生库随迁），排序保证进度稳定 */
    private List<Path> collectFiles() throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            List<Path> files = new ArrayList<>(s.filter(Files::isRegularFile)
                    .filter(p -> {
                        Path rel = root.relativize(p);
                        return !(rel.getNameCount() == 1 && EXCLUDED_ROOT_FILES.contains(rel.toString()));
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .toList());
            return files;
        }
    }

    /** 与 DbBackupService 同款 VACUUM INTO：在线库的一致性快照，不拷半写状态的 db 文件 */
    private void vacuumInto(Path target) throws IOException {
        try (Connection conn = db.getRunner().getConnectionProvider().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
        } catch (java.sql.SQLException e) {
            throw new IOException("数据库快照生成失败: " + target, e);
        }
    }

    /** 快照校验：integrity_check 须 ok 且表数量与在线库一致，挡住坏快照进入提交阶段 */
    private void verifySnapshot(Path snapshot) throws IOException {
        try (Connection live = db.getRunner().getConnectionProvider().getConnection();
             Connection copy = DriverManager.getConnection("jdbc:sqlite:" + snapshot);
             Statement ls = live.createStatement();
             Statement cs = copy.createStatement()) {
            try (ResultSet rs = cs.executeQuery("PRAGMA integrity_check")) {
                if (!rs.next() || !"ok".equals(rs.getString(1))) {
                    throw new IOException("迁移快照完整性校验失败: " + snapshot);
                }
            }
            if (tableCount(ls) != tableCount(cs)) {
                throw new IOException("迁移快照表数量与在线库不一致: " + snapshot);
            }
        } catch (java.sql.SQLException e) {
            throw new IOException("迁移快照校验失败: " + snapshot, e);
        }
    }

    private static int tableCount(Statement stmt) throws java.sql.SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}