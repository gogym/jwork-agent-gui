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

package com.gettyio.jwork.agent.tools.hitl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AskUserTool} 单测：入参清洗、选项截断、拿不到答案时的兜底口径。
 * <p>反问工具最怕的是「问不出去也回不来」，所以没答、抛异常、点「由你判断」三条退路都验到。</p>
 *
 * @author gogym
 */
class AskUserToolTest {

    /** 记下提问内容并立即回填预设答案的假处理器。 */
    private static final class FakeHandler implements AskUserHandler {
        private String question;
        private List<String> options = List.of();
        private int calls;
        private String answer;
        private RuntimeException boom;

        @Override
        public CompletableFuture<String> ask(String question, List<String> options) {
            calls++;
            this.question = question;
            this.options = List.copyOf(options);
            if (boom != null) {
                throw boom;
            }
            return CompletableFuture.completedFuture(answer);
        }
    }

    @Test
    @DisplayName("空问题直接拒答，不打扰用户")
    void blankQuestionIsRejected() {
        FakeHandler handler = new FakeHandler();
        AskUserTool tool = new AskUserTool(handler);

        assertTrue(tool.askUser(null, "  ", null).join().startsWith("Error:"));
        assertTrue(tool.askUser(null, null, null).join().startsWith("Error:"));
        assertEquals(0, handler.calls);
    }

    @Test
    @DisplayName("选项去空白去重、最多留四条")
    void optionsAreCleanedAndCapped() {
        FakeHandler handler = new FakeHandler();
        handler.answer = "9月";
        AskUserTool tool = new AskUserTool(handler);

        tool.askUser(null, "统计哪个月？",
                Arrays.asList(" 9月 ", "10月", "", null, "9月", "11月", "12月"))
                .join();

        assertEquals(List.of("9月", "10月", "11月", "12月"), handler.options);
    }

    @Test
    @DisplayName("用户答案原样回喂模型")
    void answerIsPassedThrough() {
        FakeHandler handler = new FakeHandler();
        handler.answer = " 限定在用券订单内 ";

        String result = new AskUserTool(handler).askUser(null, "口径怎么定？", List.of("全部", "用券")).join();

        assertEquals("User answer: 限定在用券订单内", result);
    }

    @Test
    @DisplayName("「由你判断」换成可执行的拍板指引，并带上原问题")
    void youDecideBecomesInstruction() {
        FakeHandler handler = new FakeHandler();
        handler.answer = AskUserHandler.YOU_DECIDE;

        String result = new AskUserTool(handler).askUser(null, "要不要剔除购物金订单？", List.of("剔除", "保留")).join();

        assertTrue(result.contains("asked you to decide"));
        assertTrue(result.contains("要不要剔除购物金订单？"));
    }

    @Test
    @DisplayName("没人回答：回「自己判断并说明假设」，不挂这一轮")
    void missingAnswerFallsBackToNoAnswer() {
        FakeHandler handler = new FakeHandler();
        AskUserTool tool = new AskUserTool(handler);

        assertEquals(AskUserTool.NO_ANSWER, tool.askUser(null, "路径在哪？", List.of()).join());

        handler.answer = "   ";
        assertEquals(AskUserTool.NO_ANSWER, tool.askUser(null, "路径在哪？", List.of()).join());
    }

    @Test
    @DisplayName("反问通道抛异常按无人作答处理，绝不把异常抛回框架")
    void handlerFailureBecomesNoAnswer() {
        FakeHandler handler = new FakeHandler();
        handler.boom = new IllegalStateException("主窗口已关闭");

        String result = new AskUserTool(handler).askUser(null, "选哪个？", List.of("A", "B")).join();

        assertEquals(AskUserTool.NO_ANSWER, result);
    }

    @Test
    @DisplayName("长问题截断后再弹，弹窗不是一整段文章")
    void longQuestionIsTruncated() {
        FakeHandler handler = new FakeHandler();
        handler.answer = "好";

        new AskUserTool(handler).askUser(null, "问".repeat(400), List.of()).join();

        assertEquals(301, handler.question.length());
        assertTrue(handler.question.endsWith("…"));
        assertFalse(handler.question.endsWith("问问"));
    }
}
