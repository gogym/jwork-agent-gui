package com.gettyio.gim.agent.core.plan;

import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 计划文件读写：定位并读写 Harness 计划模式产出的 {@code <workspace>/plans/PLAN.md}。
 * <p>计划模式下智能体经 {@code plan_write} 把蓝图写到工作区相对路径 {@code plans/PLAN.md}（由框架的
 * WorkspaceManager 落到本地磁盘）。本服务只做「读该文件供 UI 预览/编辑」与「写回用户确认后的版本」，
 * 不复制框架的计划态管理；路径与运行时装配口径一致（按智能体工作区解析）。</p>
 */
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private static final String PLAN_DIR = "plans";
    private static final String PLAN_FILE = "PLAN.md";

    private final MultiAgentGateway gateway;

    public PlanService(MultiAgentGateway gateway) {
        this.gateway = gateway;
    }

    /** 该智能体的计划文件绝对路径。 */
    public Path planFileFor(String agentCode) {
        return gateway.workspaceForAgent(agentCode).resolve(PLAN_DIR).resolve(PLAN_FILE);
    }

    /** 计划文件是否已存在（规划轮跑过后才有）。 */
    public boolean exists(String agentCode) {
        return Files.exists(planFileFor(agentCode));
    }

    /** 读取计划内容；不存在返回空串。 */
    public String read(String agentCode) {
        Path file = planFileFor(agentCode);
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            log.warn("读取计划文件失败: {}", file, e);
            return "";
        }
    }

    /** 写回计划内容（父目录不存在则创建）。 */
    public void write(String agentCode, String content) {
        Path file = planFileFor(agentCode);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
            log.info("已写回计划文件: {}", file);
        } catch (IOException e) {
            throw new UncheckedIOException("写入计划文件失败", e);
        }
    }
}
