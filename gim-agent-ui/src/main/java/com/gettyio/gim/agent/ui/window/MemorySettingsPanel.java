package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.ui.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 记忆设置子面板：只读查看 workspace/MEMORY.md（由 Agent 自动维护）。抽自 {@link SettingsWindow}。
 */
final class MemorySettingsPanel {

    private final SettingsWindow host;
    private final GimPaths gimPaths;

    MemorySettingsPanel(SettingsWindow host, GimPaths gimPaths) {
        this.host = host;
        this.gimPaths = gimPaths;
    }

    VBox build() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.memory"), I18n.t("settings.memory.subtitle"));
        Path memoryFile = gimPaths.memoryMd();

        TextArea viewer = new TextArea(readMemory(memoryFile));
        viewer.getStyleClass().add("memory-editor");
        viewer.setWrapText(true);
        viewer.setEditable(false);
        viewer.setMaxWidth(Double.MAX_VALUE);

        Button reveal = new Button(I18n.t("settings.memory.reveal"));
        reveal.setOnAction(e -> revealMemory(memoryFile));
        HBox actions = new HBox(8, reveal);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(8, 0, 0, 0));

        Label hint = new Label(I18n.t("settings.memory.readonlyHint"));
        hint.getStyleClass().add("row-desc");
        hint.setWrapText(true);

        VBox card = new VBox(
                SettingsLayout.settingRow(I18n.t("settings.memory.file"), String.valueOf(memoryFile), null),
                hint, viewer, actions);
        card.getStyleClass().add("settings-group");
        Label gt = new Label(I18n.t("settings.group.memory"));
        gt.getStyleClass().add("settings-group-title");
        box.getChildren().addAll(gt, card);
        return box;
    }

    private String readMemory(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void revealMemory(Path file) {
        try {
            Path dir = file.getParent();
            if (Desktop.isDesktopSupported() && dir != null) {
                Desktop.getDesktop().open(dir.toFile());
            } else {
                host.notify("settings.menu.memory", I18n.t("settings.memory.revealFail", "Desktop unsupported"));
            }
        } catch (Exception e) {
            host.notify("settings.menu.memory", I18n.t("settings.memory.revealFail", e.getMessage()));
        }
    }
}
