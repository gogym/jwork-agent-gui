package com.gettyio.gim.agent.core.memory;

import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 应用级长期记忆管理：读写 Harness 在工作区内维护的记忆文件，供 UI 查看/编辑/清空。
 * <p>Harness 默认启用长期记忆：{@code <workspace>/MEMORY.md} 为精炼长期记忆，{@code <workspace>/memory/*.md}
 * 为每日台账（由 MemoryFlush/Consolidate 自动写入）。本服务只做「读现成文件 + 覆盖/删除」，
 * 不复制框架的合并逻辑——智能体运行时仍照常往 MEMORY.md 追加，用户在 UI 的编辑即时落到同一文件。</p>
 * <p>记忆按智能体隔离：各智能体的工作区由 {@link MultiAgentGateway#workspaceForAgent} 解析（未设专属目录
 * 者共享默认工作区，因而共享同一份 MEMORY.md），与运行时装配口径完全一致。</p>
 */
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private static final String MEMORY_MD = "MEMORY.md";
    private static final String MEMORY_DIR = "memory";

    /** 清空长期记忆时写回的最小骨架，保留标题让 Harness 继续在同一文件上沉淀。 */
    private static final String MEMORY_SKELETON = """
            # 长期记忆

            （由 Harness 自动维护，记录用户偏好、常用事实与约定）
            """;

    private final GimDatabase db;
    private final MultiAgentGateway gateway;

    public MemoryService(GimDatabase db, MultiAgentGateway gateway) {
        this.db = db;
        this.gateway = gateway;
    }

    /** 全部智能体定义（供记忆页选择器）。 */
    public List<AgentDefinition> listAgents() {
        return db.agentDao().findAll();
    }

    /** 该智能体的工作区根目录（记忆文件的落点）。 */
    public Path workspaceOf(String agentCode) {
        return gateway.workspaceForAgent(agentCode);
    }

    /** 读取长期记忆 {@code MEMORY.md}；不存在返回空串（UI 以占位提示区分「空」与「未生成」）。 */
    public String readLongTerm(String agentCode) {
        Path file = workspaceOf(agentCode).resolve(MEMORY_MD);
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            log.warn("读取 MEMORY.md 失败: {}", file, e);
            return "";
        }
    }

    /** 覆盖写入长期记忆 {@code MEMORY.md}（父目录不存在则创建）。 */
    public void writeLongTerm(String agentCode, String content) {
        Path file = workspaceOf(agentCode).resolve(MEMORY_MD);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
            log.info("已写入 MEMORY.md: {}", file);
        } catch (IOException e) {
            throw new UncheckedIOException("写入记忆失败", e);
        }
    }

    /** 清空长期记忆：写回最小骨架（而非删除文件，保持 Harness 后续沉淀的落点不变）。 */
    public void clearLongTerm(String agentCode) {
        writeLongTerm(agentCode, MEMORY_SKELETON);
    }

    /** 每日台账文件名列表（{@code memory/*.md}，按名字逆序，最新在前）。 */
    public List<String> listDaily(String agentCode) {
        Path dir = workspaceOf(agentCode).resolve(MEMORY_DIR);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            log.warn("列出记忆台账失败: {}", dir, e);
            return List.of();
        }
    }

    /** 读取某条每日台账内容；文件名校验防目录穿越，非法名返回空串。 */
    public String readDaily(String agentCode, String name) {
        Path file = safeMemoryFile(agentCode, name);
        if (file == null) {
            return "";
        }
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            log.warn("读取记忆台账失败: {}", file, e);
            return "";
        }
    }

    /** 删除某条每日台账；文件名校验防目录穿越。 */
    public void deleteDaily(String agentCode, String name) {
        Path file = safeMemoryFile(agentCode, name);
        if (file == null) {
            return;
        }
        try {
            if (Files.deleteIfExists(file)) {
                log.info("已删除记忆台账: {}", file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("删除记忆台账失败", e);
        }
    }

    /** 仅接受 {@code memory/} 目录下的裸 {@code .md} 文件名，含分隔符或非法名一律拒绝。 */
    private Path safeMemoryFile(String agentCode, String name) {
        if (name == null || name.isBlank() || !name.endsWith(".md")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.contains("..")) {
            return null;
        }
        return workspaceOf(agentCode).resolve(MEMORY_DIR).resolve(name);
    }
}
