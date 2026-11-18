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

package com.gettyio.jwork.agent.tools.guard;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 单个检查环节产出的决策结果（含理由，供 UI 展示风险说明）
 *
 * <p>{@code floored} 表示这条结论处在<b>「自动」审批档的下限</b>之内：档位开关不得替用户放过它，
 * 只有用户本人的决策（本会话允许／总是允许／逐次确认）才能越过。硬拒绝本身就是最下限，
 * 无需该标记。</p>
 *
 * @author gogym
 */
@Getter
@Accessors(fluent = true)
public class GuardResult {

    private final GuardDecision decision;
    private final String reason;
    /** 是否属于「自动档不可越过」的高危类（仅 ASK 结论需要置真）。 */
    private final boolean floored;

    private GuardResult(GuardDecision decision, String reason, boolean floored) {
        this.decision = decision;
        this.reason = reason;
        this.floored = floored;
    }

    public static GuardResult allow(String reason) {
        return new GuardResult(GuardDecision.ALLOW, reason, false);
    }

    public static GuardResult deny(String reason) {
        return new GuardResult(GuardDecision.DENY, reason, false);
    }

    public static GuardResult ask(String reason) {
        return new GuardResult(GuardDecision.ASK, reason, false);
    }

    /** 需要人工过目的询问：自动审批档不得直接放过（黑名单命中的 CRITICAL 规则亦用此档）。 */
    public static GuardResult askFloored(String reason) {
        return new GuardResult(GuardDecision.ASK, reason, true);
    }
}
