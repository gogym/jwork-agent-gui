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

import com.gettyio.gim.agent.domain.entity.PromptTemplate;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.ui.control.CardKit;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.config.PromptTemplateAdminService;
import com.gettyio.gim.agent.ui.control.ToggleSwitch;
import com.gettyio.gim.agent.ui.window.dialog.GimDialog;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

import java.util.List;

/**
 * 提示词模板设置子面板：卡片式列出模板（启停开关 + 编辑 + 删除），内联表单新增/编辑。
 * 抽自 {@link SettingsWindow}，持有本页专属的 {@code templateCardsBox}；渲染由宿主整表刷新驱动。
 *
 * @author gogym
 */
final class TemplateSettingsPanel {

    private final SettingsWindow host;
    private final PromptTemplateAdminService promptTemplateAdminService;
    private final FlowPane templateCardsBox = SettingsLayout.cardsBox();

    TemplateSettingsPanel(SettingsWindow host, PromptTemplateAdminService promptTemplateAdminService) {
        this.host = host;
        this.promptTemplateAdminService = promptTemplateAdminService;
    }

    VBox build() {
        VBox box = new VBox();
        box.getStyleClass().add("settings-page");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMaxHeight(Double.MAX_VALUE);

        VBox headText = new VBox(4);
        Label title = new Label(I18n.t("settings.menu.templates"));
        title.getStyleClass().add("page-title");
        Label subtitle = new Label(I18n.t("settings.templates.subtitle"));
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.setWrapText(true);
        headText.getChildren().addAll(title, subtitle);

        Button addBtn = new Button(I18n.t("settings.template.add"));
        addBtn.getStyleClass().add("btn-primary");
        Label addIcon = new Label("\uE710");
        addIcon.getStyleClass().add("icon");
        addBtn.setGraphic(addIcon);
        addBtn.setOnAction(e -> openTemplateDialog(null));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(headText, spacer, addBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        templateCardsBox.getStyleClass().add("provider-cards");
        VBox.setVgrow(templateCardsBox, Priority.ALWAYS);
        box.getChildren().addAll(header, templateCardsBox);
        return box;
    }

    /** 依据库中数据重绘模板信息卡片（宿主整表刷新时调用）。 */
    void reload() {
        templateCardsBox.getChildren().clear();
        List<PromptTemplate> templates = promptTemplateAdminService.list();
        if (templates.isEmpty()) {
            templateCardsBox.setAlignment(Pos.CENTER);
            templateCardsBox.getChildren().add(templateEmptyState());
            return;
        }
        templateCardsBox.setAlignment(Pos.TOP_LEFT);
        for (PromptTemplate t : templates) {
            templateCardsBox.getChildren().add(templateCard(t));
        }
    }

    private VBox templateEmptyState() {
        VBox box = new VBox();
        box.getStyleClass().add("empty-state");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFillWidth(false);
        Label icon = new Label("\uE73A");
        icon.getStyleClass().add("empty-icon");
        Label t = new Label(I18n.t("settings.template.empty"));
        t.getStyleClass().add("empty-title");
        t.setTextAlignment(TextAlignment.CENTER);
        Label d = new Label(I18n.t("settings.template.emptyDesc"));
        d.getStyleClass().add("empty-desc");
        d.setWrapText(true);
        d.setTextAlignment(TextAlignment.CENTER);
        d.setMaxWidth(460);
        box.getChildren().addAll(icon, t, d);
        return box;
    }

    private VBox templateCard(PromptTemplate t) {
        boolean enabled = EnabledStatus.isEnabled(t.getEnabled());
        Label name = new Label(t.getTemplateName() == null || t.getTemplateName().isBlank()
                ? t.getTemplateCode() : t.getTemplateName());
        name.getStyleClass().add("provider-card-title");
        Label badge = new Label(I18n.t(enabled ? "settings.template.enabled" : "settings.template.disabled"));
        badge.getStyleClass().addAll("badge", enabled ? "badge-green" : "badge-gray");

        ToggleSwitch toggle = new ToggleSwitch();
        toggle.setSelected(enabled);
        toggle.setMinWidth(Region.USE_PREF_SIZE);
        toggle.selectedProperty().addListener((obs, o, sel) -> {
            promptTemplateAdminService.setEnabled(t.getTemplateCode(), sel);
            reload();
        });

        Button edit = CardKit.iconTextButton("\uE70F", I18n.t("common.edit"), () -> openTemplateDialog(t));
        edit.getStyleClass().add("link-btn");
        Button del = CardKit.iconTextButton("\uE74D", I18n.t("common.delete"), () -> handleDeleteTemplate(t));
        del.getStyleClass().addAll("link-btn", "link-btn-danger");
        // 卡片首行：名称可压缩 + 徽标 + 操作区（启停开关 + 编辑 + 删除）钉住首选宽度
        name.setMinWidth(0);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setTooltip(new Tooltip(name.getText()));
        badge.setMinWidth(Region.USE_PREF_SIZE);
        HBox titleRow = new HBox(10, name, badge);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.setMaxWidth(Double.MAX_VALUE);
        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        HBox head = new HBox();
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(titleRow, headSpacer, toggle, edit, del);
        for (Node action : List.of(toggle, edit, del)) {
            if (action instanceof Region r) {
                r.setMinWidth(Region.USE_PREF_SIZE);
            }
        }

        VBox card = new VBox(8, head,
                CardKit.kvLine(I18n.t("settings.template.desc"), t.getDescription()),
                CardKit.kvLine(I18n.t("settings.template.content"), SettingsLayout.preview(t.getContent())));
        card.getStyleClass().add("provider-card");
        card.setPrefWidth(SettingsLayout.CARD_WIDTH);
        card.setMaxWidth(SettingsLayout.CARD_WIDTH);
        return card;
    }

    /** 弹模板表单：名称 / 描述 / 内容；保存写库后刷列表。{@code editing} 为 null 表示新增。 */
    private void openTemplateDialog(PromptTemplate editing) {
        GimDialog.Shell shell = GimDialog.shell(host.hostWindow(),
                I18n.t(editing == null ? "settings.template.addTitle" : "settings.template.editTitle"));

        TextField nameField = new TextField(editing == null ? "" : editing.getTemplateName());
        nameField.setPromptText(I18n.t("settings.template.namePrompt"));
        nameField.setMaxWidth(Double.MAX_VALUE);
        TextField descField = new TextField(editing == null ? "" : editing.getDescription());
        descField.setPromptText(I18n.t("settings.template.descPrompt"));
        descField.setMaxWidth(Double.MAX_VALUE);
        TextArea contentArea = new TextArea(editing == null ? "" : editing.getContent());
        contentArea.setPromptText(I18n.t("settings.template.contentPrompt"));
        contentArea.setPrefRowCount(8);
        contentArea.setWrapText(true);
        contentArea.setMaxWidth(Double.MAX_VALUE);

        VBox form = new VBox(10);
        form.setPrefWidth(460);
        form.getChildren().addAll(
                SettingsLayout.labeledField(I18n.t("settings.template.name"), nameField),
                SettingsLayout.labeledField(I18n.t("settings.template.desc"), descField),
                SettingsLayout.labeledField(I18n.t("settings.template.content"), contentArea));

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        Button save = new Button(I18n.t("common.save"));
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            String content = contentArea.getText() == null ? "" : contentArea.getText().trim();
            if (name.isEmpty() || content.isEmpty()) {
                host.notify("settings.menu.templates", I18n.t("settings.status.templateRequired"));
                return;
            }
            PromptTemplate t = editing == null ? new PromptTemplate() : editing;
            t.setTemplateName(name);
            t.setDescription(descField.getText() == null ? null : descField.getText().trim());
            t.setContent(content);
            if (editing == null) {
                t.setEnabled(1);
            }
            promptTemplateAdminService.save(t);
            reload();
            shell.stage().close();
        });
        HBox btns = new HBox(8, cancel, save);
        btns.getStyleClass().add("dialog-actions");
        btns.setAlignment(Pos.CENTER_RIGHT);

        shell.body().getChildren().addAll(form, btns);
        shell.showAndWait();
    }

    private void handleDeleteTemplate(PromptTemplate t) {
        if (!GimDialog.confirm(host.hostWindow(), I18n.t("settings.menu.templates"),
                I18n.t("settings.template.deleteConfirm", t.getTemplateName()))) {
            return;
        }
        promptTemplateAdminService.delete(t.getTemplateCode());
        reload();
    }
}
