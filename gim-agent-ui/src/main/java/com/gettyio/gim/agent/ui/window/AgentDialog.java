package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.config.AgentAdminService;
import com.gettyio.gim.agent.ui.config.AgentAdminService.CapabilityOption;
import com.gettyio.gim.agent.ui.config.AgentAdminService.ModelOption;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 智能体表单对话框：设置窗口「智能体」页与侧栏智能体菜单共用同一套控件与校验，
 * 避免两处各自演化出不同的字段口径。
 * <p>技能与 MCP 为白名单多选：不勾即无能力（与运行时的严格语义一致），已禁用项仍可回显
 * 但会标注出来，提醒它勾了也不会生效。</p>
 */
public final class AgentDialog {

    /** 能力列表超过此项时改为内滚，避免对话框拉得过长 */
    private static final int CAP_INLINE_LIMIT = 6;

    private AgentDialog() {
    }

    /**
     * 新建/编辑智能体，保存即落库（并由服务层失效其运行时）。
     *
     * @param existing 现有定义；为 null 表示新建
     * @return 保存后的定义；用户取消或校验未通过时为空
     */
    public static Optional<AgentDefinition> edit(Window owner, AgentAdminService service, AgentDefinition existing) {
        boolean isNew = existing == null;
        AgentDefinition form = isNew ? new AgentDefinition() : existing;
        GimDialog.Shell shell = GimDialog.shell(owner, I18n.t(isNew ? "agent.new.title" : "agent.edit.title"));

        TextField nameField = new TextField(nz(form.getAgentName()));
        nameField.setPromptText(I18n.t("agent.name.prompt"));
        nameField.setMaxWidth(Double.MAX_VALUE);

        TextField descField = new TextField(nz(form.getDescription()));
        descField.setPromptText(I18n.t("agent.desc.prompt"));
        descField.setMaxWidth(Double.MAX_VALUE);

        TextArea promptArea = new TextArea(nz(form.getSystemPrompt()));
        promptArea.setPromptText(I18n.t("agent.prompt.prompt"));
        promptArea.setPrefRowCount(5);
        promptArea.setWrapText(true);

        ComboBox<String> modelBox = new ComboBox<>();
        List<ModelOption> options = new ArrayList<>();
        options.add(ModelOption.followDefault());
        options.addAll(service.modelOptions());
        for (int i = 0; i < options.size(); i++) {
            modelBox.getItems().add(i == 0 ? I18n.t("agent.model.followDefault") : options.get(i).label());
        }
        modelBox.getSelectionModel().select(matchModel(options, form.getProviderCode(), form.getModelCode()));
        modelBox.setMaxWidth(Double.MAX_VALUE);

        TextField tempField = new TextField(form.getTemperature() == null ? "" : trimNum(form.getTemperature()));
        tempField.setPromptText(I18n.t("agent.temp.prompt"));
        TextField maxTokenField = new TextField(form.getMaxTokens() == null ? "" : String.valueOf(form.getMaxTokens()));
        maxTokenField.setPromptText(I18n.t("agent.maxTokens.prompt"));
        HBox paramBox = new HBox(8, tempField, maxTokenField);
        HBox.setHgrow(tempField, Priority.ALWAYS);
        HBox.setHgrow(maxTokenField, Priority.ALWAYS);

        TextField workspaceField = new TextField(nz(form.getWorkspaceDir()));
        workspaceField.setPromptText(I18n.t("agent.workspace.prompt"));
        workspaceField.setMaxWidth(Double.MAX_VALUE);

        // 授权项目目录：非空即允许智能体用文件工具真正编辑该目录内的文件（写前逐次 diff 确认）
        TextField projectField = new TextField(nz(form.getProjectPath()));
        projectField.setPromptText(I18n.t("agent.project.prompt"));
        HBox.setHgrow(projectField, Priority.ALWAYS);
        Button browseProject = new Button(I18n.t("agent.project.browse"));
        browseProject.setOnAction(e -> {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle(I18n.t("agent.project.browse"));
            String current = projectField.getText().trim();
            if (!current.isEmpty()) {
                java.nio.file.Path seed = java.nio.file.Paths.get(current);
                if (java.nio.file.Files.isDirectory(seed)) {
                    chooser.setInitialDirectory(seed.toFile());
                }
            }
            java.io.File picked = chooser.showDialog(shell.stage());
            if (picked != null) {
                projectField.setText(picked.getAbsolutePath());
            }
        });
        HBox projectBox = new HBox(8, projectField, browseProject);
        projectBox.setMaxWidth(Double.MAX_VALUE);

        CapabilityPicker skillsPicker = new CapabilityPicker(
                service.skillOptions(), service.selectedSkillCodes(form), I18n.t("agent.cap.noneSkills"));
        CapabilityPicker mcpPicker = new CapabilityPicker(
                service.mcpOptions(), service.selectedMcpCodes(form), I18n.t("agent.cap.noneMcp"));
        Label capHint = new Label(I18n.t("agent.cap.hint"));
        capHint.getStyleClass().add("row-desc");
        capHint.setWrapText(true);

        Label error = new Label("");
        error.getStyleClass().add("form-error");
        error.setWrapText(true);

        VBox form1 = new VBox(10);
        form1.setMinWidth(400);
        form1.getChildren().addAll(
                fieldRow(I18n.t("agent.name"), nameField),
                fieldRow(I18n.t("agent.desc"), descField),
                fieldRow(I18n.t("agent.prompt"), promptArea),
                fieldRow(I18n.t("agent.model"), modelBox),
                fieldRow(I18n.t("agent.params"), paramBox),
                fieldRow(I18n.t("agent.workspace"), workspaceField),
                fieldRow(I18n.t("agent.project"), projectBox),
                fieldRow(I18n.t("agent.skills"), skillsPicker.node()),
                fieldRow(I18n.t("agent.mcp"), mcpPicker.node()),
                capHint,
                error);

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        Button save = new Button(I18n.t("common.save"));
        save.getStyleClass().add("btn-primary");
        final AgentDefinition[] saved = new AgentDefinition[1];
        save.setOnAction(e -> {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (name.isEmpty()) {
                error.setText(I18n.t("agent.name.required"));
                return;
            }
            Double temperature;
            Integer maxTokens;
            try {
                temperature = parseTemp(tempField.getText());
                maxTokens = parseMaxTokens(maxTokenField.getText());
            } catch (NumberFormatException ex) {
                error.setText(I18n.t("agent.params.invalid"));
                return;
            }
            int index = modelBox.getSelectionModel().getSelectedIndex();
            ModelOption model = index > 0 ? options.get(index) : ModelOption.followDefault();
            form.setAgentName(name);
            form.setDescription(descField.getText().trim());
            form.setSystemPrompt(promptArea.getText().trim());
            form.setProviderCode(model.providerCode());
            form.setModelCode(model.modelCode());
            form.setTemperature(temperature);
            form.setMaxTokens(maxTokens);
            form.setWorkspaceDir(workspaceField.getText().trim());
            form.setProjectPath(projectField.getText().trim());
            // 能力白名单：一个都没勾也要写 []，无能力统一落在空清单而不是留 NULL
            form.setSkillsJson(service.toSkillsJson(skillsPicker.selectedCodes()));
            form.setMcpServersJson(service.toMcpServersJson(mcpPicker.selectedCodes()));
            saved[0] = service.save(form);
            shell.stage().close();
        });
        HBox btns = new HBox(8, cancel, save);
        btns.setAlignment(Pos.CENTER_RIGHT);

        shell.body().getChildren().addAll(form1, btns);
        shell.showAndWait();
        return Optional.ofNullable(saved[0]);
    }

