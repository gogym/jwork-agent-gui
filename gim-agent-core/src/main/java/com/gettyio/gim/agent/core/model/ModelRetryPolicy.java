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

package com.gettyio.gim.agent.core.model;

import io.agentscope.core.model.ExecutionConfig;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * 模型请求重试策略：沿用框架默认可重试白名单（限流 / 超时 / IO 抖动），但把账户预算 / 额度用尽、
 * 欠费这类<b>永久性错误</b>排除在外——这类 429 重试不会恢复，只会空转烧请求次数，且每次重试都拖一条
 * 完整 WARN 堆栈：手动压缩含「记忆沉淀 + 摘要」两段 LLM 调用，预算触顶时日志会被重试堆栈刷满整屏。
 * <p>经各协议 ChatModel 的默认 options 注入（{@code executionConfig}），对话与压缩同一条重试口径。</p>
 *
 * @author gogym
 */
public final class ModelRetryPolicy {

    /** 永久性额度错误特征（服务商错误码 / 文案，小写匹配）：命中即不重试。 */
    private static final String[] PERMANENT_MARKERS = {
            "budgetlimitexceeded", "budget limit", "insufficient_quota", "insufficient quota",
            "arrearage", "overdue-payment",
    };
    /** cause 链遍历上限：防异常自引用成环。 */
    private static final int MAX_CAUSE_DEPTH = 16;

    private ModelRetryPolicy() {
    }

    /** 是否账户预算/额度用尽或欠费类永久错误（沿 cause 链匹配错误码与文案）。 */
    public static boolean isPermanentQuotaError(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                for (String marker : PERMANENT_MARKERS) {
                    if (lower.contains(marker)) {
                        return true;
                    }
                }
            }
            Throwable cause = t.getCause();
            if (cause == t) {
                break;
            }
            t = cause;
        }
        return false;
    }

    /** 默认执行配置：超时/退避/次数沿用框架默认，仅把永久额度错误剔出重试白名单。 */
    public static ExecutionConfig executionConfig() {
        ExecutionConfig d = ExecutionConfig.MODEL_DEFAULTS;
        return ExecutionConfig.builder()
                .timeout(d.getTimeout())
                .maxAttempts(d.getMaxAttempts())
                .initialBackoff(d.getInitialBackoff())
                .maxBackoff(d.getMaxBackoff())
                .backoffMultiplier(d.getBackoffMultiplier())
                .retryOn(retryableButNotPermanent())
                .build();
    }

    private static Predicate<Throwable> retryableButNotPermanent() {
        return t -> ExecutionConfig.RETRYABLE_ERRORS.test(t) && !isPermanentQuotaError(t);
    }
}