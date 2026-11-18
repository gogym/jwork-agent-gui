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

package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.core.chat.ContextBreakdown;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 浮层分类缩放纯函数：独立粗估等比缩到占用条总量后，图例百分比之和才与标题占比自洽；
 * 不足 1% 的小类显示「&lt;1%」而非被四舍五入抹成 0。
 *
 * @author gogym
 */
class ContextPopoverScaleTest {

    @Test
    @DisplayName("粗估和等于总量：比例直取，小类保留 <1%")
    void scaleIdentityWhenRawMatchesEstimate() {
        ContextBreakdown bd = new ContextBreakdown(100, 200, 50, 3, 1000);
        ContextPopover.ScaledBreakdown s = ContextPopover.scale(bd, 100, 1450, 10000);
        assertArrayEquals(new double[]{0.01, 0.02, 0.005, 0.11}, s.fractions(), 1e-9);
        assertArrayEquals(new String[]{"1%", "2%", "<1%", "11%"}, s.percents());
    }

    @Test
    @DisplayName("真实回报小于粗估和：等比缩小，各类占比同步收敛")
    void scaleDownToRealReport() {
        ContextBreakdown bd = new ContextBreakdown(100, 200, 50, 3, 1000);
        ContextPopover.ScaledBreakdown s = ContextPopover.scale(bd, 0, 725, 10000);
        // raw=1350，k=725/1350：各类 = 粗估×k/上限
        double f0 = 100 * (725.0 / 1350) / 10000;
        assertArrayEquals(new double[]{f0, 2 * f0, 0.5 * f0, 10 * f0}, s.fractions(), 1e-9);
        assertArrayEquals(new String[]{"<1%", "1%", "<1%", "5%"}, s.percents());
        double sum = 0;
        for (double f : s.fractions()) {
            sum += f;
        }
        assertEquals(0.0725, sum, 1e-9, "分段之和 = 总量/上限");
    }

    @Test
    @DisplayName("零上限/全零估算：不除零，全 0%")
    void zeroLimitAndEmptyBreakdown() {
        ContextPopover.ScaledBreakdown s = ContextPopover.scale(new ContextBreakdown(0, 0, 0, 0, 0), 0, 0, 0);
        assertArrayEquals(new double[]{0, 0, 0, 0}, s.fractions(), 0);
        assertArrayEquals(new String[]{"0%", "0%", "0%", "0%"}, s.percents());
    }

    @Test
    @DisplayName("百分比文案边界：0 / 不足1 / 取整 / 进位")
    void formatPercentBoundaries() {
        assertEquals("0%", ContextPopover.formatPercent(0));
        assertEquals("0%", ContextPopover.formatPercent(-3));
        assertEquals("<1%", ContextPopover.formatPercent(0.9));
        assertEquals("1%", ContextPopover.formatPercent(1));
        assertEquals("42%", ContextPopover.formatPercent(42.4));
        assertEquals("100%", ContextPopover.formatPercent(99.6));
    }
}