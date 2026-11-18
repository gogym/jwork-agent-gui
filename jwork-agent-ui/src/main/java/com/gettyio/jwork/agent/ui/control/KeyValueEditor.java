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

package com.gettyio.jwork.agent.ui.control;

import com.gettyio.jwork.agent.ui.i18n.I18n;
import com.gettyio.jwork.agent.ui.util.UiStyles;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 键值对编辑器：MCP 表单的环境变量 / 请求头输入口（动态增删行）。
 * <p>值默认掩码（密码框），行内眼睛按钮在密文/明文间切换——与登录页同款叠放双向绑定手法，
 * 切换只换可见节点、输入内容不丢；收集时始终读密码框文本，无需感知切换。</p>
 * <p>收集口径：键名去首尾空白、空键名行忽略（允许留一行空行待填）；同名键后行覆盖前行；
 * 值原样保留（不 trim，Bearer token 与环境变量值里的空格可能有语义）。删行是移除该键的唯一路径。</p>
 *
 * @author gogym
 */
public final class KeyValueEditor {

    private static final String GLYPH_REVEAL = "\uE7B3";
    private static final String GLYPH_HIDE = "\uED1A";
    private static final String GLYPH_REMOVE = "\uE74D";
    private static final String GLYPH_ADD = "\uE710";

    private final VBox rows = new VBox(6);
    private final VBox root = new VBox(6);
    private final Button addBtn = new Button();
    private final List<Row> rowList = new ArrayList<>();
    /** 表单标题对齐锚高度：有行取首行输入框，无行取添加按钮（标题与输入框同水平中线） */
    private final SimpleDoubleProperty anchorHeight = new SimpleDoubleProperty();
    private final String namePrompt;
    private final String valuePrompt;

    /**
     * @param namePrompt  键名输入框 placeholder（如 API_KEY / Authorization）
     * @param valuePrompt 值输入框 placeholder（如 sk-… / Bearer sk-…）
     */
    public KeyValueEditor(String namePrompt, String valuePrompt) {
        this.namePrompt = namePrompt;
        this.valuePrompt = valuePrompt;
        root.getStyleClass().add("kv-editor");
        addBtn.getStyleClass().add(UiStyles.LINK_BTN);
        addBtn.setGraphic(icon(GLYPH_ADD));
        addBtn.setText(I18n.t("settings.mcp.kv.add"));
        addBtn.setOnAction(e -> addRow());
        root.getChildren().addAll(rows, addBtn);
        rows.getChildren().addListener((ListChangeListener<Node>) c -> rebindAnchor());
        rebindAnchor();
    }

    /** 对齐锚高度（只读）：供表单标题绑定平移量，行增删后自动换锚。 */
    public ReadOnlyDoubleProperty anchorHeightProperty() {
        return anchorHeight;
    }

    private void rebindAnchor() {
        anchorHeight.unbind();
        if (rowList.isEmpty()) {
            anchorHeight.bind(addBtn.heightProperty());
        } else {
            anchorHeight.bind(rowList.get(0).box.heightProperty());
        }
    }

    /** 整块控件（行列表 + 添加按钮），可直接放进表单栅格。 */
    public Node getNode() {
        return root;
    }

    /** 以映射重建全部行（保持传入顺序）；null 值落空串。 */
    public void setEntries(Map<String, String> entries) {
        rowList.clear();
        rows.getChildren().clear();
        if (entries != null) {
            entries.forEach((name, value) -> appendRow(name, value == null ? "" : value));
        }
    }

    /** 追加一行空行（「添加一行」按钮同口径）。 */
    public void addRow() {
        appendRow("", "");
    }

    /** 当前行数（含待填的空行）。 */
    public int rowCount() {
        return rowList.size();
    }

    /** 收集为映射：键名 trim、空键名忽略、同名后行覆盖；值原样。 */
    public Map<String, String> collect() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Row row : rowList) {
            String name = row.name.getText() == null ? "" : row.name.getText().trim();
            if (!name.isEmpty()) {
                out.put(name, row.secret.getText() == null ? "" : row.secret.getText());
            }
        }
        return out;
    }

    private void appendRow(String name, String value) {
        Row row = new Row(name, value);
        rowList.add(row);
        rows.getChildren().add(row.box);
    }

    private static Label icon(String glyph) {
        Label label = new Label(glyph);
        label.getStyleClass().add("icon");
        return label;
    }

    /** 单行：键名框 + 掩码值（内嵌显形按钮）+ 删行按钮。 */
    private final class Row {

        private final HBox box;
        private final TextField name;
        private final PasswordField secret;

        private Row(String nameText, String valueText) {
            name = new TextField(nameText);
            name.setPromptText(namePrompt);
            name.getStyleClass().add("kv-name");
            secret = new PasswordField();
            secret.setText(valueText);
            secret.setPromptText(valuePrompt);
            TextField plain = new TextField();
            plain.textProperty().bindBidirectional(secret.textProperty());
            plain.setPromptText(valuePrompt);
            plain.setVisible(false);
            plain.setManaged(false);
            secret.getStyleClass().add("has-reveal");
            plain.getStyleClass().add("has-reveal");

            Label eye = icon(GLYPH_REVEAL);
            Button reveal = new Button();
            reveal.getStyleClass().add("auth-reveal-btn");
            reveal.setFocusTraversable(false);
            reveal.setGraphic(eye);
            Tooltip tooltip = new Tooltip(I18n.t("settings.mcp.kv.reveal.show"));
            reveal.setTooltip(tooltip);
            reveal.setOnAction(e -> {
                boolean show = !plain.isVisible();
                secret.setVisible(!show);
                secret.setManaged(!show);
                plain.setVisible(show);
                plain.setManaged(show);
                TextInputControl editing = show ? plain : secret;
                editing.positionCaret(editing.getText() == null ? 0 : editing.getText().length());
                editing.requestFocus();
                eye.setText(show ? GLYPH_HIDE : GLYPH_REVEAL);
                reveal.getStyleClass().remove("auth-reveal-on");
                if (show) {
                    reveal.getStyleClass().add("auth-reveal-on");
                }
                tooltip.setText(I18n.t(show ? "settings.mcp.kv.reveal.hide" : "settings.mcp.kv.reveal.show"));
            });
            StackPane valueBox = new StackPane(secret, plain, reveal);
            StackPane.setAlignment(reveal, Pos.CENTER_RIGHT);
            StackPane.setMargin(reveal, new Insets(0, 5, 0, 0));
            HBox.setHgrow(valueBox, Priority.ALWAYS);

            Button remove = new Button();
            remove.getStyleClass().addAll(UiStyles.LINK_BTN, UiStyles.LINK_BTN_DANGER);
            remove.setFocusTraversable(false);
            remove.setGraphic(icon(GLYPH_REMOVE));
            remove.setTooltip(new Tooltip(I18n.t("settings.mcp.kv.remove")));

            box = new HBox(6, name, valueBox, remove);
            box.setAlignment(Pos.CENTER_LEFT);
            // box 赋值后再挂监听：λ 内读 final 字段须先完成确定赋值
            remove.setOnAction(e -> {
                rowList.remove(this);
                rows.getChildren().remove(box);
            });
        }
    }
}