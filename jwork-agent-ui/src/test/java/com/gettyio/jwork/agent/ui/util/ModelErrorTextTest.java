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

package com.gettyio.jwork.agent.ui.util;

import com.gettyio.jwork.agent.ui.i18n.I18n;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 模型失败翻译：按特征归类到可操作指引，无信息异常回退通用提示，根因原文截断。 */
class ModelErrorTextTest {

    @Test
    void budgetCauseChainMapsToGuidance() {
        Throwable budget = new RuntimeException("HTTP request failed with status 429 | "
                + "{\"error\":{\"code\":\"BudgetLimitExceeded\",\"message\":\"budget limit reached\"}}");
        Throwable wrapped = new IllegalStateException("Retries exhausted: 2/2", budget);
        assertEquals(I18n.t("model.error.budget"), ModelErrorText.describe(wrapped));
    }

    @Test
    void authModelTimeoutNetworkBranches() {
        assertEquals(I18n.t("model.error.auth"), ModelErrorText.describe(
                new RuntimeException("HTTP request failed with status 401 | invalid_api_key")));
        assertEquals(I18n.t("model.error.model"), ModelErrorText.describe(
                new RuntimeException("HTTP request failed with status 404 | model_not_found")));
        assertEquals(I18n.t("model.error.timeout"), ModelErrorText.describe(
                new IllegalStateException("wrap", new TimeoutException("request timed out"))));
        assertEquals(I18n.t("model.error.network"), ModelErrorText.describe(
                new IllegalStateException("wrap", new UnknownHostException("maas.example.com"))));
        assertEquals(I18n.t("model.error.network"), ModelErrorText.describe(
                new IllegalStateException("wrap", new ConnectException("Connection refused"))));
    }

    @Test
    void noInformationFallsBackToGeneric() {
        assertEquals(I18n.t("model.error.unknown"), ModelErrorText.describe(null));
        assertEquals(I18n.t("model.error.unknown"),
                ModelErrorText.describe(new IllegalStateException("Retries exhausted: 2/2")));
        assertEquals(I18n.t("model.error.unknown"), ModelErrorText.describe(new RuntimeException()));
    }

    @Test
    void rawRootMessageTruncated() {
        String longMessage = "x".repeat(300);
        String described = ModelErrorText.describe(new IllegalStateException(longMessage));
        assertEquals(161, described.length());
        assertEquals("custom hint", ModelErrorText.describe(new IllegalArgumentException("custom hint")));
    }
}