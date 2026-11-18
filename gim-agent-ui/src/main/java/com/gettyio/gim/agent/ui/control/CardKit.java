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

package com.gettyio.gim.agent.ui.control;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * 跨页面通用卡片构件工具箱：设置页、任务页、MCP 页共享的卡片搭样纯函数。
 * <p>「卡片首行」「图标文字按钮」「键值行」「图标状态值」等构件在多个页面存在相同实现，
 * 统一收敛到本类，集中样式类名与布局细节（名称省略号截断、操作按钮保宽度、
 * 值列横向拉伸等），避免各页面重复同一段搭样代码。全部为无状态纯函数，可直接静态调用。</p>
 *
 * @author gogym
 */
public final class CardKit {

    /** 名称锁宽槽位：徽标左缘 = 槽位 + 行距，跨卡片对齐，不随名称长短前移后挪 */
    private static final double NAME_SLOT = 120;

    private CardKit() {
    }

    /** 图标文字按钮：Segoe 图标 + 文本；按钮样式类（如 link-btn / link-btn-danger）由调用方按场景补充。 */
    public static Button iconTextButton(String iconText, String text, Runnable action) {
        Button b = new Button(text);
        Label ic = new Label(iconText);
        ic.getStyleClass().add("icon");
        b.setGraphic(ic);
        b.setOnAction(e -> action.run());
        return b;
    }

    /**
     * 卡片首行：左侧名称 + 状态徽标，右侧操作按钮（编辑/删除/使用等）。
     * <p>名称锁在 {@link #NAME_SLOT} 宽槽位内（过长省略号截断并挂 tooltip 便于看全名），
     * 标题行吸收剩余宽度，使徽标左缘在同页卡片间保持同一竖线；空间不足时只压缩名称，
     * 徽标与操作按钮的最小宽度钉在首选宽度上，保证操作入口始终完整可见。</p>
     */
    public static HBox cardHeadRow(Label name, Label badge, Button... actions) {
        name.setMinWidth(0);
        name.setPrefWidth(NAME_SLOT);
        name.setMaxWidth(NAME_SLOT);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setTooltip(new Tooltip(name.getText()));
        badge.setMinWidth(Region.USE_PREF_SIZE);

        HBox titleRow = new HBox(10, name, badge);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(titleRow, Priority.ALWAYS);

        HBox head = new HBox();
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().add(titleRow);
        for (Button action : actions) {
            action.setMinWidth(Region.USE_PREF_SIZE);
            head.getChildren().add(action);
        }
        return head;
    }

    /** 键值行：键 + 值文本；值为 {@code null} 或空白时显示 "-"。 */
    public static HBox kvLine(String key, String value) {
        Label v = new Label(value == null || value.isBlank() ? "-" : value);
        v.getStyleClass().add("provider-card-val");
        v.setWrapText(true);
        return kvNodeLine(key, v);
    }

    /**
     * 键值行（富节点值）：键 + 任意值节点，值列横向拉伸占满剩余宽度。
     * <p>值 Label 需由调用方持有的场景（如 MCP 探测结果异步回写）也走此方法，
     * 传入的节点引用与卡片内展示保持同一实例。</p>
     */
    public static HBox kvNodeLine(String key, Node value) {
        Label k = new Label(key);
        k.getStyleClass().add("provider-card-key");
        HBox.setHgrow(value, Priority.ALWAYS);
        HBox h = new HBox(12, k, value);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** 构造「Segoe 图标 + 主题色 + 说明文字」的卡面状态值（如可用/不可用、支持/不支持）。 */
    public static HBox iconTextValue(String glyph, String colorClass, String text) {
        Label ic = new Label(glyph);
        ic.getStyleClass().addAll("icon", "card-status-icon", colorClass);
        Label tx = new Label(text);
        tx.getStyleClass().add("provider-card-val");
        HBox h = new HBox(6, ic, tx);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }
}
