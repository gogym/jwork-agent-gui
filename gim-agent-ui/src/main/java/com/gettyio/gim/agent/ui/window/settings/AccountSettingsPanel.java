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

package com.gettyio.gim.agent.ui.window.settings;

import com.gettyio.gim.agent.domain.security.LocalAuthService;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.window.dialog.GimDialog;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * 账号设置子面板（独立菜单页）：当前账号、免登录有效期、修改密码与退出登录。
 * <p>自 {@link SecuritySettingsPanel} 的「账号与登录」页内 Tab 提升为一级菜单页——账号属于
 * 身份管理，与工具/文件安全管控不同类。依赖 {@link LocalAuthService} 与宿主弹窗归属/提示/退登回调。</p>
 *
 * @author gogym
 */
final class AccountSettingsPanel {

    private final SettingsWindow host;
    private final LocalAuthService localAuthService;

    AccountSettingsPanel(SettingsWindow host, LocalAuthService localAuthService) {
        this.host = host;
        this.localAuthService = localAuthService;
    }

    VBox build() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.account"), I18n.t("settings.account.subtitle"));

        String name = localAuthService.username();
        Label accountValue = SettingsLayout.valueLabel(name == null || name.isBlank() ? "-" : name);

        Spinner<Integer> days = new Spinner<>(LocalAuthService.MIN_SESSION_DAYS,
                LocalAuthService.MAX_SESSION_DAYS, localAuthService.sessionDays());
        days.setEditable(true);
        days.setMaxWidth(120);
        days.valueProperty().addListener((obs, old, val) -> {
            if (val != null) {
                localAuthService.setSessionDays(val);
            }
        });

        Button change = SettingsLayout.newButton(I18n.t("settings.account.change"), this::handleChangePassword);
        change.getStyleClass().add("link-btn");
        Button signOut = SettingsLayout.newButton(I18n.t("settings.account.signout"), this::handleSignOut);
        signOut.getStyleClass().add("link-btn");

        box.getChildren().add(SettingsLayout.groupCard(I18n.t("settings.account.group"), List.of(
                SettingsLayout.settingRow(I18n.t("settings.account.current"), null, accountValue),
                SettingsLayout.settingRow(I18n.t("settings.account.session"),
                        I18n.t("settings.account.session.desc"), days),
                SettingsLayout.settingRow(I18n.t("settings.account.change"),
                        I18n.t("settings.account.change.desc"), change),
                SettingsLayout.settingRow(I18n.t("settings.account.signout"),
                        I18n.t("settings.account.signout.desc"), signOut))));
        return box;
    }

    /** 修改密码：旧密码 + 新密码 + 确认；成功后服务清除会话，下次启动/唤回主窗需重新登录。 */
    private void handleChangePassword() {
        GimDialog.Shell shell = GimDialog.shell(host.hostWindow(),
                I18n.t("settings.account.change.title"));
        PasswordField oldField = new PasswordField();
        PasswordField newField = new PasswordField();
        PasswordField confirmField = new PasswordField();
        VBox form = new VBox(10,
                SettingsLayout.labeledField(I18n.t("settings.account.change.old"), oldField),
                SettingsLayout.labeledField(I18n.t("settings.account.change.new"), newField),
                SettingsLayout.labeledField(I18n.t("settings.account.change.confirm"), confirmField));
        form.setPrefWidth(440);

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        Button save = new Button(I18n.t("common.save"));
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            if (newField.getText() == null || newField.getText().length() < LocalAuthService.MIN_PASSWORD_LENGTH) {
                host.notify("settings.account.change.title",
                        I18n.t("auth.error.password.tooShort", LocalAuthService.MIN_PASSWORD_LENGTH));
                return;
            }
            if (!newField.getText().equals(confirmField.getText())) {
                host.notify("settings.account.change.title", I18n.t("auth.error.password.mismatch"));
                return;
            }
            if (!localAuthService.changePassword(oldField.getText(), newField.getText())) {
                host.notify("settings.account.change.title", I18n.t("auth.error.password.wrong"));
                return;
            }
            shell.stage().close();
            host.notify("settings.account.change.title", I18n.t("settings.account.change.done"));
        });
        HBox btns = new HBox(8, cancel, save);
        btns.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        shell.body().getChildren().addAll(form, btns);
        shell.showAndWait();
    }

    /** 退出登录：确认后清除会话并在窗口内呈现登录页，认证通过回到主界面。 */
    private void handleSignOut() {
        if (!GimDialog.confirm(host.hostWindow(), I18n.t("settings.account.signout"),
                I18n.t("settings.account.signout.confirm"))) {
            return;
        }
        host.signOutApp();
    }
}
