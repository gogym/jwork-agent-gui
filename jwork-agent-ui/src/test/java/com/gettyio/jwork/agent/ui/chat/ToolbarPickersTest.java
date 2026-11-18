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

package com.gettyio.jwork.agent.ui.chat;

import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.entity.ModelConfig;
import com.gettyio.jwork.agent.domain.entity.ModelProviderConfig;
import com.gettyio.jwork.agent.domain.persist.SqlSchemaInitializer;
import com.gettyio.jwork.agent.domain.persist.SqliteConnectionProvider;
import com.gettyio.jwork.agent.ui.config.ProviderAdminService;
import com.gettyio.jwork.agent.ui.config.SecurityAdminService;
import io.agentscope.core.permission.PermissionMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolbarPickers} 会话级选择单测：菜单选中经回调上报、按会话恢复模型与审批档、
 * 空值/失效模型回退全局默认、恢复路径不反写。按钮全 null（不触 FX），模型/审批配置落临时 SQLite。
 *
 * @author gogym
 */
class ToolbarPickersTest {

    @TempDir
    Path tempDir;

    private JworkDatabase db;
    private SecurityAdminService security;
    private ToolbarPickers pickers;

    @BeforeEach
    void setUp() {
        SqliteConnectionProvider cp = new SqliteConnectionProvider(tempDir.resolve("jwork.db"));
        new SqlSchemaInitializer(cp).initialize();
        db = new JworkDatabase(cp);
        security = new SecurityAdminService(db, null);
        ProviderAdminService providers = new ProviderAdminService(db, null, null, null, null);
        pickers = new ToolbarPickers(null, null, null, null, null, null, providers, security, null, null);
        ModelProviderConfig p = new ModelProviderConfig();
        p.setProviderCode("zhipu");
        p.setProviderName("智谱");
        p.setApiProtocol("openai");
        p.setEnabled(1);
        db.modelProviderDao().insert(p);
        ModelConfig m = new ModelConfig();
        m.setProviderCode("zhipu");
        m.setModelCode("glm-4.6");
        m.setModelName("GLM-4.6");
        m.setEnabled(1);
        m.setStatus(ProviderAdminService.MODEL_STATUS_VALID);
        db.modelConfigDao().insert(m);
    }

    @Test
    @DisplayName("按会话恢复：模型与审批档原样回到工具条")
    void restoreModelAndApproval() {
        pickers.applySessionPrefs("glm-4.6", "auto");

        assertEquals("zhipu", pickers.selectedProviderCode());
        assertEquals("glm-4.6", pickers.selectedModelCode());
        assertEquals(PermissionMode.ACCEPT_EDITS, pickers.approvalMode());
        assertTrue(pickers.approvalAutoApprove());
        assertEquals("auto", pickers.approvalKey());
    }

    @Test
    @DisplayName("恢复跟随默认 + 只读档：模型不覆盖、审批 EXPLORE")
    void restoreFollowDefaultAndReadonly() {
        pickers.applySessionPrefs(null, "readonly");

        assertNull(pickers.selectedModelCode());
        assertNull(pickers.selectedProviderCode());
        assertEquals(PermissionMode.EXPLORE, pickers.approvalMode());
        assertEquals("readonly", pickers.approvalKey());
    }

    @Test
    @DisplayName("会话里的模型已删/禁用：回退跟随默认，不指向打不通的模型")
    void restoreUnknownModelFallsBack() {
        pickers.applySessionPrefs("gone-model", "ask");

        assertNull(pickers.selectedModelCode());
        assertEquals("ask", pickers.approvalKey());
    }

    @Test
    @DisplayName("会话审批档为空（历史行）：回退设置「安全」页全局默认")
    void restoreBlankApprovalUsesGlobalDefault() {
        security.setApprovalDefault("auto");

        pickers.applySessionPrefs(null, null);
        assertTrue(pickers.approvalAutoApprove());

        pickers.applySessionPrefs(null, "  ");
        assertTrue(pickers.approvalAutoApprove());
    }

    @Test
    @DisplayName("菜单选中即上报：模型与审批档两次选择各通知宿主一次")
    void pickNotifiesSink() {
        List<ToolbarPickers.SessionPrefs> seen = new ArrayList<>();
        pickers.setSessionPrefsSink(seen::add);

        pickers.selectModel(pickers.modelOptions().get(1));
        assertEquals(1, seen.size());
        assertEquals("glm-4.6", seen.get(0).modelCode());
        assertEquals("ask", seen.get(0).approvalMode());

        pickers.selectApproval(pickers.approvalOptions().get(2));
        assertEquals(2, seen.size());
        assertEquals("glm-4.6", seen.get(1).modelCode());
        assertEquals("readonly", seen.get(1).approvalMode());
    }

    @Test
    @DisplayName("恢复路径不反写：切会话不该把读出来的值再写一遍库")
    void restoreDoesNotNotifySink() {
        List<ToolbarPickers.SessionPrefs> seen = new ArrayList<>();
        pickers.setSessionPrefsSink(seen::add);

        pickers.applySessionPrefs("glm-4.6", "auto");

        assertTrue(seen.isEmpty());
    }

    @Test
    @DisplayName("档位→持久化值：自动通过优先，只读独立，其余询问")
    void approvalKeyMapping() {
        assertEquals("auto", ToolbarPickers.approvalKey(PermissionMode.ACCEPT_EDITS, true));
        assertEquals("ask", ToolbarPickers.approvalKey(PermissionMode.ACCEPT_EDITS, false));
        assertEquals("readonly", ToolbarPickers.approvalKey(PermissionMode.EXPLORE, false));
    }

    @Test
    @DisplayName("模型匹配：空编码/无匹配返回 null（跟随默认），命中返回对应项")
    void matchModelOptionByCode() {
        List<ToolbarPickers.ModelOption> options = pickers.modelOptions();

        assertNull(ToolbarPickers.matchModelOption(options, null));
        assertNull(ToolbarPickers.matchModelOption(options, "  "));
        assertNull(ToolbarPickers.matchModelOption(options, "nope"));
        assertEquals("glm-4.6", ToolbarPickers.matchModelOption(options, "glm-4.6").modelCode());
    }
}