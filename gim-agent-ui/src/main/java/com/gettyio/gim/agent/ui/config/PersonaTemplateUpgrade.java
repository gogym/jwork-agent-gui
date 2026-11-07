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

package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.core.WorkspaceProvisioner;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.window.dialog.GimDialog;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 内置人格（AGENTS.md）模板升级提示：新版文案随程序发布，但老工作区的 AGENTS.md 只在缺失时播种、
 * 从不覆盖，因此「产物交付 / 结果核对 / 表格附件 / 覆盖写」这些新总则到不了已经在用的工作区。
 * <p>启动装配后延后一拍检查：仅当文件仍是旧版内置原文（{@link WorkspaceProvisioner#agentsMdState()}
 * 为 UPGRADABLE，用户一个字没动过）才弹一次一键升级；用户改写过的人格永不提示、永不覆盖。</p>
 * <p>「以后再说」与关窗同样按模板版本记档（{@link #KEY_ACKED_VERSION}），同一版本只打扰一次，
 * 后续再发新版才会重新提示。</p>
 *
 * @author gogym
 */
public final class PersonaTemplateUpgrade {

    private static final Logger log = LoggerFactory.getLogger(PersonaTemplateUpgrade.class);

    /** 应用级设置键：已提示/已升级到的模板版本，同一版本不再重复打扰。 */
    static final String KEY_ACKED_VERSION = "app.persona.templateVersion";

    private final WorkspaceProvisioner provisioner;
    private final GimDatabase database;

    public PersonaTemplateUpgrade(WorkspaceProvisioner provisioner, GimDatabase database) {
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
        this.database = Objects.requireNonNull(database, "database");
    }

    /** 启动后延后一拍提示升级：主窗先渲染完成，弹窗不至于抢在首帧之前。 */
    public void maybePrompt(Window owner) {
        PauseTransition delay = new PauseTransition(Duration.seconds(1.5));
        delay.setOnFinished(e -> Platform.runLater(() -> prompt(owner)));
        delay.play();
    }

    /** 提示主体（FX 线程）；无需提示时静默返回。 */
    void prompt(Window owner) {
        if (provisioner.agentsMdState() != WorkspaceProvisioner.AgentsMdState.UPGRADABLE
                || ackedVersion() >= WorkspaceProvisioner.TEMPLATE_VERSION) {
            return;
        }
        int choice = GimDialog.choose(owner, I18n.t("persona.upgrade.title"), null,
                I18n.t("persona.upgrade.body"),
                I18n.t("persona.upgrade.action"), I18n.t("persona.upgrade.later"));
        if (choice != 0) {
            // 「以后再说」与直接关窗（-1）都记档：旧版默认人格不会自己变新，但同一个版本不该再问
            acknowledge();
            return;
        }
        if (provisioner.upgradeAgentsMd()) {
            acknowledge();
            GimDialog.message(owner, I18n.t("persona.upgrade.title"), null, I18n.t("persona.upgrade.done"));
        } else {
            GimDialog.message(owner, I18n.t("persona.upgrade.title"), null,
                    I18n.t("persona.upgrade.failed"), GimDialog.Level.ERROR);
        }
    }

    private int ackedVersion() {
        try {
            String v = database.settingDao().getValue(KEY_ACKED_VERSION).orElse(null);
            return v == null ? 0 : Integer.parseInt(v.trim());
        } catch (RuntimeException e) {
            log.warn("读取人格模板提示记档失败，按未提示处理", e);
            return 0;
        }
    }

    private void acknowledge() {
        try {
            database.settingDao().upsert(KEY_ACKED_VERSION, String.valueOf(WorkspaceProvisioner.TEMPLATE_VERSION),
                    "内置人格模板已提示/已升级到的版本");
        } catch (RuntimeException e) {
            // 记档失败最多让同一个版本再问一次，不影响人格本身
            log.warn("写入人格模板提示记档失败", e);
        }
    }
}
