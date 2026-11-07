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

package com.gettyio.gim.agent.core.chat;

/**
 * 工具确认的裁决结果：放行与否之外还带<b>拒绝理由</b>。
 * <p>理由会写回模型上下文（框架用它替换默认的 {@code "Permission denied by user"}），
 * 模型才知道该换路子而不是原样重试；只回一个布尔值等于让它对着黑名单反复撞。</p>
 *
 * @param allowed 是否放行
 * @param reason  拒绝理由（放行时为 null），面向模型的可执行说明
 * @author gogym
 */
public record ConfirmOutcome(boolean allowed, String reason) {

    /** 拒绝理由兜底文案：拦截链与用户都没给出原因时使用。 */
    private static final String DEFAULT_DENY_REASON =
            "用户拒绝了该操作。请不要重复同样的调用，可改用更安全的替代做法，或向用户说明并询问。";

    public static ConfirmOutcome allow() {
        return new ConfirmOutcome(true, null);
    }

    public static ConfirmOutcome deny(String reason) {
        return new ConfirmOutcome(false, reason == null || reason.isBlank()
                ? DEFAULT_DENY_REASON : reason + "。" + DEFAULT_DENY_REASON);
    }
}
