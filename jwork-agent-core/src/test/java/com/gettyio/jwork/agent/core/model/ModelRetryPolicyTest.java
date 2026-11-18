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

package com.gettyio.jwork.agent.core.model;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.extensions.model.openai.exception.RateLimitException;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 重试策略：永久额度错误沿 cause 链识别并剔出重试白名单，瞬时限流仍保留重试。 */
class ModelRetryPolicyTest {

    private static final String BUDGET_BODY =
            "HTTP transport error during streaming: HTTP request failed with status 429 | "
                    + "{\"error\":{\"message\":\"The budget limit configured by the customer has been reached.\","
                    + "\"type\":\"BudgetLimitExceeded\",\"code\":\"BudgetLimitExceeded\"}}";

    private static RateLimitException rateLimit(String message) {
        return new RateLimitException(message, "BudgetLimitExceeded", "{}");
    }

    @Test
    void permanentQuotaDetectedThroughCauseChain() {
        Throwable wrapped = new IllegalStateException("Retries exhausted: 2/2", rateLimit(BUDGET_BODY));
        assertTrue(ModelRetryPolicy.isPermanentQuotaError(wrapped));
        assertTrue(ModelRetryPolicy.isPermanentQuotaError(rateLimit(BUDGET_BODY)));
        assertTrue(ModelRetryPolicy.isPermanentQuotaError(new RuntimeException("insufficient_quota")));
        assertTrue(ModelRetryPolicy.isPermanentQuotaError(new RuntimeException("Arrearage: account overdue")));
        assertFalse(ModelRetryPolicy.isPermanentQuotaError(
                rateLimit("Requests rate limit exceeded, please try again later")));
        assertFalse(ModelRetryPolicy.isPermanentQuotaError(null));
    }

    @Test
    void retryWhitelistDropsPermanentButKeepsTransient() {
        RateLimitException transientLimit = rateLimit("Requests rate limit exceeded, please try again later");
        // 框架白名单本就视限流为可重试；若框架口径变化此断言同步暴露
        assertTrue(ExecutionConfig.RETRYABLE_ERRORS.test(transientLimit));
        Predicate<Throwable> retryOn = ModelRetryPolicy.executionConfig().getRetryOn();
        assertTrue(retryOn.test(transientLimit));
        assertFalse(retryOn.test(rateLimit(BUDGET_BODY)));
        assertFalse(retryOn.test(new IllegalStateException("Retries exhausted: 2/2", rateLimit(BUDGET_BODY))));
    }

    @Test
    void executionConfigInheritsFrameworkDefaults() {
        ExecutionConfig cfg = ModelRetryPolicy.executionConfig();
        assertEquals(ExecutionConfig.MODEL_DEFAULTS.getMaxAttempts(), cfg.getMaxAttempts());
        assertEquals(ExecutionConfig.MODEL_DEFAULTS.getTimeout(), cfg.getTimeout());
        assertEquals(ExecutionConfig.MODEL_DEFAULTS.getInitialBackoff(), cfg.getInitialBackoff());
    }
}