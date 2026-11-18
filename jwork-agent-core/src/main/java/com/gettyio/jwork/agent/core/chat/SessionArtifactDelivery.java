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

package com.gettyio.jwork.agent.core.chat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 会话产物交付落点：把 {@code deliver_artifact} 交来的文件落到
 * {@code workspace/generated/sessions/<sessionId>/<文件名>}，即用户能在本机直接打开的位置。
 * <p>为什么要有这一步：智能体的工作区文件（尤其 Docker 沙箱里的）用户看不见也拿不走，
 * 「跑完脚本报了个路径」等于什么都没交付。交付后返回的绝对路径会进工具结果，
 * 模型据此校验文件确实存在，UI 侧按同一路径口径反查渲染产物卡（{@code SessionArtifactDelivery.dirOf}）。</p>
 * <p>同名不覆盖：框架约定 {@code force=false} 时返回 conflict，模型需换名再交一次——
 * 免得把上一轮的报告悄悄盖掉。</p>
 *
 * @author gogym
 */
public final class SessionArtifactDelivery implements ArtifactDeliveryTarget {

    private static final Logger log = LoggerFactory.getLogger(SessionArtifactDelivery.class);

    /** 无会话上下文时的兜底目录名（定时任务、探针等非会话调用）。 */
    static final String NO_SESSION = "_no-session";

    /** 交付名长度上限（字符）：留出目录与序号余量，避免触到 Windows 260 字符路径上限。 */
    private static final int MAX_NAME_CHARS = 120;

    private final Path sessionsRoot;

    public SessionArtifactDelivery(Path sessionsRoot) {
        this.sessionsRoot = sessionsRoot.toAbsolutePath().normalize();
    }

    @Override
    public ArtifactDeliveryResult deliver(RuntimeContext runtimeContext, ArtifactDeliveryRequest request) {
        if (request == null || request.content() == null) {
            return ArtifactDeliveryResult.fail("没有可交付的内容");
        }
        String fileName = safeName(request.fileName());
        if (fileName.isEmpty()) {
            return ArtifactDeliveryResult.fail("交付文件名非法");
        }
        Path dir = dirOf(sessionsRoot, runtimeContext == null ? null : runtimeContext.getSessionId());
        Path target = dir.resolve(fileName);
        boolean exists;
        try {
            exists = Files.isRegularFile(target);
        } catch (SecurityException e) {
            return ArtifactDeliveryResult.fail("无权访问交付目录：" + dir);
        }
        if (exists && !request.force()) {
            return ArtifactDeliveryResult.conflict("交付目录已存在同名文件 " + fileName + "，请换一个新的 fileName 再交付");
        }
        try {
            Files.createDirectories(dir);
            Files.write(target, request.content());
        } catch (IOException | SecurityException e) {
            log.warn("产物交付落盘失败: {}", target, e);
            return ArtifactDeliveryResult.fail("写入失败：" + e.getMessage());
        }
        return ArtifactDeliveryResult.success(target.toString());
    }

    /**
     * 会话交付目录：{@code <sessionsRoot>/<sessionId>}。
     * 与 UI 侧产物卡反查共用本方法，避免两套口径导致卡片指向不存在的文件。
     */
    public static Path dirOf(Path sessionsRoot, String sessionId) {
        String sid = safeSegment(sessionId);
        return sessionsRoot.toAbsolutePath().normalize().resolve(sid.isEmpty() ? NO_SESSION : sid);
    }

    /** 会话 ID 作目录名：只留字母数字与 -_.，其余替换为下划线（防 {@code ..} 与分隔符逃逸）。 */
    private static String safeSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && sb.length() < 64; i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            sb.append(ok ? c : '_');
        }
        String out = sb.toString().replaceAll("^\\.+$", "_");
        return out.isBlank() ? "" : out;
    }

    /**
     * 交付文件名清洗：框架已校验不含路径分隔符，这里再剥控制字符、截断超长名，
     * 并保留原扩展名（大小写按模型给的写法，仅用于判型不参与改写）。
     */
    private static String safeName(String fileName) {
        if (fileName == null) {
            return "";
        }
        String n = fileName.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) {
            n = n.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length() && sb.length() < MAX_NAME_CHARS; i++) {
            char c = n.charAt(i);
            if (c == '\0' || c < 0x20 || "|:*?\"<>".indexOf(c) >= 0) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        while (out.endsWith(".")) {
            out = out.substring(0, out.length() - 1);
        }
        return out.isBlank() || out.equals(".") || out.equals("..") ? "" : out;
    }

    /** 交付根（供装配与设置页展示）。 */
    public Path sessionsRoot() {
        return sessionsRoot;
    }
}
