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
import com.gettyio.gim.agent.ui.theme.UiTheme;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * 外观设置子面板：主题、字号、界面语言。抽自 {@link SettingsWindow}，改动即时生效；
 * 语言切换由宿主监听后整窗重建，本页只负责把当前值呈现到胶囊下拉并回写。
 *
 * @author gogym
 */
final class AppearanceSettingsPanel {

    VBox build() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.appearance"), I18n.t("settings.appearance.subtitle"));

        String[] themeOpts = {I18n.t("menu.mode.light"), I18n.t("menu.mode.dark")};
        ComboBox<String> themeCombo = SettingsLayout.pillCombo(themeOpts);
        themeCombo.setValue(UiTheme.mode() == UiTheme.Mode.DARK ? themeOpts[1] : themeOpts[0]);
        themeCombo.setOnAction(e -> UiTheme.setMode(
                themeCombo.getSelectionModel().getSelectedIndex() == 1
                        ? UiTheme.Mode.DARK : UiTheme.Mode.LIGHT));

        String[] fontOpts = {I18n.t("menu.font.small"), I18n.t("menu.font.medium"), I18n.t("menu.font.large")};
        ComboBox<String> fontCombo = SettingsLayout.pillCombo(fontOpts);
        fontCombo.setValue(fontOpts[UiTheme.fontSize().ordinal()]);
        fontCombo.setOnAction(e -> {
            int idx = fontCombo.getSelectionModel().getSelectedIndex();
            if (idx >= 0) {
                UiTheme.setFontSize(UiTheme.FontSize.values()[idx]);
            }
        });

        I18n.Language[] langs = I18n.Language.values();
        String[] langOpts = new String[langs.length];
        for (int i = 0; i < langs.length; i++) {
            langOpts[i] = langs[i].nativeName();
        }
        ComboBox<String> langCombo = SettingsLayout.pillCombo(langOpts);
        langCombo.setValue(I18n.current().nativeName());
        langCombo.setOnAction(e -> {
            int idx = langCombo.getSelectionModel().getSelectedIndex();
            if (idx >= 0) {
                I18n.setLanguage(langs[idx]);
            }
        });

        List<javafx.scene.Node> rows = List.of(
                SettingsLayout.settingRow(I18n.t("settings.appearance.theme"), I18n.t("settings.appearance.theme.desc"), themeCombo),
                SettingsLayout.settingRow(I18n.t("settings.appearance.font"), I18n.t("settings.appearance.font.desc"), fontCombo),
                SettingsLayout.settingRow(I18n.t("settings.appearance.lang"), I18n.t("settings.appearance.lang.desc"), langCombo));
        box.getChildren().add(SettingsLayout.groupCard(I18n.t("settings.group.interface"), rows));
        return box;
    }
}
