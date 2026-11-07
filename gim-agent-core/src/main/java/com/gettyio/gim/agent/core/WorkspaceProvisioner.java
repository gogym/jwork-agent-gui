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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 工作区初始化：确保 Harness 工作区目录与默认人格/记忆文件存在（幂等）
 *
 * @author gogym
 */
public class WorkspaceProvisioner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceProvisioner.class);

    /**
     * 默认人格模板版本：改动 {@link #DEFAULT_AGENTS_MD} 即 +1。
     * <p>AGENTS.md 是用户可编辑文件，幂等初始化只补不覆盖，因此新版本文案不会自动落到老工作区。
     * UI 侧用 {@link #agentsMdState()} 判定「仍是旧版默认人格」时提示一键升级。</p>
     */
    public static final int TEMPLATE_VERSION = 2;

    /** 模板版本标记（写在文末，对用户不可见但可解析）。 */
    private static final String TEMPLATE_MARK = "<!-- gim-agents-template: ";

    /** 规范化比较时抹掉的空白（含全角空格与不间断空格）。 */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u3000\\u00A0]+");

    private static final String DEFAULT_AGENTS_MD = """
            # Jwork

            你是运行在本地桌面的个人 AI 助理。遵循以下原则：

            - 本地优先：优先使用用户授权目录内的文件与工具。
            - 安全确认：执行文件写入、shell 命令等副作用操作前，触发用户确认。
            - 记忆沉淀：将长期有价值的事实写入 MEMORY.md。
            - 定时能力：用户要求定期/到点执行某事时，使用 schedule_task 工具（Quartz cron 表达式），
              并用 list_tasks / cancel_task 管理与查询任务。若是一次性「指定时刻」执行（如“明天 8 点提醒我”），
              改传 run_at 参数（yyyy-MM-dd HH:mm:ss），它与 cron 互斥。
            - 技能复用：workspace/skills/ 下已启用的技能（SKILL.md）定义了专项任务规范，命中时应遵循。
            - 联网检索：需要实时信息（新闻、价格、最新版本、知识截止后的事实）时，使用 web_search 搜索，
              必要时用 web_fetch 读取某条链接的完整正文。
            - 产物交付：最终成果（报表、文档、脚本、图片）写好后必须调用 deliver_artifact(filePath, fileName,
              description) 交到本机；把工具返回的路径写进回答，用户点得到才算交付。没交付的产出等于没做。
            - 结果核对：数据类产物落盘后用 inspect_file(path, sheet, offset, rows) 抽查几行，回答里给出核对结论
              （合计金额、订单条数、与源表的差值），不要只回一句「已完成」。
            - 表格附件：用户给的大表已在提示里给出本机路径与工作表结构；按需用 inspect_file 分片取数，
              全量统计用 execute 跑 Python（pandas/openpyxl）读该路径，别把整份表塞进上下文。
            - 改写不留垃圾：覆盖自己上一轮的脚本或草稿用 write_file(overwrite=true)，不要一路换名留下
              v2/final 中间版本；用户给的数据文件一律不改写。
            - 简洁直接：回答以可执行为先，避免冗余。
            """ + TEMPLATE_MARK + TEMPLATE_VERSION + " -->\n";

    /** v1 内置人格原文（补「联网检索」那一版，无版本标记）。 */
    private static final String LEGACY_AGENTS_MD_V1 = """
            # Jwork

            你是运行在本地桌面的个人 AI 助理。遵循以下原则：

            - 本地优先：优先使用用户授权目录内的文件与工具。
            - 安全确认：执行文件写入、shell 命令等副作用操作前，触发用户确认。
            - 记忆沉淀：将长期有价值的事实写入 MEMORY.md。
            - 定时能力：用户要求定期/到点执行某事时，使用 schedule_task 工具（Quartz cron 表达式），
              并用 list_tasks / cancel_task 管理与查询任务。若是一次性「指定时刻」执行（如“明天 8 点提醒我”），
              改传 run_at 参数（yyyy-MM-dd HH:mm:ss），它与 cron 互斥。
            - 技能复用：workspace/skills/ 下已启用的技能（SKILL.md）定义了专项任务规范，命中时应遵循。
            - 联网检索：需要实时信息（新闻、价格、最新版本、知识截止后的事实）时，使用 web_search 搜索，
              必要时用 web_fetch 读取某条链接的完整正文。
            - 简洁直接：回答以可执行为先，避免冗余。
            """;

    /**
     * 历代「用户从没动过」的内置人格原文（无版本标记的早期版本才需要逐字比对）。
     * <p>只要文件与其中任一份规范化后相同就判定可升级；对不上即视为用户改写过，一律不碰。</p>
     */
    private static final String[] LEGACY_AGENTS_MD = {
            // 补「联网检索」之前的内置人格
            """
            # Jwork

            你是运行在本地桌面的个人 AI 助理。遵循以下原则：

            - 本地优先：优先使用用户授权目录内的文件与工具。
            - 安全确认：执行文件写入、shell 命令等副作用操作前，触发用户确认。
            - 记忆沉淀：将长期有价值的事实写入 MEMORY.md。
            - 定时能力：用户要求定期/到点执行某事时，使用 schedule_task 工具（Quartz cron 表达式），
              并用 list_tasks / cancel_task 管理与查询任务。若是一次性「指定时刻」执行（如“明天 8 点提醒我”），
              改传 run_at 参数（yyyy-MM-dd HH:mm:ss），它与 cron 互斥。
            - 技能复用：workspace/skills/ 下已启用的技能（SKILL.md）定义了专项任务规范，命中时应遵循。
            - 简洁直接：回答以可执行为先，避免冗余。
            """,
            LEGACY_AGENTS_MD_V1
    };

    private static final String DEFAULT_MEMORY_MD = """
            # 长期记忆

            （由 Harness 自动维护，记录用户偏好、常用事实与约定）
            """;

    private static final String DEFAULT_KNOWLEDGE_MD = """
            # 知识库目录

            （本目录存放可长期参考的资料；每导入一份资料，在此追加一行「文件名 — 一句话摘要」，
            便于智能体按需定位与读取。）
            """;

    private final GimPaths paths;

    public WorkspaceProvisioner(GimPaths paths) {
        this.paths = paths;
    }

    /** 幂等初始化工作区：创建目录树，并在文件缺失时生成 AGENTS.md/MEMORY.md/KNOWLEDGE.md 默认内容；IO 失败包装为 {@link UncheckedIOException}。 */
    public void provision() {
        try {
            createDirs();
            ensureFile(paths.agentsMd(), DEFAULT_AGENTS_MD);
            ensureFile(paths.memoryMd(), DEFAULT_MEMORY_MD);
            ensureFile(paths.knowledgeMd(), DEFAULT_KNOWLEDGE_MD);
            log.info("工作区就绪: {}", paths.workspaceDir());
        } catch (IOException e) {
            throw new UncheckedIOException("初始化工作区失败", e);
        }
    }

    /** {@link #agentsMdState()} 的判定结果：决定 UI 要不要弹「一键升级人格」。 */
    public enum AgentsMdState {
        /** 已是当前模板版本，无需处理。 */
        CURRENT,
        /** 仍是旧版内置人格（用户从没改过），可直接替换为当前模板。 */
        UPGRADABLE,
        /** 用户改写过：不提示、不覆盖。 */
        CUSTOMIZED,
        /** 文件还没生成，{@link #provision()} 会直接写当前模板。 */
        ABSENT
    }

    /**
     * 判定 AGENTS.md 与内置人格模板的关系。
     * <p>读失败按 {@link AgentsMdState#CUSTOMIZED} 处理：宁可不打扰，也不能因为读不出内容就去覆盖用户文件。</p>
     */
    public AgentsMdState agentsMdState() {
        Path file = paths.agentsMd();
        if (!Files.exists(file)) {
            return AgentsMdState.ABSENT;
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException e) {
            log.warn("读取 AGENTS.md 失败，跳过人格升级提示: {}", file, e);
            return AgentsMdState.CUSTOMIZED;
        }
        int version = templateVersionOf(content);
        if (version > 0) {
            return version >= TEMPLATE_VERSION ? AgentsMdState.CURRENT : AgentsMdState.UPGRADABLE;
        }
        String plain = stripWhitespace(content);
        for (String legacy : LEGACY_AGENTS_MD) {
            if (plain.equals(stripWhitespace(legacy))) {
                return AgentsMdState.UPGRADABLE;
            }
        }
        return AgentsMdState.CUSTOMIZED;
    }

    /**
     * 一键升级内置人格：仅替换 {@link AgentsMdState#UPGRADABLE}（旧版默认原文）的 AGENTS.md，
     * 用户改写过的与已升级过的一律原样返回 false。
     */
    public boolean upgradeAgentsMd() {
        if (agentsMdState() != AgentsMdState.UPGRADABLE) {
            return false;
        }
        try {
            Files.writeString(paths.agentsMd(), DEFAULT_AGENTS_MD, StandardCharsets.UTF_8);
            log.info("AGENTS.md 已升级到模板 v{}: {}", TEMPLATE_VERSION, paths.agentsMd());
            return true;
        } catch (IOException e) {
            log.warn("升级 AGENTS.md 失败: {}", paths.agentsMd(), e);
            return false;
        }
    }

    /** 解析文末版本标记里的模板版本号；无标记返回 0（表示早期版本或被用户删掉了标记）。 */
    private static int templateVersionOf(String content) {
        int from = content.indexOf(TEMPLATE_MARK);
        if (from < 0) {
            return 0;
        }
        int start = from + TEMPLATE_MARK.length();
        int end = start;
        while (end < content.length() && Character.isDigit(content.charAt(end))) {
            end++;
        }
        if (end == start) {
            return 0;
        }
        try {
            return Integer.parseInt(content.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 抹掉全部空白后比较，避免换行符（CRLF/LF）与缩进差异把旧版默认原文误判成「用户改过」。 */
    private static String stripWhitespace(String s) {
        return WHITESPACE.matcher(s).replaceAll("");
    }

    private void createDirs() throws IOException {
        Files.createDirectories(paths.root());
        Files.createDirectories(paths.workspaceDir());
        Files.createDirectories(paths.skillsDir());
        Files.createDirectories(paths.knowledgeDir());
        Files.createDirectories(paths.memoryDir());
        Files.createDirectories(paths.logsDir());
        Files.createDirectories(paths.backupsDir());
        Files.createDirectories(paths.attachmentsDir());
    }

    private void ensureFile(Path file, String defaultContent) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, defaultContent, StandardCharsets.UTF_8);
            log.info("生成默认文件: {}", file);
        }
    }
}
