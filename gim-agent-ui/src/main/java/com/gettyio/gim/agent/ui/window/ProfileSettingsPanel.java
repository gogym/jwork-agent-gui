package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import com.gettyio.gim.agent.ui.update.UpdateChecker;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 个人资料（关于）设置子面板：应用版本/数据目录/语言/主题只读概览。
 * <p>更新相关功能已抽至独立的 {@link UpdateSettingsPanel}（「更新」菜单页）。</p>
 */
final class ProfileSettingsPanel {

    private final GimPaths gimPaths;
    private final UpdateChecker updateChecker;

    ProfileSettingsPanel(GimPaths gimPaths, UpdateChecker updateChecker) {
        this.gimPaths = gimPaths;
        this.updateChecker = updateChecker;
    }

    VBox build() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.profile"), I18n.t("settings.profile.subtitle"));

        HBox head = new HBox(16);
        head.getStyleClass().add("profile-head");
        Label avatar = new Label("A");
        avatar.getStyleClass().add("profile-avatar");
        avatar.setAlignment(Pos.CENTER);
        Label name = new Label(I18n.t("user.local"));
        name.getStyleClass().add("profile-name");
        Label badge = new Label(I18n.t("settings.profile.badge"));
        badge.getStyleClass().addAll("badge", "badge-green");
        VBox info = new VBox(6, name, badge);
        info.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(avatar, info);

        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.settingRow(I18n.t("settings.profile.appVersion"), null,
                SettingsLayout.valueLabel(updateChecker.currentVersion())));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.key.dataDir"), null,
                SettingsLayout.valueLabel(String.valueOf(gimPaths.root()))));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.profile.language"), null,
                SettingsLayout.valueLabel(I18n.current().nativeName())));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.profile.theme"), null,
                SettingsLayout.valueLabel(I18n.t(UiTheme.mode() == UiTheme.Mode.DARK ? "menu.mode.dark" : "menu.mode.light"))));

        box.getChildren().add(SettingsLayout.groupCard(I18n.t("settings.group.about"), rows));
        return box;
    }
}
