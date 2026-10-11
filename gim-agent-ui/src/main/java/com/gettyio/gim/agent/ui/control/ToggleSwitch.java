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

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;

/**
 * 胶囊开关（对标 Qoder 绿色 Toggle）：轨道 + 圆形滑块，点击切换。
 * <p>样式由 {@code .toggle-switch} / {@code .toggle-switch:selected}（伪类，不是样式类，
 * 两者不通用）与 {@code .toggle-knob} 控制；启用态靠驱动 {@code :selected} 伪类变绿，
 * 滑块位置通过 StackPane 对齐在左右间切换实现。</p>
 *
 * @author gogym
 */
public class ToggleSwitch extends StackPane {

    /** 启用态伪类：对应 CSS 里的 {@code .toggle-switch:selected}。 */
    private static final PseudoClass SELECTED_PSEUDO = PseudoClass.getPseudoClass("selected");

    private final BooleanProperty selected = new SimpleBooleanProperty(false);

    public ToggleSwitch() {
        getStyleClass().add("toggle-switch");
        Label knob = new Label();
        knob.getStyleClass().add("toggle-knob");
        getChildren().add(knob);
        setCursor(Cursor.HAND);
        setAlignment(Pos.CENTER_LEFT);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);

        addEventFilter(MouseEvent.MOUSE_CLICKED, e -> setSelected(!isSelected()));
        applyState(false);
        selected.addListener((obs, o, v) -> applyState(v));
    }

    /** 同步启用态：驱动 :selected 伪类使轨道变主色，并把滑块推到右侧。 */
    private void applyState(boolean value) {
        pseudoClassStateChanged(SELECTED_PSEUDO, value);
        setAlignment(value ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean value) {
        selected.set(value);
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }
}
