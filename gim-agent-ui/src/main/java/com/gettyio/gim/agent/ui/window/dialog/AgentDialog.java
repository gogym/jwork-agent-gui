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

package com.gettyio.gim.agent.ui.window.dialog;

import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.config.AgentAdminService;
import com.gettyio.gim.agent.ui.config.AgentAdminService.CapabilityOption;
import com.gettyio.gim.agent.ui.config.AgentAdminService.ModelOption;
import com.gettyio.gim.agent.ui.util.UiStyles;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
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
 *
 * @author gogym
 */
public final class AgentDialog {

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
        capHint.getStyleClass().add(UiStyles.ROW_DESC);
        capHint.setWrapText(true);

        Label error = new Label("");
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        // 无错时不占行：双列布局底部不再留一条空白带
        error.setVisible(false);
        error.setManaged(false);

        // 双列宽版：左列基本信息、右列工作区与能力白名单；单列叠放会把对话框拉到屏外，
        // 加宽后两列各自收高，常见屏幕一屏放下
        VBox leftCol = new VBox(10,
                fieldRow(I18n.t("agent.name"), nameField),
                fieldRow(I18n.t("agent.desc"), descField),
                fieldRow(I18n.t("agent.prompt"), promptArea),
                fieldRow(I18n.t("agent.model"), modelBox),
                fieldRow(I18n.t("agent.params"), paramBox),
                fieldRow(I18n.t("agent.workspace"), workspaceField),
                fieldRow(I18n.t("agent.project"), projectBox));
        VBox rightCol = new VBox(10,
                fieldRow(I18n.t("agent.skills"), skillsPicker.node()),
                fieldRow(I18n.t("agent.mcp"), mcpPicker.node()),
                capHint);
        leftCol.setMaxWidth(Double.MAX_VALUE);
        rightCol.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(leftCol, Priority.ALWAYS);
        HBox.setHgrow(rightCol, Priority.ALWAYS);
        HBox columns = new HBox(24, leftCol, rightCol);
        VBox formRoot = new VBox(12, columns, error);
        formRoot.setMinWidth(760);
        formRoot.setPrefWidth(860);
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        Button save = new Button(I18n.t("common.save"));
        save.getStyleClass().add(UiStyles.BTN_PRIMARY);
        final AgentDefinition[] saved = new AgentDefinition[1];
        save.setOnAction(e -> {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (name.isEmpty()) {
                showError(error, I18n.t("agent.name.required"));
                return;
            }
            Double temperature;
            Integer maxTokens;
            try {
                temperature = parseTemp(tempField.getText());
                maxTokens = parseMaxTokens(maxTokenField.getText());
            } catch (NumberFormatException ex) {
                showError(error, I18n.t("agent.params.invalid"));
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

        shell.body().getChildren().addAll(formRoot, btns);
        shell.showAndWait();
        return Optional.ofNullable(saved[0]);
    }

    /** 校验失败才露出错误行，避免常态下底部空占位；窗口随新内容重算尺寸 */
    private static void showError(Label error, String message) {
        error.setText(message);
        error.setManaged(true);
        error.setVisible(true);
        if (error.getScene() != null && error.getScene().getWindow() instanceof Stage stage) {
            Platform.runLater(stage::sizeToScene);
        }
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
     * 能力多选区：搜索过滤 + 已选计数 + 全选/清空，两行行（名称 + 编码，同名只显一行），
     * 禁用项琥珀徽标标注；列表限高内滚，记录再多对话框也不被拉长。
     * <p>批量操作作用于「当前可见行」：搜索后可一键全选结果；计数始终是全量口径。</p>
     */
    private static final class CapabilityPicker {

        /** 内滚限高：约四行两行行；双列布局下两个列表与左列基本信息齐高 */
        private static final double LIST_MAX_HEIGHT = 176;

        private final List<CapabilityOption> options;
        private final List<CheckBox> boxes = new ArrayList<>();
        private final List<HBox> rows = new ArrayList<>();
        private final TextField search = new TextField();
        private final Label count = new Label();
        private final Label empty = new Label(I18n.t("agent.cap.noMatch"));
        private VBox list;
        private ScrollPane scroller;
        private final Node node;

        CapabilityPicker(List<CapabilityOption> options, List<String> selected, String emptyHint) {
            this.options = options;
            if (options.isEmpty()) {
                Label none = new Label(emptyHint);
                none.getStyleClass().add(UiStyles.ROW_DESC);
                none.setWrapText(true);
                node = none;
                return;
            }
            Set<String> chosen = new HashSet<>(selected);
            list = new VBox(2);
            list.getStyleClass().add("cap-list-box");
            for (CapabilityOption option : options) {
                HBox row = row(option, chosen.contains(option.code()));
                rows.add(row);
                list.getChildren().add(row);
            }
            empty.getStyleClass().add("cap-empty");
            empty.setMaxWidth(Double.MAX_VALUE);
            empty.setVisible(false);
            empty.setManaged(false);
            list.getChildren().add(empty);
            scroller = new ScrollPane(list);
            scroller.setFitToWidth(true);
            scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroller.getStyleClass().add("cap-list");
            scroller.setMaxHeight(LIST_MAX_HEIGHT);

            search.setPromptText(I18n.t("agent.cap.search"));
            search.getStyleClass().add("cap-search");
            HBox.setHgrow(search, Priority.ALWAYS);
            search.textProperty().addListener((obs, was, now) -> applyFilter());
            count.getStyleClass().add("cap-count");
            Button all = new Button(I18n.t("agent.cap.selectAll"));
            all.getStyleClass().add(UiStyles.LINK_BTN);
            all.setOnAction(e -> bulk(true));
            Button clear = new Button(I18n.t("agent.cap.clear"));
            clear.getStyleClass().add(UiStyles.LINK_BTN);
            clear.setOnAction(e -> bulk(false));
            HBox toolbar = new HBox(6, search, count, all, clear);
            toolbar.setAlignment(Pos.CENTER_LEFT);
            VBox root = new VBox(6, toolbar, scroller);
            root.getStyleClass().add("cap-picker");
            root.setMaxWidth(Double.MAX_VALUE);
            node = root;
            applyFilter();
            // 构造期行未过 CSS，pref 估高有偏差；首帧 CSS 就绪后再算一次真高
            Platform.runLater(this::applyFilter);
        }

        /**
         * 单行：小复选框 + 名称/编码两行文本 + 禁用徽标同排；复选框与标题行同水平中线
         * （描述行不参与对齐，见 centerSquares）；行内任意位置点击即切换，选中态铺淡绿底便于扫读。
         */
        private HBox row(CapabilityOption option, boolean selected) {
            Label name = new Label(option.label());
            name.getStyleClass().add("cap-row-name");
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            name.setMaxWidth(Double.MAX_VALUE);
            VBox texts = new VBox(1, name);
            texts.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(texts, Priority.ALWAYS);
            // 名称与编码相同只显一行，避免「translate · translate」式重复噪音
            if (!option.code().equals(option.label())) {
                Label code = new Label(option.code());
                code.getStyleClass().add("cap-row-code");
                code.setTextOverrun(OverrunStyle.ELLIPSIS);
                code.setMaxWidth(Double.MAX_VALUE);
                texts.getChildren().add(code);
            }
            CheckBox box = new CheckBox();
            box.setSelected(selected);
            boxes.add(box);
            HBox row = new HBox(10, box, texts);
            // fillHeight=false：各子节点保留自身 pref 高再居中对齐；默认拉伸会按 pref 算中线后
            // 再把实高拉到行高，复选框与两行文本块错开 1px
            row.setFillHeight(false);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMaxWidth(Double.MAX_VALUE);
            row.getStyleClass().add("cap-row");
            if (!option.available()) {
                row.getStyleClass().add("cap-row-disabled");
                Label tag = new Label(I18n.t("agent.cap.disabled"));
                tag.getStyleClass().addAll("badge", "cap-tag-disabled");
                row.getChildren().add(tag);
            }
            if (selected) {
                row.getStyleClass().add("cap-row-on");
            }
            box.selectedProperty().addListener((obs, was, now) -> {
                if (now) {
                    row.getStyleClass().add("cap-row-on");
                } else {
                    row.getStyleClass().remove("cap-row-on");
                }
                updateCount();
            });
            // 点文本/空白也切换；落在复选框自身的点击走其原生切换，这里跳过避免双切换
            row.setOnMouseClicked(e -> {
                Node target = e.getTarget() instanceof Node n ? n : null;
                if (!inside(box, target)) {
                    box.setSelected(!box.isSelected());
                }
            });
            return row;
        }

        private static boolean inside(Node ancestor, Node target) {
            for (Node n = target; n != null; n = n.getParent()) {
                if (n == ancestor) {
                    return true;
                }
            }
            return false;
        }

        /** 搜索过滤：名称/编码不区分大小写包含；无匹配时给占位提示 */
        private void applyFilter() {
            String q = search.getText() == null ? "" : search.getText().trim().toLowerCase();
            int visible = 0;
            for (int i = 0; i < boxes.size(); i++) {
                CapabilityOption o = options.get(i);
                boolean hit = q.isEmpty()
                        || o.label().toLowerCase().contains(q)
                        || o.code().toLowerCase().contains(q);
                HBox row = rows.get(i);
                row.setVisible(hit);
                row.setManaged(hit);
                if (hit) {
                    visible++;
                }
            }
            empty.setVisible(visible == 0);
            empty.setManaged(visible == 0);
            updateCount();
            // 容器高随内容收放（过滤后不留大片空白），封顶内滚；+10 = 内衬 8 + 边框 2。
            // 同步显式设高而非绑定：绑定在布局 pass 内更新会被 JavaFX 布局守卫吞掉 requestLayout
            scroller.setPrefHeight(Math.min(LIST_MAX_HEIGHT, list.prefHeight(-1) + 10));
            // 过滤改变列表高度后对话框跟随自适应，避免底部留洞
            Platform.runLater(this::settleGeometry);
        }

        /**
         * 首帧皮肤/布局可能未就绪（lookup 不到方格）：未就绪则顺延到下一轮事件循环重测，
         * 保证真实弹窗里补偿必定生效，而不是静默跳过停在块中线。
         */
        private void settleGeometry() {
            boolean ready = centerSquares();
            if (node.getScene() != null && node.getScene().getWindow() instanceof Stage stage) {
                stage.sizeToScene();
            }
            if (!ready && node.getScene() != null) {
                Platform.runLater(this::settleGeometry);
            }
        }

        /**
         * 复选框方格与标题行精确同中线（描述行不参与）：Modena 方格会比控件 pref 高 1px 溢出、
         * HBox 中线又按像素取整，CSS 锁尺寸/em 缩放都消不掉这 1px；
         * 故 CSS 就绪后实测方格与名称标签的中线差，用 translateY 纯变换补偿（不触发重布局）。
         * 文本先吸整像素：半像素 Y 会让字形退化为灰度抗锯齿，观感偏细偏小且逐行不一。
         *
         * @return 所有可见行是否都已就绪并完成测量
         */
        private boolean centerSquares() {
            boolean pending = false;
            for (int i = 0; i < rows.size(); i++) {
                HBox row = rows.get(i);
                if (!row.isManaged()) {
                    continue;
                }
                CheckBox box = boxes.get(i);
                Node square = box.lookup(".box");
                if (square == null || row.getWidth() <= 0) {
                    pending = true;
                    continue;
                }
                VBox texts = (VBox) row.getChildren().get(1);
                snapY(texts);
                for (Node child : texts.getChildren()) {
                    snapY(child);
                }
                Label name = (Label) texts.getChildren().get(0);
                javafx.geometry.Bounds sq = box.localToParent(square.getBoundsInParent());
                javafx.geometry.Bounds tx = row.sceneToLocal(name.localToScene(name.getBoundsInLocal()));
                double delta = (tx.getMinY() + tx.getHeight() / 2) - (sq.getMinY() + sq.getHeight() / 2);
                box.setTranslateY(box.getTranslateY() + delta);
                snapY(box);
            }
            return !pending;
        }

        /** 节点绝对 Y 吸到整像素（纯变换，不触发重布局；重复调用收敛） */
        private static void snapY(Node n) {
            double y = n.localToScene(0, 0).getY();
            n.setTranslateY(n.getTranslateY() + Math.round(y) - y);
        }

        /** 全选/清空：只作用于当前可见行，搜索后批量勾选结果符合直觉 */
        private void bulk(boolean selected) {
            for (int i = 0; i < boxes.size(); i++) {
                if (rows.get(i).isManaged()) {
                    boxes.get(i).setSelected(selected);
                }
            }
        }

        private void updateCount() {
            int sel = 0;
            for (CheckBox box : boxes) {
                if (box.isSelected()) {
                    sel++;
                }
            }
            count.setText(I18n.t("agent.cap.count", sel, boxes.size()));
        }

        /** 被勾选的编码（保持候选项顺序，使落库结果稳定） */
        List<String> selectedCodes() {
            List<String> codes = new ArrayList<>();
            for (int i = 0; i < boxes.size(); i++) {
                if (boxes.get(i).isSelected()) {
                    codes.add(options.get(i).code());
                }
            }
            return codes;
        }

        Node node() {
            return node;
        }
    }
}
