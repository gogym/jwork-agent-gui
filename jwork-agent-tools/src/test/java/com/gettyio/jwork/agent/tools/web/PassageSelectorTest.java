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

package com.gettyio.jwork.agent.tools.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PassageSelector} 选段单测：验证中文切片能命中、输出按原文顺序、预算守得住，
 * 以及「没命中就回空选择」——宁可让调用方降级到正文开头，也不凑一段不相干内容冒充答案。
 *
 * @author gogym
 */
class PassageSelectorTest {

    private static final String TEXT = """
            # 支付订单退款规则
            退款订单以支付平台回执为准，T+1 到账。
            用券订单的退款需扣除优惠券抵扣部分后再计算实际退款金额。
            本节描述对账口径：两边订单条数差值超过 5 条时需要人工复核。
            历史遗留问题：2025 年以前的订单不在本次统计范围内。
            联系方式请见客服页面说明。
            """;

    @Test
    @DisplayName("中文 prompt 靠二字切片命中相关段落，无关段落不进结果")
    void picksRelevantChinesePassages() {
        PassageSelector.Selection s = PassageSelector.select(TEXT, "退款订单的统计口径", 4000);

        assertTrue(s.text().contains("退款订单以支付平台回执为准"), s.text());
        assertTrue(s.text().contains("对账口径"), s.text());
        assertFalse(s.text().contains("联系方式请见客服页面"), s.text());
        assertEquals(6, s.total());
        assertTrue(s.matched() >= 2, "matched=" + s.matched());
    }

    @Test
    @DisplayName("输出按原文顺序，不受得分排序影响")
    void keepsDocumentOrder() {
        PassageSelector.Selection s = PassageSelector.select(TEXT, "退款 订单 联系方式 客服", 4000);

        int refund = s.text().indexOf("退款订单以支付平台回执为准");
        int contact = s.text().indexOf("联系方式请见客服页面说明");
        assertTrue(refund >= 0, "未选中退款段落：\n" + s.text());
        assertTrue(contact > refund, "顺序被得分打乱了：\n" + s.text());
    }

    @Test
    @DisplayName("预算上限是硬约束：装不下的段落被截断并留省略号")
    void respectsBudget() {
        PassageSelector.Selection s = PassageSelector.select(TEXT, "退款 订单 统计 口径 联系 客服", 25);

        assertTrue(s.text().length() <= 25, "len=" + s.text().length() + "\n" + s.text());
        assertTrue(s.text().contains("…"), s.text());
    }

    @Test
    @DisplayName("一个词都没命中时返回空选择，交调用方降级")
    void noMatchReturnsEmpty() {
        PassageSelector.Selection s = PassageSelector.select(TEXT, "机器学习 神经网络", 4000);

        assertEquals("", s.text());
        assertEquals(0, s.matched());
    }

    @Test
    @DisplayName("纯填充词与空输入不参与计分")
    void stopWordsOnlyPromptMatchesNothing() {
        assertEquals("", PassageSelector.select(TEXT, "how about this", 4000).text());
        assertEquals("", PassageSelector.select(TEXT, "   ", 4000).text());
        assertEquals("", PassageSelector.select(TEXT, null, 4000).text());
        assertEquals("", PassageSelector.select(null, "退款", 4000).text());
    }

    @Test
    @DisplayName("单字命中不够入选：避免关键词刷屏把无关段落带回上下文")
    void weakSingleHitIsFiltered() {
        String text = "客服页面提供在线咨询与帮助\n退款订单统计口径见支付平台回执说明文档";
        PassageSelector.Selection s = PassageSelector.select(text, "退款订单", 4000);

        assertEquals(1, s.matched(), s.text());
        assertTrue(s.text().contains("退款订单统计口径"), s.text());
        assertFalse(s.text().contains("在线咨询"), s.text());
    }
}
