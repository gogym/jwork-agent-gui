package com.gettyio.gim.agent.tools.guard;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 单个检查环节产出的决策结果（含理由，供 UI 展示风险说明）
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
