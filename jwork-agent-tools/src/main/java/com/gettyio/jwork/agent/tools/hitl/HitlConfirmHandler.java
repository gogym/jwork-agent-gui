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

package com.gettyio.jwork.agent.tools.hitl;

import com.gettyio.jwork.agent.core.chat.ToolConfirmRequest;
import com.gettyio.jwork.agent.domain.enums.PermissionDecision;

import java.util.concurrent.CompletableFuture;

/**
 * 人工确认处理器：由 UI 层实现，弹出确认框并异步回填用户决策。
 *
 * @author gogym
 */
public interface HitlConfirmHandler {

    /**
     * 发起确认，返回异步决策（允许/拒绝/总是允许）
     */
    CompletableFuture<PermissionDecision> requestConfirm(ToolConfirmRequest request);
}
