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

package com.gettyio.gim.agent.tools.hitl;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 反问处理器：由 UI 层实现，把智能体的澄清问题弹给用户并异步回填答案。
 * <p>与 {@link HitlConfirmHandler} 同一条人机通道，但语义不同：确认框问「能不能做」，
 * 这里问「你要的是哪个」。</p>
 *
 * @author gogym
 */
public interface AskUserHandler {

    /**
     * 哨兵答案：用户点「由你判断」（或对话框压根没答上）。实现方回填这个值而不是自然文本，
     * {@link AskUserTool} 据此换成「自己挑一个并把理由说清楚」的指引。
     * <p>两侧的点号是让它长得就不像人会打出来的话；别用不可见字符做前后缀，
     * 工具侧要先 trim 再比对，控制字符会被直接削掉。</p>
     */
    String YOU_DECIDE = "::you-decide::";

    /**
     * 提问并等待回答。
     *
     * @param question 问题原文
     * @param options  候选答案（可为空列表，空则表示要自由输入）
     * @return 用户答案；null 或 {@link #YOU_DECIDE} 表示没拿到（用户关窗、不在电脑前或无主窗口）
     */
    CompletableFuture<String> ask(String question, List<String> options);
}
