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

package com.gettyio.gim.agent.core.subagent;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用自带的委派对象：只读探索子代理 {@code explore}。
 *
 * <p>为什么内置而不是建一条 {@code agents} 表记录：库里的定义是「用户资产」，会被设置页编辑、
 * 被导出导入、删掉就没了；而探索能力是主控智能体本身该有的手脚，必须装配即在场。走代码声明还有一处
 * 关键收益——{@link SubagentDeclaration.Builder#tools(List)} 是白名单：框架给子代理只注册名单内的
 * 工具，写文件与跑命令根本不存在，不是「有工具但提示词叫它别用」。</p>
 *
 * <p>{@link WorkspaceMode#ISOLATED} 不是笔误：这条路径会把父级 {@code LocalFilesystemSpec} 的
 * project/additionalRoots/mode 原样克隆给子代理，所以项目目录的读写边界与主控一致；换成 SHARED
 * 反而不下发该 spec，子代理的 project 会退回 {@code user.dir}（应用启动目录），搜不到用户代码。
 * 隔离出来的运行目录只影响写落点，而它没有写工具。</p>
 *
 * @author gogym
 */
public final class BuiltinSubagents {

    /** 探索子代理的委派名：模型以 {@code agent_spawn(agent="explore")} 调用。 */
    public static final String EXPLORE = "explore";

    /**
     * 探索子代理的工具白名单（框架内置的只读检索四件套）。
     * <p>不给 {@code write_file}/{@code edit_file}/{@code execute}：探索的产出是「结论 + 出处」，
     * 改动一律交回主控在授权档位下做。也不给网络工具——委派给它的都是本地仓库里的问题。</p>
     */
    static final List<String> EXPLORE_TOOLS =
            List.of("read_file", "grep_files", "glob_files", "list_files");

    /** 探索轮次上限：框架默认 10 轮，跨文件定位往往要「搜—读—再搜」好几轮，触顶就只能交回半截结论。 */
    private static final int EXPLORE_STEPS = 16;

    /** 委派判据写给主控模型看：说清什么任务该派、派回来的是什么，模型才不至于把三行代码的活也外包。 */
    private static final String EXPLORE_DESCRIPTION = """
            只读检索子代理：需要在授权目录里翻多个文件才能回答时使用——「这个逻辑在哪实现」\
            「全仓有几处调用 X」「这批配置项都出现在哪些文件」。它自带一份独立上下文，\
            先搜后读，只回结论与 文件:行号 证据，不能写文件、不能跑命令。已经知道具体文件与\
            位置的活不必派给它，直接自己读。""";

    /** 子代理人格：只讲怎么搜、怎么交回结果，不重复主智能体的通用总则。 */
    private static final String EXPLORE_BODY = """
            你是只读的检索子代理，被主控派来查一件事。你不改任何东西——也没有改东西的工具。

            - 先搜后读：用 grep_files / glob_files / list_files 定位置，再用 read_file 的
              offset/limit 只读命中处附近，别把整份文件灌进自己的上下文。
            - grep_files 是字面量匹配，不支持正则：给原文里真实出现过的片段，
              找不到就换个更短的关键词再试一次，而不是断定「代码里没有」。
            - 范围只在授权的项目与工作区目录内；越界路径会被直接拒绝，不要绕。
            - 最后一条回复就是主控看到的全部内容（中间的搜索过程不回传），按三段给：
              结论（1-3 句）；证据（相对路径:行号 —— 这行为什么相关）；未尽事项
              （还需要主控确认或改动的点，没有就不写）。
            - 证据最多 15 条，同一件事只留最说明问题的一行；没查到就如实说搜过哪些关键词与目录，
              不要臆测出处。

            """;

    private BuiltinSubagents() {
    }

    /**
     * 构造内置探索子代理声明。
     *
     * @return 只读检索的委派声明（每次装配新建一份，声明本身不可变）
     */
    public static SubagentDeclaration explore() {
        return SubagentDeclaration.builder()
                .name(EXPLORE)
                .description(EXPLORE_DESCRIPTION)
                .inlineAgentsBody(EXPLORE_BODY)
                .workspaceMode(WorkspaceMode.ISOLATED)
                // 只能被委派，不允许当主智能体直跑：它的人格是「查完就回话」，没有交付习惯
                .mode(SubagentDeclaration.Mode.SUBAGENT)
                .tools(EXPLORE_TOOLS)
                .steps(EXPLORE_STEPS)
                .build();
    }

    /**
     * 把内置对象并到用户（数据库定义）声明前面，同名让位于用户——用户的机器上用户说了算。
     *
     * @param declared 用户声明，可空
     * @return 至少含 {@code explore} 的声明列表，永不为空、不含 null
     */
    public static List<SubagentDeclaration> withExplore(List<SubagentDeclaration> declared) {
        List<SubagentDeclaration> user = new ArrayList<>();
        if (declared != null) {
            for (SubagentDeclaration decl : declared) {
                if (decl != null) {
                    user.add(decl);
                }
            }
        }
        List<SubagentDeclaration> out = new ArrayList<>(user.size() + 1);
        boolean nameTaken = user.stream()
                .anyMatch(decl -> EXPLORE.equalsIgnoreCase(decl.getName().trim()));
        if (!nameTaken) {
            out.add(explore());
        }
        out.addAll(user);
        return out;
    }
}
