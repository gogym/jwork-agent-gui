package com.gettyio.gim.agent.tools.guard;

import com.gettyio.gim.agent.domain.dao.ToolPermissionRuleDao;
import com.gettyio.gim.agent.domain.entity.ToolPermissionRule;
import com.gettyio.gim.agent.domain.enums.PermissionDecision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 安全拦截责任链：按 order 依次执行策略；先查历史"总是允许"规则短路，
 * 首个非空策略结论即为最终决策，全部沉默则默认放行。
 */
public class GuardChain {

    private final List<ToolGuard> guards;
    private final ToolPermissionRuleDao ruleDao; // 可为 null（无持久化时）

    public GuardChain(List<ToolGuard> guards, ToolPermissionRuleDao ruleDao) {
        List<ToolGuard> sorted = new ArrayList<>(guards);
        sorted.sort(Comparator.comparingInt(ToolGuard::order));
        this.guards = sorted;
        this.ruleDao = ruleDao;
    }

    public GuardResult evaluate(GuardContext ctx) {
        // 1) 历史"总是允许"规则短路
        if (ruleDao != null) {
            Optional<ToolPermissionRule> whole = ruleDao.find(ctx.toolName(), null);
            if (whole.isPresent() && PermissionDecision.ALWAYS_ALLOW.name().equals(whole.get().getDecision())) {
                return GuardResult.allow("用户已设置总是允许该工具");
            }
        }

        // 2) 责任链首个非空结论
        for (ToolGuard guard : guards) {
            GuardResult result = guard.inspect(ctx);
            if (result != null) {
                return result;
            }
        }

        // 3) 默认放行
        return GuardResult.allow("无拦截规则命中");
    }

    /** 记录用户对某次 ASK 的决策，ALWAYS_ALLOW 持久化 */
    public void recordDecision(GuardContext ctx, PermissionDecision decision) {
        if (ruleDao == null || decision != PermissionDecision.ALWAYS_ALLOW) {
            return;
        }
        ruleDao.upsert(ctx.toolName(), null, decision.name());
    }
}
