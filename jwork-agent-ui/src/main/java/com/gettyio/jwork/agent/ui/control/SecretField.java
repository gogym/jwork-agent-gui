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

import com.gettyio.jwork.agent.domain.security.SecretStore;
import com.gettyio.jwork.agent.ui.i18n.I18n;
import com.gettyio.jwork.agent.ui.util.UiStyles;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 密钥输入行：密码框 + 存储状态徽章 + 图标「清除」按钮，图像生成 / 联网搜索 / 向量库 / WebDAV 四处共用。
 *
 * <p>它刻意<b>不回填已存密钥明文</b>。回填会让「留空」有两种解释——用户真想清空，还是压根没碰这格？
 * 旧实现按后者落一个空串，于是一次瞬时解密失败加一次失焦就把用户配好的 Key 抹掉了。这里的口径：</p>
 * <ul>
 *   <li>留空恒表示「不修改」，不产生任何写入；</li>
 *   <li>撤销只有「清除」这一条路径；</li>
 *   <li>「未配置」与「已配置但本机解不开」分开显示——后者是换机器或换了 Windows 账户，
 *       该提示重录，而不是让人以为从没配过。</li>
 * </ul>
 * <p>状态以语义色胶囊徽章呈现（未配置灰 / 已保存绿 / 解不开红 / 旧明文蓝），长解释收进 tooltip；
 * 「清除」为垃圾桶图标按钮，仅已配置时出现，避免裸文字链接的视觉噪声。</p>
 *
 * @author gogym
 */
public final class SecretField {

    private final PasswordField field = new PasswordField();
    private final Label stateLabel = new Label();
    private final Button clearButton = new Button();
    private final HBox control;
    private final Supplier<SecretStore.State> stateRead;
    private final Consumer<String> writer;
    private final Runnable clearer;
    private final List<Runnable> afterCommit = new ArrayList<>();

    /**
     * @param stateRead 当前入库状态（每次刷新现取，提供方切换等改动即时反映）
     * @param writer    写入新录入的明文；仅在输入框非空白时被调用
     * @param clearer   显式清除已存密钥
     */
    public SecretField(Supplier<SecretStore.State> stateRead, Consumer<String> writer, Runnable clearer) {
        this.stateRead = stateRead;
        this.writer = writer;
        this.clearer = clearer;
        field.setPrefWidth(260);
        stateLabel.getStyleClass().add("badge");
        stateLabel.setMinWidth(Region.USE_PREF_SIZE);
        Label clearIcon = new Label("\uE74D");
        clearIcon.getStyleClass().add("icon");
        clearButton.setGraphic(clearIcon);
        clearButton.getStyleClass().addAll("icon-btn", "icon-btn-danger");
        clearButton.setMinWidth(Region.USE_PREF_SIZE);
        clearButton.setOnAction(e -> {
            clearer.run();
            field.clear();
            refresh();
        });
        field.setOnAction(e -> commit());
        field.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                commit();
            }
        });
        control = new HBox(8, field, stateLabel, clearButton);
        control.setAlignment(Pos.CENTER_LEFT);
        applyI18n();
    }

    /** 密码框本体：供调用方设 promptText 与样式类；宽度由本控件统一持有（260px）。 */
    public PasswordField field() {
        return field;
    }

    /** 整行控件（输入框 + 状态 + 清除），可直接作为 {@code settingRow} 的右侧控件。 */
    public Node control() {
        return control;
    }

    /** 提交后的附加回调（刷新状态行等），在状态刷新之后执行。 */
    public void addAfterCommit(Runnable action) {
        afterCommit.add(action);
    }

    /**
     * 状态徽章刷新：短文案 + 语义色胶囊 + 长解释 tooltip。
     * <p>不便摆整行控件的场合（如代理认证那格）也能用同一套口径说话。</p>
     */
    public static void applyStateBadge(Label badge, SecretStore.State state) {
        String key = tokenOf(state);
        badge.setText(I18n.t(state == SecretStore.State.READABLE
                ? "settings.secret.saved.short" : "settings.secret." + key));
        badge.setTooltip(new Tooltip(I18n.t("settings.secret." + key + ".tip")));
        badge.getStyleClass().removeAll(UiStyles.BADGE_GRAY, UiStyles.BADGE_GREEN, "badge-red", "badge-blue");
        badge.getStyleClass().add(switch (state) {
            case EMPTY -> UiStyles.BADGE_GRAY;
            case READABLE -> UiStyles.BADGE_GREEN;
            case UNREADABLE -> "badge-red";
            case LEGACY_PLAIN -> "badge-blue";
        });
    }

    private static String tokenOf(SecretStore.State state) {
        return switch (state) {
            case EMPTY -> "none";
            case READABLE -> "saved";
            case UNREADABLE -> "unreadable";
            case LEGACY_PLAIN -> "legacy";
        };
    }

    /** 文案随语言切换重取；调用方在自己的 applyI18n 里带一次。 */
    public void applyI18n() {
        clearButton.setTooltip(new Tooltip(I18n.t("settings.secret.clear")));
        refresh();
    }

    /** 按当前入库状态刷新徽章与「清除」按钮显隐。 */
    public void refresh() {
        SecretStore.State state = stateRead.get();
        applyStateBadge(stateLabel, state);
        boolean configured = state != SecretStore.State.EMPTY;
        clearButton.setVisible(configured);
        clearButton.setManaged(configured);
    }

    /**
     * 提交输入框内容：非空白才写入并回空框（已存明文不进输入框，写一次就收进库）；
     * 空白什么都不做——保存动作与「清除」是两个意图。
     *
     * @return 是否发生了写入
     */
    public boolean commit() {
        String typed = field.getText();
        if (typed == null || typed.isBlank()) {
            refresh();
            return false;
        }
        writer.accept(typed.trim());
        field.clear();
        refresh();
        for (Runnable action : afterCommit) {
            action.run();
        }
        return true;
    }
}
