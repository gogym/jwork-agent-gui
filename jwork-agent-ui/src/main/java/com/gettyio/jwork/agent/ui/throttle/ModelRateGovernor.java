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

package com.gettyio.jwork.agent.ui.throttle;

import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.entity.ModelConfig;
import com.gettyio.jwork.agent.domain.enums.TimeRange;
import com.gettyio.jwork.agent.ui.i18n.I18n;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 模型速率治理：按 {@code models.rate_limit_rpm}/{@code rate_limit_tpm} 做发送节流与配额提醒。
 * <p>以模型编码为键维护滚动 60 秒窗口：{@code recordRequest} 记一次请求、{@code recordTokens} 在
 * 一轮回报后累加其 token。发送前 {@link #blockedReason} 判断是否已超 RPM/TPM，超则给出「请 N 秒后再试」
 * 的本地化文案，交由发送流程弹窗拦下。速率未配置（列空或 ≤0）的模型一律放行。</p>
 * <p>方法全部同步：请求记录在 FX 线程，token 回报也在 FX 线程回调（{@code showUsage}），
 * 但仍统一加锁以防未来跨线程调用。</p>
 *
 * @author gogym
 */
public class ModelRateGovernor {

    /** 滚动窗口长度：一分钟。 */
    private static final long WINDOW_MS = 60_000L;

    /** 应用级设置键：月度用量预算上限（元），空或 ≤0 表示不启用告警。 */
    public static final String KEY_MONTHLY_BUDGET = "usage.monthlyBudget";

    private final JworkDatabase db;
    /** 每模型最近请求时刻（毫秒），升序入队、超出窗口即出队。 */
    private final Map<String, Deque<Long>> requestTimes = new HashMap<>();
    /** 每模型最近 token 回报：{@code {时刻毫秒, token 数}}，窗口内求和判断 TPM。 */
    private final Map<String, Deque<long[]>> tokenWindows = new HashMap<>();
    /** 月预算告警当日已提醒标记：一天最多提醒一次，跨月自然重置。 */
    private LocalDate lastBudgetWarnDate;

    public ModelRateGovernor(JworkDatabase db) {
        this.db = db;
    }

    /**
     * 发送前的速率体检：命中 RPM 或 TPM 上限时返回本地化提示（含建议等待秒数），否则返回 {@code null} 表示放行。
     * <p>{@code modelCode} 为空（跟随默认但未配置默认、或无法确定本轮模型）时一律放行。</p>
     */
    public synchronized String blockedReason(String modelCode) {
        if (modelCode == null || modelCode.isBlank()) {
            return null;
        }
        ModelConfig m = db.modelConfigDao().findByCode(modelCode).orElse(null);
        if (m == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        Integer rpm = m.getRateLimitRpm();
        if (rpm != null && rpm > 0) {
            Deque<Long> times = requestTimes.get(modelCode);
            int used = pruneRequests(modelCode, now);
            if (used >= rpm) {
                long retry = retrySeconds(times, now);
                return I18n.t("rate.limit.rpm", rpm, retry);
            }
        }
        Integer tpm = m.getRateLimitTpm();
        if (tpm != null && tpm > 0) {
            Deque<long[]> entries = tokenWindows.get(modelCode);
            long used = pruneTokens(modelCode, now);
            if (used >= tpm) {
                long retry = retrySecondsTokens(entries, now);
                return I18n.t("rate.limit.tpm", tpm, retry);
            }
        }
        return null;
    }

    /**
     * 月度预算告警：设置键 {@link #KEY_MONTHLY_BUDGET} 配了正数且当月估算费用（JOIN models 单价）
     * 已达上限时，返回本地化提醒文案；否则返回 {@code empty}。同一天只提醒一次（内存去重，
     * 重启后最多再提醒一次），预算未配置或未超时零开销返回。
     */
    public synchronized Optional<String> budgetWarningIfExceeded() {
        double budget;
        try {
            budget = db.settingDao().getValue(KEY_MONTHLY_BUDGET)
                    .filter(s -> !s.isBlank())
                    .map(Double::parseDouble)
                    .orElse(0d);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (budget <= 0) {
            return Optional.empty();
        }
        double spent = db.conversationDao().aggregateGlobal(TimeRange.THIS_MONTH).estCost();
        if (spent < budget) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.now();
        if (today.equals(lastBudgetWarnDate)) {
            return Optional.empty();
        }
        lastBudgetWarnDate = today;
        return Optional.of(I18n.t("usage.budget.exceeded",
                String.format("%.2f", spent), String.format("%.2f", budget)));
    }

    /** 记一次发送（RPM 计数），发送确认后调用。 */
    public synchronized void recordRequest(String modelCode) {
        if (modelCode == null || modelCode.isBlank()) {
            return;
        }
        requestTimes.computeIfAbsent(modelCode, k -> new ArrayDeque<>()).addLast(System.currentTimeMillis());
    }

    /** 回报一轮 token 用量（TPM 计数），仅当有实际 token 时入窗。 */
    public synchronized void recordTokens(String modelCode, long tokens) {
        if (modelCode == null || modelCode.isBlank() || tokens <= 0) {
            return;
        }
        tokenWindows.computeIfAbsent(modelCode, k -> new ArrayDeque<>())
                .addLast(new long[]{System.currentTimeMillis(), tokens});
    }

    /** 出队窗口外的请求时刻，返回窗口内剩余计数。 */
    private int pruneRequests(String modelCode, long now) {
        Deque<Long> times = requestTimes.get(modelCode);
        if (times == null) {
            return 0;
        }
        while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MS) {
            times.pollFirst();
        }
        return times.size();
    }

    /** 出队窗口外的 token 记录，返回窗口内 token 合计。 */
    private long pruneTokens(String modelCode, long now) {
        Deque<long[]> entries = tokenWindows.get(modelCode);
        if (entries == null) {
            return 0;
        }
        long sum = 0;
        while (!entries.isEmpty()) {
            long[] head = entries.peekFirst();
            if (now - head[0] > WINDOW_MS) {
                entries.pollFirst();
            } else {
                break;
            }
        }
        for (long[] e : entries) {
            sum += e[1];
        }
        return sum;
    }

    /** 建议等待秒数：以窗口内最早一条的到期时刻估算，至少 1 秒。 */
    private static long retrySeconds(Deque<Long> times, long now) {
        Long oldest = times == null ? null : times.peekFirst();
        if (oldest == null) {
            return 1;
        }
        return Math.max(1, (oldest + WINDOW_MS - now + 999) / 1000);
    }

    private static long retrySecondsTokens(Deque<long[]> entries, long now) {
        long[] oldest = entries == null ? null : entries.peekFirst();
        if (oldest == null) {
            return 1;
        }
        return Math.max(1, (oldest[0] + WINDOW_MS - now + 999) / 1000);
    }
}
