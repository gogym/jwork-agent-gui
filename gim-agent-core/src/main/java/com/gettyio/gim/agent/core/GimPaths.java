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

package com.gettyio.gim.agent.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地数据目录约定（本地优先，数据自持）：
 * <pre>
 * ~/.jwork/
 *   jwork.db
 *   secret.key
 *   security/auth.json
 *   workspace/
 *   logs/
 *   backups/
 * </pre>
 * 根目录可通过构造参数覆盖（测试/自定义）。
 *
 * @author gogym
 */
public class GimPaths {

    public static final String DEFAULT_ROOT_NAME = ".jwork";

    private final Path root;

    public GimPaths() {
        this(Paths.get(System.getProperty("user.home"), DEFAULT_ROOT_NAME));
    }

    public GimPaths(Path root) {
        this.root = root.toAbsolutePath();
    }

    public Path root() {
        return root;
    }

    public Path dbFile() {
        return root.resolve("jwork.db");
    }

    public Path secretKeyFile() {
        return root.resolve("secret.key");
    }

    /** 认证目录：登录凭证与会话状态（auth.json）存放处 */
    public Path securityDir() {
        return root.resolve("security");
    }

    /**
     * 登录凭证文件：账号 + 加盐哈希 + 会话状态。明文存放不依赖 DPAPI（重装系统/换 Windows 账户后仍可验证）；
     * 编辑 reset 标志或删除本文件即可重置登录，不影响数据库中的任何数据。
     */
    public Path authFile() {
        return securityDir().resolve("auth.json");
    }

    public Path workspaceDir() {
        return root.resolve("workspace");
    }

    public Path logsDir() {
        return root.resolve("logs");
    }

    /** 数据库备份目录：VACUUM INTO 快照按时间命名存放，滚动保留最近若干份 */
    public Path backupsDir() {
        return root.resolve("backups");
    }

    /** 云端恢复暂存目录：下载校验后的待应用快照落此，重启时由恢复器替换当前库 */
    public Path restoreDir() {
        return root.resolve("restore");
    }

    /** 待应用的云端恢复快照：存在即表示下次启动时需先执行库替换 */
    public Path pendingRestoreFile() {
        return restoreDir().resolve("pending.db");
    }

    /**
     * 待应用快照的来源信息（远端文件名 + 下载时间，制表符分隔一行）。
     * <p>设置页据此告诉用户「下次启动会换成哪一份快照」并提供撤销入口；应用或撤销时与
     * {@link #pendingRestoreFile()} 一同删除，两者是同一件事的两半。</p>
     */
    public Path pendingRestoreInfoFile() {
        return restoreDir().resolve("pending.info");
    }

    public Path skillsDir() {
        return workspaceDir().resolve("skills");
    }

    public Path knowledgeDir() {
        return workspaceDir().resolve("knowledge");
    }

    /** KNOWLEDGE.md：知识树目录摘要，Harness 每轮注入其内容与 knowledge/ 文件清单。 */
    public Path knowledgeMd() {
        return knowledgeDir().resolve("KNOWLEDGE.md");
    }

    /** memory/ 目录：每日记忆台账（memory/*.md），与精炼的 MEMORY.md 配套。 */
    public Path memoryDir() {
        return workspaceDir().resolve("memory");
    }

    /** 图片附件落盘根目录：按 {@code <sessionId>/<uuid>.<ext>} 归档，随会话删除而清理 */
    public Path attachmentsDir() {
        return workspaceDir().resolve("attachments");
    }

    /** AI 生成图片等产物目录：{@code generated/}，供 generate_image 工具与聊天页生图入口落盘引用。 */
    public Path generatedDir() {
        return workspaceDir().resolve("generated");
    }

    /**
     * 交付产物根：{@code generated/sessions/}——智能体经 {@code deliver_artifact} 交出的最终文件
     * 按会话收纳在其下的 {@code <sessionId>/} 子目录（口径由
     * {@code SessionArtifactDelivery.dirOf} 给出，UI 产物卡同此反查）。
     */
    public Path deliveriesDir() {
        return generatedDir().resolve("sessions");
    }

    public Path agentsMd() {
        return workspaceDir().resolve("AGENTS.md");
    }

    public Path memoryMd() {
        return workspaceDir().resolve("MEMORY.md");
    }
}
