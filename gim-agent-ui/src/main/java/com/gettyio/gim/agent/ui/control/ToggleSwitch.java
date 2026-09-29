package com.gettyio.gim.agent.ui.control;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;

/**
 * 胶囊开关（对标 Qoder 绿色 Toggle）：轨道 + 圆形滑块，点击切换。
 * <p>样式由 CSS 类 {@code .toggle-switch} / {@code .toggle-switch:selected} 与
 * {@code .toggle-knob} 控制；滑块位置通过 StackPane 对齐在左右间切换实现。</p>
 */
public class ToggleSwitch extends StackPane {

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
        selected.addListener((obs, o, v) -> {
            if (v) {
                getStyleClass().add("selected");
                setAlignment(Pos.CENTER_RIGHT);
            } else {
                getStyleClass().remove("selected");
                setAlignment(Pos.CENTER_LEFT);
            }
        });
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
