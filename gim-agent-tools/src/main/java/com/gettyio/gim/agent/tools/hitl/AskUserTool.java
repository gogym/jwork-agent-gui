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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 澄清反问工具 {@code ask_user}：把「缺了就会跑偏」的问题弹给坐在电脑前的人，拿到回答再继续跑。
 * <p>没有它时模型只能猜——猜错口径的报表比不做更糟（本仓库的退款差异任务就吃过这个亏）。
 * 它跟 HITL 确认框不是一回事：确认框问「允不允许这么做」，这里问「你要的是哪一个」。</p>
 * <p>答案经 {@link AskUserHandler} 由 UI 层回填；拿不到答案（用户关窗、后台定时任务无人可问）
 * 时返回一句明确的「自己判断并把假设写进回答」，绝不把调用挂死。</p>
 *
 * @author gogym
 */
public final class AskUserTool {

    public static final String NAME = "ask_user";

    private static final Logger log = LoggerFactory.getLogger(AskUserTool.class);

    private static final String DESCRIPTION =
            "Ask the human user one clarifying question and wait for the answer. Use it only when a"
                    + " missing detail would change the result and there is no reasonable default;"
                    + " ask one question at a time, offer 2-4 short options when the choices are"
                    + " enumerable, and omit options to ask for free-form input. Do not use it to"
                    + " report progress, to summarise, or to ask permission for a tool call - the"
                    + " confirmation dialog already covers permission.";

    /** 选项按钮上限：再多一屏放不下，也让用户难选。 */
    private static final int MAX_OPTIONS = 4;
    /** 问题文本上限：弹窗一行放不下长段落，模型啰嗦时截断即可。 */
    private static final int QUESTION_MAX = 300;
    /** 等待回答的上限时长（分钟）：对话框被忘在后台也不能把这一轮永远挂住。 */
    private static final long ANSWER_WAIT_MINUTES = 30;

    /** 拿不到答案时回给模型的指引：让它继续干活，但必须把假设写明白。 */
    static final String NO_ANSWER =
            "The user did not answer (they closed the prompt or are away). Proceed with your best"
                    + " judgement now, state the assumption explicitly in your reply, and offer to"
                    + " redo it if the assumption is wrong.";

    private final AskUserHandler handler;

    public AskUserTool(AskUserHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /**
     * @param runtimeContext per-call agent runtime injected by the framework (not an LLM argument)
     * @param question       问题原文
     * @param options        候选答案，可为空（表示要自由输入）
     */
    @Tool(name = NAME, description = DESCRIPTION, readOnly = true, concurrencySafe = false)
    public CompletableFuture<String> askUser(
            RuntimeContext runtimeContext,
            @ToolParam(name = "question", description = "The one thing you need to know, phrased"
                    + " so it can be answered in a sentence or by picking an option") String question,
            @ToolParam(name = "options", required = false, description = "Short mutually exclusive"
                    + " choices to show as buttons (2-4 items). Omit for free-form input.")
                    List<String> options) {
        if (question == null || question.isBlank()) {
            return CompletableFuture.completedFuture("Error: question must not be empty.");
        }
        List<String> choices = normalizeOptions(options);
        String text = question.trim();
        if (text.length() > QUESTION_MAX) {
            text = text.substring(0, QUESTION_MAX) + "…";
        }
        final String ask = text;
        try {
            CompletableFuture<String> answered = handler.ask(ask, choices);
            if (answered == null) {
                return CompletableFuture.completedFuture(NO_ANSWER);
            }
            return answered
                    .orTimeout(ANSWER_WAIT_MINUTES, TimeUnit.MINUTES)
                    .thenApply(answer -> render(answer, ask))
                    .exceptionally(cause -> {
                        log.info("反问未拿到答案: {}", ask, cause);
                        return NO_ANSWER;
                    });
        } catch (RuntimeException e) {
            log.warn("反问通道不可用，按无人作答处理: {}", ask, e);
            return CompletableFuture.completedFuture(NO_ANSWER);
        }
    }

    /** 答案回给模型的文本：空答与「你来判断」都转成可执行的指引。 */
    private static String render(String answer, String ask) {
        if (answer == null || answer.isBlank()) {
            return NO_ANSWER;
        }
        String text = answer.trim();
        if (AskUserHandler.YOU_DECIDE.equals(text)) {
            return "The user asked you to decide. Pick the most reasonable reading of \"" + ask
                    + "\", say which option you chose and why, and continue.";
        }
        return "User answer: " + text;
    }

    /** 选项清洗：去空白、去重、限长，超上限只留前几条（模型爱堆十个选项）。 */
    private static List<String> normalizeOptions(List<String> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String o : options) {
            if (o == null) {
                continue;
            }
            String t = o.trim();
            if (!t.isEmpty()) {
                clean.add(t.length() > 80 ? t.substring(0, 80) + "…" : t);
            }
            if (clean.size() == MAX_OPTIONS) {
                break;
            }
        }
        return new ArrayList<>(clean);
    }
}
