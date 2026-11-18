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

package com.gettyio.jwork.agent.core.memory;

import com.gettyio.jwork.agent.core.chat.MultiAgentGateway;
import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.entity.AgentDefinition;
import com.gettyio.jwork.agent.domain.util.FileKinds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 应用级长期记忆管理：读写 Harness 在工作区内维护的记忆文件，供设置页按「作用域」查看/编辑/删除。
 * <p>Harness 默认启用长期记忆：{@code <workspace>/MEMORY.md} 为精炼长期记忆，{@code <workspace>/memory/*.md}
 * 为每日台账（由 MemoryFlush/Consolidate 自动写入）。本服务只做「读现成文件 + 覆盖/删除」，
 * 不复制框架的合并逻辑——智能体运行时仍照常往 MEMORY.md 沉淀，用户在 UI 的编辑即时落到同一文件。</p>
 * <p>记忆以<b>工作区目录</b>为作用域：未设专属工作区的智能体（含默认智能体）共用共享工作区，
 * 因而共享同一份记忆文件，与运行时装配口径完全一致。作用域键取自 {@link #listScopes()} 的枚举结果，
 * 展开的读写只接受已知目录，防止用任意路径访问记忆。</p>
 * <p>记忆以<b>工作区目录</b>为作用域：未设专属工作区的智能体默认各得 {@code agents/<编码>/} 专属目录，
 * 默认智能体（共享工作区）即全局记忆，与运行时装配口径完全一致。作用域键取自 {@link #listScopes()}
 * 的枚举结果，展开的读写只接受已知目录，防止用任意路径访问记忆。</p>
 * <p>记忆文件按用途分两类（均在 {@code memory/} 文件夹下）：<b>分类记忆文件</b>（自由命名，如
 * {@code 用户偏好.md}——由用户/智能体按需创建，可被 memory_search 搜索并随沉淀合并进 MEMORY.md）
 * 与<b>每日台账</b>（{@code YYYY-MM-DD.md}，由框架自动写入）。</p>
 *
 * @author gogym
 */
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private static final String MEMORY_MD = "MEMORY.md";
    private static final String MEMORY_DIR = "memory";

    /** 每日台账文件名（{@code YYYY-MM-DD.md}）：展示与创建校验据此与分类记忆文件分流 */
    private static final java.util.regex.Pattern DAILY_FILE =
            java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\.md");

    /** 分类文件名长度上限（不含 .md）：避免异常长文件名 */
    private static final int NAME_MAX_LEN = 60;

    private final JworkDatabase db;
    private final MultiAgentGateway gateway;

    public MemoryService(JworkDatabase db, MultiAgentGateway gateway) {
        this.db = db;
        this.gateway = gateway;
    }

    /**
     * 一个记忆作用域 = 一份记忆文件落点（某工作区目录）。
     *
     * @param key          作用域键（工作区目录的绝对路径字符串），读写/删除 API 的标识
     * @param dir          工作区目录
     * @param agentNames   共用该作用域的智能体显示名（共享工作区即全部未设专属目录的智能体）
     * @param shared       是否共享工作区（全局记忆）
     * @param fileCount    记忆文件数：MEMORY.md 计 1，其余为 memory/ 顶层 .md 台账数
     * @param lastModified 最近修改时间（毫秒时间戳；无文件时为 0）
     */
    public record MemoryScope(String key, Path dir, List<String> agentNames,
                              boolean shared, int fileCount, long lastModified) {
    }

    /**
     * 一条分类记忆文件条目（{@code memory/} 下的非台账 .md）。
     *
     * @param name         文件名（含 .md）
     * @param lastModified 最近修改时间（毫秒时间戳）
     */
    public record MemoryFile(String name, long lastModified) {
    }

    /**
     * 枚举全部记忆作用域：共享工作区恒为首位，其余按智能体定义逐个解析工作区目录，
     * 落点相同的智能体合并到同一作用域（agentNames 记录全部共用者）。
     */
    public List<MemoryScope> listScopes() {
        Path shared = normalize(gateway.sharedWorkspace());
        String sharedKey = shared.toString();
        Map<String, Path> byKey = new LinkedHashMap<>();
        Map<String, List<String>> namesByKey = new LinkedHashMap<>();
        byKey.put(sharedKey, shared);
        namesByKey.put(sharedKey, new ArrayList<>());
        for (AgentDefinition def : db.agentDao().findAll()) {
            Path dir = normalize(gateway.workspaceForAgent(def.getAgentCode()));
            String key = dir.toString();
            byKey.putIfAbsent(key, dir);
            namesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(displayName(def));
        }
        List<MemoryScope> scopes = new ArrayList<>(byKey.size());
        byKey.forEach((key, dir) ->
                scopes.add(toScope(key, dir, namesByKey.get(key), sharedKey.equals(key))));
        return scopes;
    }

    /** 读取该作用域的长期记忆 {@code MEMORY.md}；不存在返回空串（UI 以占位提示区分「空」与「未生成」）。 */
    public String readLongTerm(String scopeKey) {
        Path dir = scopeDir(scopeKey);
        if (dir == null) {
            return "";
        }
        Path file = dir.resolve(MEMORY_MD);
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            log.warn("读取 MEMORY.md 失败: {}", file, e);
            return "";
        }
    }

    /** 覆盖写入长期记忆 {@code MEMORY.md}（目录不存在则创建）。 */
    public void writeLongTerm(String scopeKey, String content) {
        Path dir = scopeDir(scopeKey);
        if (dir == null) {
            return;
        }
        Path file = dir.resolve(MEMORY_MD);
        try {
            Files.createDirectories(dir);
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
            log.info("已写入 MEMORY.md: {}", file);
        } catch (IOException e) {
            throw new UncheckedIOException("写入记忆失败", e);
        }
    }

    /** 分类记忆文件列表（{@code memory/} 下非日期的 .md），按修改时间倒序（最近在前，同刻按名升序）。 */
    public List<MemoryFile> listCategoryFiles(String scopeKey) {
        Path dir = scopeDir(scopeKey);
        if (dir == null) {
            return List.of();
        }
        Path memoryDir = dir.resolve(MEMORY_DIR);
        if (!Files.isDirectory(memoryDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(memoryDir)) {
            return s.filter(MemoryService::isMdFile)
                    .filter(p -> !isDailyFile(p.getFileName().toString()))
                    .map(p -> new MemoryFile(p.getFileName().toString(), lastModified(p)))
                    .sorted(Comparator.comparingLong(MemoryFile::lastModified).reversed()
                            .thenComparing(MemoryFile::name))
                    .toList();
        } catch (IOException e) {
            log.warn("列出分类记忆文件失败: {}", memoryDir, e);
            return List.of();
        }
    }

    /** 每日台账文件名列表（{@code memory/YYYY-MM-DD.md}，按名字逆序，最新在前）。 */
    public List<String> listDailyFiles(String scopeKey) {
        Path dir = scopeDir(scopeKey);
        if (dir == null) {
            return List.of();
        }
        Path dailyDir = dir.resolve(MEMORY_DIR);
        if (!Files.isDirectory(dailyDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dailyDir)) {
            return s.filter(MemoryService::isMdFile)
                    .map(p -> p.getFileName().toString())
                    .filter(MemoryService::isDailyFile)
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            log.warn("列出记忆台账失败: {}", dailyDir, e);
            return List.of();
        }
    }

    /** 读取某条记忆文件（分类文件或台账）；文件名校验防目录穿越，非法名返回空串。 */
    public String readMemoryFile(String scopeKey, String name) {
        Path file = safeMemoryFile(scopeKey, name);
        if (file == null) {
            return "";
        }
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            log.warn("读取记忆文件失败: {}", file, e);
            return "";
        }
    }

    /** 覆盖写入某条记忆文件（不存在则创建）；文件名校验防目录穿越。 */
    public void writeMemoryFile(String scopeKey, String name, String content) {
        Path file = safeMemoryFile(scopeKey, name);
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
            log.info("已写入记忆文件: {}", file);
        } catch (IOException e) {
            throw new UncheckedIOException("写入记忆文件失败", e);
        }
    }

    /**
     * 新建分类记忆文件：名字经 {@link #normalizeCategoryName} 清洗校验后落 {@code memory/<名字>.md}，
     * 成功返回最终文件名（含 .md）。名称为空/非法/日期格式抛 {@link IllegalArgumentException}，
     * 同名已存在抛 {@link IllegalStateException}。
     */
    public String createCategoryFile(String scopeKey, String rawName, String content) {
        Path dir = scopeDir(scopeKey);
        if (dir == null) {
            throw new IllegalArgumentException("未知记忆作用域");
        }
        String name = normalizeCategoryName(rawName);
        if (name == null) {
            throw new IllegalArgumentException("记忆文件名称无效");
        }
        Path file = dir.resolve(MEMORY_DIR).resolve(name);
        if (Files.exists(file)) {
            throw new IllegalStateException("已存在同名记忆文件：" + name);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
            log.info("已新建分类记忆文件: {}", file);
            return name;
        } catch (IOException e) {
            throw new UncheckedIOException("新建记忆文件失败", e);
        }
    }

    /** 删除某条记忆文件（分类文件或台账）；文件名校验防目录穿越。 */
    public void deleteMemoryFile(String scopeKey, String name) {
        Path file = safeMemoryFile(scopeKey, name);
        if (file == null) {
            return;
        }
        try {
            if (Files.deleteIfExists(file)) {
                log.info("已删除记忆文件: {}", file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("删除记忆文件失败", e);
        }
    }

    /**
     * 分类文件名规范化：保留字母/数字/CJK，其余折叠为连字符，补 {@code .md} 后缀。
     * 空名、超长、日期格式（会与每日台账混淆）或保留名（archive）返回 {@code null}。
     * <p>UI 新建表单与智能体写入工具（memory_write）共用此口径。</p>
     */
    public static String normalizeCategoryName(String rawName) {
        String raw = rawName == null ? "" : rawName.strip();
        if (raw.toLowerCase(java.util.Locale.ROOT).endsWith(FileKinds.MD)) {
            raw = raw.substring(0, raw.length() - FileKinds.MD.length());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.deleteCharAt(sb.length() - 1);
        }
        String base = sb.toString();
        if (base.isEmpty() || base.length() > NAME_MAX_LEN
                || DAILY_FILE.matcher(base + FileKinds.MD).matches()
                || "archive".equalsIgnoreCase(base)) {
            return null;
        }
        return base + FileKinds.MD;
    }

    /**
     * 整个删除该作用域的全部记忆：{@code MEMORY.md} 与 {@code memory/} 目录（含 archive 归档），
     * 落点由框架下次沉淀时重建；作用域键未知时静默返回。
     */
    public void deleteAll(String scopeKey) {
        Path dir = scopeDir(scopeKey);
        if (dir == null) {
            return;
        }
        try {
            Files.deleteIfExists(dir.resolve(MEMORY_MD));
            Path dailyDir = dir.resolve(MEMORY_DIR);
            if (Files.isDirectory(dailyDir)) {
                List<Path> victims;
                try (Stream<Path> s = Files.walk(dailyDir)) {
                    // 逆序即先删文件后删目录，整棵子树（含 archive）清空
                    victims = s.sorted(Comparator.reverseOrder()).toList();
                }
                for (Path victim : victims) {
                    Files.deleteIfExists(victim);
                }
            }
            log.info("已删除记忆作用域全部文件: {}", dir);
        } catch (IOException e) {
            throw new UncheckedIOException("删除记忆失败", e);
        }
    }

    /**
     * 解析作用域键对应的工作区目录；仅接受 {@link #listScopes()} 枚举出的已知目录，
     * 未知/伪造键返回 {@code null}（调用方按空操作处理）。
     */
    private Path scopeDir(String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank()) {
            return null;
        }
        for (MemoryScope scope : listScopes()) {
            if (scope.key().equals(scopeKey)) {
                return scope.dir();
            }
        }
        return null;
    }

    /** 仅接受 {@code memory/} 目录下的裸 {@code .md} 文件名，含分隔符或非法名一律拒绝。 */
    private Path safeMemoryFile(String scopeKey, String name) {
        if (name == null || name.isBlank() || !name.endsWith(FileKinds.MD)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.contains("..")) {
            return null;
        }
        Path dir = scopeDir(scopeKey);
        return dir == null ? null : dir.resolve(MEMORY_DIR).resolve(name);
    }

    /** 统计某作用域的文件数与最近修改时间：MEMORY.md 计 1，memory/ 顶层 .md 台账逐个累计。 */
    private static MemoryScope toScope(String key, Path dir, List<String> agentNames, boolean shared) {
        int count = 0;
        long last = 0L;
        Path memoryMd = dir.resolve(MEMORY_MD);
        if (Files.isRegularFile(memoryMd)) {
            count++;
            last = lastModified(memoryMd);
        }
        Path dailyDir = dir.resolve(MEMORY_DIR);
        if (Files.isDirectory(dailyDir)) {
            try (Stream<Path> s = Files.list(dailyDir)) {
                for (Path p : s.filter(MemoryService::isMdFile).toList()) {
                    count++;
                    last = Math.max(last, lastModified(p));
                }
            } catch (IOException e) {
                log.warn("统计记忆台账失败: {}", dailyDir, e);
            }
        }
        return new MemoryScope(key, dir, List.copyOf(agentNames), shared, count, last);
    }

    private static boolean isMdFile(Path p) {
        return p.getFileName().toString().endsWith(FileKinds.MD);
    }

    private static boolean isDailyFile(String name) {
        return DAILY_FILE.matcher(name).matches();
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static Path normalize(Path p) {
        return p.toAbsolutePath().normalize();
    }

    /** 智能体显示名：优先 agentName，缺省回退 agentCode。 */
    private static String displayName(AgentDefinition def) {
        String name = def.getAgentName();
        return name == null || name.isBlank() ? def.getAgentCode() : name;
    }
}
