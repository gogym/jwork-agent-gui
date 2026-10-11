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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SQLite 快照文件头校验：判断给定文件前 16 字节是否为标准 SQLite 数据库魔数。
 * <p>云端同步下载校验（{@code WebDavSyncService}）与启动时待恢复快照校验
 * （{@link RestoreApplier}）共用同一口径：读取失败或长度不足一律视为无效快照，
 * 避免把损坏或错误内容替换进现网库。</p>
 *
 * @author gogym
 */
public final class SqliteSnapshotValidator {

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
}
