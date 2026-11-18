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

import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.control.ToggleSwitch;
import com.gettyio.gim.agent.ui.desktop.GlobalHotkeyService;
import com.gettyio.gim.agent.ui.util.UiStyles;
import com.gettyio.gim.agent.ui.window.AppLifecycle;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用设置子面板：启动与关闭行为（关闭行为、开机自启、全局热键）。
 * <p>抽自 {@link SettingsWindow}；原混装于此的数据路径 / 上下文窗口 / 用量预算 / 网络代理 /
 * 语音输入 / 语音朗读 / 图像生成卡片已按类型拆至独立菜单页——数据与路径并入
 * {@link BackupRestoreSettingsPanel}（数据与备份），其余分别迁至 {@link ChatUsageSettingsPanel}、
 * {@link NetworkSettingsPanel}、{@link VoiceSettingsPanel} 与 {@link ImageSettingsPanel}。</p>
 *
 * @author gogym
 */
final class GeneralSettingsPanel {

    private final SettingsWindow host;
    private final AppLifecycle appLifecycle;
    private final GlobalHotkeyService globalHotkeyService;

    GeneralSettingsPanel(SettingsWindow host, AppLifecycle appLifecycle, GlobalHotkeyService globalHotkeyService) {
        this.host = host;
        this.appLifecycle = appLifecycle;
        this.globalHotkeyService = globalHotkeyService;
    }

    VBox build() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.general"), I18n.t("settings.general.subtitle"));
        box.getChildren().add(behaviorCard());
        return box;
    }

    /** 启动与关闭卡片：关闭行为（退出/最小化到托盘）与开机自启（仅打包 exe 可用）。 */
    private VBox behaviorCard() {
        String[] opts = {I18n.t("settings.close.tray"), I18n.t("settings.close.exit")};
        ComboBox<String> closeCombo = SettingsLayout.pillCombo(opts);
        boolean exitAction = AppLifecycle.CLOSE_EXIT.equals(
                appLifecycle.readCloseAction().orElse(AppLifecycle.CLOSE_TRAY));
        closeCombo.setValue(opts[exitAction ? 1 : 0]);
        closeCombo.setOnAction(e -> {
            boolean exit = closeCombo.getSelectionModel().getSelectedIndex() == 1;
            appLifecycle.setCloseAction(exit ? AppLifecycle.CLOSE_EXIT : AppLifecycle.CLOSE_TRAY);
        });

        boolean manageable = appLifecycle.canManageAutostart();
        Node autoControl;
        if (manageable) {
            ToggleSwitch autostart = new ToggleSwitch();
            autostart.setSelected(appLifecycle.isAutostartEnabled());
            autostart.selectedProperty().addListener((obs, o, sel) -> {
                if (!appLifecycle.setAutostart(sel)) {
                    autostart.setSelected(!sel);
                    host.notify("settings.autostart", I18n.t("settings.status.autostartFail"));
                }
            });
            autoControl = autostart;
        } else {
            Label na = new Label(I18n.t("settings.autostart.unavailable"));
            na.getStyleClass().add(UiStyles.ROW_VALUE);
            autoControl = na;
        }

        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.settingRow(I18n.t("settings.close.action"), I18n.t("settings.close.action.desc"), closeCombo));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.autostart"), I18n.t("settings.autostart.desc"), autoControl));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.hotkey"), I18n.t("settings.hotkey.desc"), hotkeyControl()));
        return SettingsLayout.groupCard(I18n.t("settings.group.startupClose"), rows);
    }

    /**
     * 全局热键开关控件：仅 Windows 可用（与 {@link GlobalHotkeyService#isSupported()} 同口径）；
     * 切换即写 {@code app.hotkey.enabled} 并即时注册/反注册。不支持平台显示不可用文案。
     */
    private Node hotkeyControl() {
        if (!globalHotkeyService.isSupported()) {
            Label na = new Label(I18n.t("settings.hotkey.unavailable"));
            na.getStyleClass().add(UiStyles.ROW_VALUE);
            return na;
        }
        ToggleSwitch hotkey = new ToggleSwitch();
        hotkey.setSelected(globalHotkeyService.isEnabled());
        hotkey.selectedProperty().addListener((obs, o, sel) -> globalHotkeyService.setEnabled(sel));
        return hotkey;
    }
}
