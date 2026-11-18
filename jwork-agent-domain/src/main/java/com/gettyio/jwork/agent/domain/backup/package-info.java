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

/**
 * 数据库备份与恢复：本地快照生成、云端恢复的应用执行与快照文件校验。
 * <p>{@link DbBackupService} 以 VACUUM INTO 生成一致性快照并做滚动清理；
 * {@link RestoreApplier} 在应用引导早期把「待恢复快照」先备份旧库再原子替换进当前库；
 * {@link SqliteSnapshotValidator} 校验快照文件头魔数，供启动恢复与云端同步下载检查共用。</p>
 */
package com.gettyio.jwork.agent.domain.backup;
