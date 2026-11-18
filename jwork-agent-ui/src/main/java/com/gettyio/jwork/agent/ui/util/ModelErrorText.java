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

import com.gettyio.jwork.agent.core.model.ModelRetryPolicy;
import com.gettyio.jwork.agent.ui.i18n.I18n;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * 模型调用失败的人话翻译：原始异常常是 reactor 包装语（"Retries exhausted: 2/2"）或服务商整段错误
 * JSON，直接贴给用户既看不懂也不知道下一步做什么。这里沿 cause 链按特征归类，译成可操作指引
 * （预算 / 鉴权 / 模型 / 超时 / 网络）；都不命中才回退根因原文（截断防半屏 JSON）。
 * <p>对话失败气泡、对比列、快速问答与手动压缩失败共用同一口径。</p>
 *
 * @author gogym
 */
public final class ModelErrorText {

    /** cause 链遍历上限：防异常自引用成环。 */
    private static final int MAX_CAUSE_DEPTH = 16;
    /** 回退根因原文的截断长度：够定位问题，又不至于半屏错误 JSON。 */
    private static final int MAX_RAW_CHARS = 160;

    private ModelErrorText() {
    }

    /** 失败原因 → 用户可读指引；null 与无信息异常回退通用提示。 */
    public static String describe(Throwable error) {
        if (error == null) {
            return I18n.t("model.error.unknown");
        }
        if (ModelRetryPolicy.isPermanentQuotaError(error)) {
            return I18n.t("model.error.budget");
        }
        String joined = joinedMessages(error);
        if (containsAny(joined, "status 401", "status 403", "invalid_api_key", "invalid api key",
                "incorrect api key", "unauthorized", "authentication")) {
            return I18n.t("model.error.auth");
        }
        if (containsAny(joined, "status 404", "model_not_found", "model not found", "model does not exist")) {
            return I18n.t("model.error.model");
        }
        if (hasCause(error, TimeoutException.class) || containsAny(joined, "timeout", "timed out")) {
            return I18n.t("model.error.timeout");
        }
        if (hasCause(error, UnknownHostException.class) || hasCause(error, ConnectException.class)
                || containsAny(joined, "connection refused", "connection reset",
                "failed to connect", "network is unreachable")) {
            return I18n.t("model.error.network");
        }
        String root = rootMessage(error);
        if (root.isBlank() || root.toLowerCase(Locale.ROOT).startsWith("retries exhausted")) {
            return I18n.t("model.error.unknown");
        }
        return root.length() <= MAX_RAW_CHARS ? root : root.substring(0, MAX_RAW_CHARS) + "…";
    }

    /** 全链消息小写拼接：特征匹配不用逐层写循环。 */
    private static String joinedMessages(Throwable error) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (Throwable t = error; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage().toLowerCase(Locale.ROOT)).append(" | ");
            }
            Throwable cause = t.getCause();
            if (cause == t) {
                break;
            }
            t = cause;
        }
        return sb.toString();
    }

    /** 最深一层非空消息：包装语（如重试耗尽）之下真正的原因。 */
    private static String rootMessage(Throwable error) {
        String root = "";
        int depth = 0;
        for (Throwable t = error; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                root = t.getMessage();
            }
            Throwable cause = t.getCause();
            if (cause == t) {
                break;
            }
            t = cause;
        }
        return root;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(t)) {
                return true;
            }
            Throwable cause = t.getCause();
            if (cause == t) {
                break;
            }
            t = cause;
        }
        return false;
    }

    private static boolean containsAny(String joined, String... markers) {
        for (String marker : markers) {
            if (joined.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}