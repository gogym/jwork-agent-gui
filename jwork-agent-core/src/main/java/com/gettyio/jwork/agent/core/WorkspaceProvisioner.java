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

package com.gettyio.jwork.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
    public static final int TEMPLATE_VERSION = 4;

    /** 模板版本标记（写在文末，对用户不可见但可解析）。 */
    private static final String TEMPLATE_MARK = "<!-- jwork-agents-template: ";

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
              必要时用 web_fetch 读某条链接的正文：它会剥掉导航与评论只留正文，想知道特定信息就带
              prompt 参数（只回最相关的几段），不传则按 max_chars（默认 20000 字符）从正文开头截断。
            - 产物交付：最终成果（报表、文档、脚本、图片）写好后必须调用 deliver_artifact(filePath, fileName,
              description) 交到本机；把工具返回的路径写进回答，用户点得到才算交付。没交付的产出等于没做。
            - 结果核对：数据类产物落盘后用 inspect_file(path, sheet, offset, rows) 抽查几行，回答里给出核对结论
              （合计金额、订单条数、与源表的差值），不要只回一句「已完成」。
            - 表格附件：用户给的大表已在提示里给出本机路径与工作表结构；按需用 inspect_file 分片取数，
              全量统计用 execute 跑 Python（pandas/openpyxl）读该路径，别把整份表塞进上下文。
            - 改写不留垃圾：覆盖自己上一轮的脚本或草稿用 write_file(overwrite=true)，不要一路换名留下
              v2/final 中间版本；用户给的数据文件一律不改写。
            - 先搜后读：找东西别整份读文件。grep_files 是正则搜索（get.*Name 这类写法可用，要字面量
              就转义点号），output_mode 取 content / files_with_matches / count，必要时用 glob 或 type
              收窄、用 context_before / context_after 带出上下文；glob_files 按修改时间新→旧排，
              刚生成的产物排在最前。读大文件用 read_file 的 offset/limit 分段（返回带行号，
              被截断时会告诉下一个 offset），改哪一段就照行号定位。
            - 翻找外包给子代理：要横跨许多文件定位实现或调用点、结论只要几句话时，用
              agent_spawn(agent_id="explore", task=…, timeout_seconds=180) 派给只读探索子代理。
              它另占一份上下文，只回结论与 文件:行号 证据，不能写文件也不能跑命令；
              默认只等 30 秒，超时它会转成后台任务并给 task_id，用 task_output(task_id) 续读。
              已经知道在哪的活不必派，改动一律由你自己做。
            - 长任务别干等：dev server、训练、大表导出这类跑上一两分钟以上的命令，用
              execute(run_in_background=true) 拿任务号，边做别的边用 shell_output(task_id, cursor)
              增量读进度，收尾或跑偏用 kill_shell(task_id) 停掉；同步档只留给确定能在超时内跑完的命令。
            - 简洁直接：回答以可执行为先，避免冗余。
            """ + TEMPLATE_MARK + TEMPLATE_VERSION + " -->\n";

    /** v3 内置人格原文（带当时的版本标记）：升到 v4 时靠它逐字核对「用户没动过」。 */
    private static final String AGENTS_MD_V3 = """
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
            - 先搜后读：找东西别整份读文件。grep_files 是正则搜索（get.*Name 这类写法可用，要字面量
              就转义点号），output_mode 取 content / files_with_matches / count，必要时用 glob 或 type
              收窄、用 context_before / context_after 带出上下文；glob_files 按修改时间新→旧排，
              刚生成的产物排在最前。读大文件用 read_file 的 offset/limit 分段（返回带行号，
              被截断时会告诉下一个 offset），改哪一段就照行号定位。
            - 长任务别干等：dev server、训练、大表导出这类跑上一两分钟以上的命令，用
              execute(run_in_background=true) 拿任务号，边做别的边用 shell_output(task_id, cursor)
              增量读进度，收尾或跑偏用 kill_shell(task_id) 停掉；同步档只留给确定能在超时内跑完的命令。
            - 简洁直接：回答以可执行为先，避免冗余。
            """ + TEMPLATE_MARK + "3 -->\n";

    /** v2 内置人格原文（带当时的版本标记）：升级前逐字核对用的就是这一份。 */
    private static final String AGENTS_MD_V2 = """
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
            """ + TEMPLATE_MARK + "2 -->\n";

    /**
     * 带版本标记的历代模板原文：版本号比当前旧时，只有正文与这里逐字（忽略空白）对得上才算「用户没动过」。
     * <p>光看文末标记不够——用户改了正文却留着标记是常见情形，按标记覆盖就把人家的人格冲掉了。</p>
     */
    private static final Map<Integer, String> MARKED_TEMPLATES = Map.of(2, AGENTS_MD_V2, 3, AGENTS_MD_V3);

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

    private final JworkPaths paths;

    public WorkspaceProvisioner(JworkPaths paths) {
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
            if (version >= TEMPLATE_VERSION) {
                return AgentsMdState.CURRENT;
            }
            // 标记旧还不够：正文必须仍是那一版模板的原样。用户改了人格却留着标记时不能覆盖，
            // 否则「一键升级」就成了「一键清空别人的定制」
            String known = MARKED_TEMPLATES.get(version);
            return known != null && sameIgnoringWhitespace(content, known)
                    ? AgentsMdState.UPGRADABLE : AgentsMdState.CUSTOMIZED;
        }
        String plain = stripWhitespace(content);
        for (String legacy : LEGACY_AGENTS_MD) {
            if (plain.equals(stripWhitespace(legacy))) {
                return AgentsMdState.UPGRADABLE;
            }
        }
        return AgentsMdState.CUSTOMIZED;
    }

    /** 忽略空白并去掉版本标记后是否逐字相同。 */
    private static boolean sameIgnoringWhitespace(String actual, String template) {
        return stripWhitespace(withoutMarker(actual)).equals(stripWhitespace(withoutMarker(template)));
    }

    /** 某一版的模板原文（只记录比当前旧的带标记版本）：升级前的逐字核对与测试共用这一份历史。 */
    static String markedTemplate(int version) {
        return MARKED_TEMPLATES.get(version);
    }

    /** 剥掉文末的版本标记（标记本身随版本而变，不参与正文比对）。 */
    private static String withoutMarker(String content) {
        int from = content.indexOf(TEMPLATE_MARK);
        if (from < 0) {
            return content;
        }
        int end = content.indexOf("-->", from);
        return end < 0 ? content.substring(0, from) : content.substring(0, from)
                + content.substring(end + 3);
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
