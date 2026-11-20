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
import com.gettyio.jwork.agent.domain.persist.SqliteConnectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据目录迁移器单测：真实 SQLite 快照 + 临时目录文件树，只跑暂存不碰注册表。
 *
 * @author gogym
 */
class DataDirMigratorTest {

    @TempDir
    Path tmp;

    private Path root;
    private JworkDatabase db;
    private DataDirMigrator migrator;

    @BeforeEach
    void setUp() throws Exception {
        root = tmp.resolve("root");
        Files.createDirectories(root);
        db = new JworkDatabase(new SqliteConnectionProvider(root.resolve("jwork.db")));
        db.initSchema();
        db.settingDao().upsert("probe.key", "probe-value", "迁移单测探针");
        Files.writeString(root.resolve("secret.key"), "key-material");
        Files.writeString(root.resolve("theme.properties"), "lang=ZH_CN");
        Files.createDirectories(root.resolve("workspace/skills"));
        Files.writeString(root.resolve("workspace/a.txt"), "hello");
        Files.writeString(root.resolve("workspace/skills/s.md"), "skill");
        Files.createDirectories(root.resolve("cef"));
        Files.writeString(root.resolve("cef/natives.bin"), "jcef-natives");
        Files.createDirectories(root.resolve("logs"));
        Files.writeString(root.resolve("logs/x.log"), "log-line");
        // 旧根自带的历史标记不应被拷进新根
        Files.writeString(root.resolve(DataDirMigrator.MARKER_NAME), "older\n2020-01-01T00:00:00Z");
        migrator = new DataDirMigrator(db, root);
    }

    @Test
    void stageCopiesDataAndConsistentSnapshot() throws Exception {
        Path target = tmp.resolve("new");
        AtomicLong lastDone = new AtomicLong();
        AtomicLong lastTotal = new AtomicLong();
        migrator.stage(target, (d, t) -> {
            lastDone.set(d);
            lastTotal.set(t);
        }, () -> false);

        // 6 个文件：secret.key/theme/a.txt/s.md/x.log/cef 原生库（数据库三件套、旧标记排除）
        assertEquals(6, lastTotal.get());
        assertEquals(6, lastDone.get());
        assertEquals("hello", Files.readString(target.resolve("workspace/a.txt")));
        assertEquals("skill", Files.readString(target.resolve("workspace/skills/s.md")));
        assertEquals("key-material", Files.readString(target.resolve("secret.key")));
        assertEquals("lang=ZH_CN", Files.readString(target.resolve("theme.properties")));
        assertTrue(Files.exists(target.resolve("logs/x.log")));
        // cef 是 JCEF 原生库安装目录，必须随迁（缺失会触发主线程联网下载冻住启动）
        assertEquals("jcef-natives", Files.readString(target.resolve("cef/natives.bin")));
        assertFalse(Files.exists(target.resolve("jwork.db-wal")));

        // 快照可独立打开且数据齐全
        JworkDatabase copy = new JworkDatabase(new SqliteConnectionProvider(target.resolve("jwork.db")));
        assertEquals("probe-value", copy.settingDao().getValue("probe.key").orElse(null));

        // 新根标记指向旧根（而非旧根自带的历史标记内容）
        DataDirMigrator.PreviousRoot prev = DataDirMigrator.readPrevious(target).orElseThrow();
        assertEquals(root, prev.dir());
    }

    @Test
    void checkTargetRejectsSameNestedAndAncestor() throws Exception {
        assertNull(migrator.checkTarget(tmp.resolve("new")));
        assertEquals("sameRoot", migrator.checkTarget(root));
        assertEquals("insideRoot", migrator.checkTarget(root.resolve("workspace/sub")));
        assertEquals("ancestorOfRoot", migrator.checkTarget(tmp));
        // 目标是普通文件 → 不可写
        Path file = tmp.resolve("plain.txt");
        Files.writeString(file, "x");
        assertEquals("notWritable", migrator.checkTarget(file));
    }

    @Test
    void isEmptyDirTreatsMissingAsEmpty() throws Exception {
        assertTrue(DataDirMigrator.isEmptyDir(tmp.resolve("missing")));
        Path empty = tmp.resolve("empty");
        Files.createDirectories(empty);
        assertTrue(DataDirMigrator.isEmptyDir(empty));
        assertFalse(DataDirMigrator.isEmptyDir(root));
    }

    @Test
    void cancelAbortsBeforeCommit() {
        assertThrows(CancellationException.class,
                () -> migrator.stage(tmp.resolve("new"), (d, t) -> { }, () -> true));
    }

    @Test
    void retryOverwritesPartialTarget() throws Exception {
        Path target = tmp.resolve("new");
        migrator.stage(target, (d, t) -> { }, () -> false);
        // 模拟上次残留：二次暂存须覆盖而非撞 VACUUM INTO 目标已存在
        migrator.stage(target, (d, t) -> { }, () -> false);
        assertEquals("hello", Files.readString(target.resolve("workspace/a.txt")));
    }

    @Test
    void deleteTreeAndClearPrevious() throws Exception {
        Path target = tmp.resolve("new");
        migrator.stage(target, (d, t) -> { }, () -> false);
        assertTrue(DataDirMigrator.readPrevious(target).isPresent());
        DataDirMigrator.deleteTree(target);
        assertFalse(Files.exists(target));
        DataDirMigrator.clearPrevious(root);
        assertTrue(DataDirMigrator.readPrevious(root).isEmpty());
    }
}