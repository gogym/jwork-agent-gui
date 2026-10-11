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

package com.gettyio.gim.agent.tools.guard;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 单个检查环节产出的决策结果（含理由，供 UI 展示风险说明）
 *
 * @author gogym
 */
@Getter
@Accessors(fluent = true)
public class GuardResult {

    private final GuardDecision decision;
    private final String reason;

    private GuardResult(GuardDecision decision, String reason) {
        this.decision = decision;
        this.reason = reason;
    }

    public static GuardResult allow(String reason) {
        return new GuardResult(GuardDecision.ALLOW, reason);
    }

    public static GuardResult deny(String reason) {
        return new GuardResult(GuardDecision.DENY, reason);
    }

    public static GuardResult ask(String reason) {
        return new GuardResult(GuardDecision.ASK, reason);
    }
}