    /** 在选项列表中定位已绑定的模型；未绑定或已删除时回到「跟随应用默认」。 */
    private static int matchModel(List<ModelOption> options, String providerCode, String modelCode) {
        for (int i = 1; i < options.size(); i++) {
            ModelOption o = options.get(i);
            if (o.providerCode().equals(providerCode) && o.modelCode().equals(modelCode)) {
                return i;
            }
        }
        return 0;
    }

    /** 留空 → null，交由模型/提供商默认决定，不用实体自带的 0.7 覆盖。 */
    private static Double parseTemp(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return Double.parseDouble(text.trim());
    }

    private static Integer parseMaxTokens(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return Integer.parseInt(text.trim());
    }

    private static String trimNum(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 对话框字段行：小标题 + 控件。 */
    private static VBox fieldRow(String caption, Node control) {
        Label cap = new Label(caption);
        cap.getStyleClass().add("form-label");
        return new VBox(4, cap, control);
    }

    /**
     * 能力多选区：一行一个 CheckBox，选项后的编码便于区分同名技能；
     * 已禁用项附加醒目标注，但仍允许保持勾选，以便看清它声明过什么。
     */
    private static final class CapabilityPicker {

        private final VBox list = new VBox(4);
        private final List<CheckBox> boxes = new ArrayList<>();
        private final Node node;

        CapabilityPicker(List<CapabilityOption> options, List<String> selected, String emptyHint) {
            Set<String> chosen = new HashSet<>(selected);
            if (options.isEmpty()) {
                Label none = new Label(emptyHint);
                none.getStyleClass().add("row-desc");
                list.getChildren().add(none);
            }
            for (CapabilityOption option : options) {
                String text = option.label() + " · " + option.code();
                if (!option.available()) {
                    text = text + " · " + I18n.t("agent.cap.disabled");
                }
                CheckBox box = new CheckBox(text);
                box.setUserData(option.code());
                box.setSelected(chosen.contains(option.code()));
                box.getStyleClass().add("cap-check");
                if (!option.available()) {
                    box.getStyleClass().add("cap-check-disabled");
                }
                boxes.add(box);
                list.getChildren().add(box);
            }
            node = list.getChildren().size() > CAP_INLINE_LIMIT ? scroll(list) : list;
        }

        /** 被勾选的编码（保持候选项顺序，使落库结果稳定） */
        List<String> selectedCodes() {
            List<String> codes = new ArrayList<>();
            for (CheckBox box : boxes) {
                if (box.isSelected()) {
                    codes.add((String) box.getUserData());
                }
            }
            return codes;
        }

        Node node() {
            return node;
        }

        private static ScrollPane scroll(VBox content) {
            ScrollPane scroller = new ScrollPane(content);
            scroller.setFitToWidth(true);
            scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroller.setPrefHeight(150);
            scroller.setMaxHeight(150);
            scroller.getStyleClass().add("cap-scroll");
            return scroller;
        }
    }
}
