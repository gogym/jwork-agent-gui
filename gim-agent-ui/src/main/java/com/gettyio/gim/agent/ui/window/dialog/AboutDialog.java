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

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.util.OsPlatform;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.theme.UiTheme;
import com.gettyio.gim.agent.ui.update.UpdateChecker;
import com.gettyio.gim.agent.ui.util.UiStyles;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;

/**
 * 齿轮菜单「关于」：品牌化关于对话框（复用 {@link GimDialog} 无边框壳）——
 * 绿色圆徽 + 应用名 + 版本胶囊居中，下接一句话简介、信息卡（数据目录 / 运行环境）、
 * 开源卡（仓库地址 / 许可证，点击经浏览器跳转）与版权行。
 * <p>版本经 {@link UpdateChecker#appVersion()} 读 {@code version.properties}（与更新检查同源），
 * 杜绝文案里硬编码版本号的漂移。</p>
 *
 * @author gogym
 */
public final class AboutDialog {

    private static final Logger log = LoggerFactory.getLogger(AboutDialog.class);

    /** 内容列宽度：简介 / 信息卡 / 开源卡同宽，加宽后品牌区更舒展。 */
    private static final double CONTENT_WIDTH = 440;

    /** 开源仓库地址：关于页展示并点击跳转。 */
    private static final String REPO_URL = "https://github.com/gogym/gim-agent-gui";
    /** 开源许可证名称与原文地址。 */
    private static final String LICENSE_NAME = "Apache License 2.0";
    private static final String LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0";

    private AboutDialog() {
    }

    /** 弹出关于对话框（owner 为归属窗口，可为 null）。 */
    public static void show(Window owner, GimPaths paths) {
        GimDialog.Shell shell = GimDialog.shell(owner, I18n.t("about.title"));
        fill(shell.body(), paths, () -> shell.stage().close());
        shell.showAndWait();
    }

    /**
     * 构建关于内容并填入指定容器（{@link #show} 与无窗口的离屏渲染验证共用）。
     *
     * @param onConfirm 「确定」按钮动作
     */
    static void fill(VBox body, GimPaths paths, Runnable onConfirm) {
        // 品牌区：图形标 + 应用名 + 版本胶囊；fillWidth=false 防图形标/胶囊被拉伸变形
        ImageView logo = UiTheme.brandMark(72);
        Label name = new Label(I18n.t("app.title"));
        name.getStyleClass().add("about-title");
        Label version = new Label(I18n.t("about.version", UpdateChecker.appVersion()));
        version.getStyleClass().add("about-version");
        VBox hero = new VBox(10, logo, name, version);
        hero.getStyleClass().add("about-hero");
        hero.setAlignment(Pos.CENTER);
        hero.setFillWidth(false);

        Label intro = new Label(I18n.t("about.intro"));
        intro.getStyleClass().add("about-intro");
        intro.setWrapText(true);
        // 内容对齐负责整个文本块定位（单行短文本也可见居中），TextAlignment 负责多行（英文）行内居中
        intro.setAlignment(Pos.CENTER);
        intro.setTextAlignment(TextAlignment.CENTER);
        intro.setMaxWidth(CONTENT_WIDTH);

        // 信息卡：数据目录（长路径可换行）+ 运行环境（Java/系统，排障定位用）
        VBox info = new VBox(10,
                infoRow(I18n.t("about.dataDir"), String.valueOf(paths.root())),
                infoRow(I18n.t("about.runtime"), "Java " + System.getProperty("java.version", "?")
                        + " · " + OsPlatform.rawName()));
        info.getStyleClass().add("about-info-card");
        info.setPrefWidth(CONTENT_WIDTH);

        // 开源卡：仓库地址与许可证均可点击跳转，与信息卡同款视觉语言
        VBox openSource = new VBox(10,
                linkRow(I18n.t("about.repo"), REPO_URL, REPO_URL),
                linkRow(I18n.t("about.license"), LICENSE_NAME, LICENSE_URL));
        openSource.getStyleClass().add("about-info-card");
        openSource.setPrefWidth(CONTENT_WIDTH);

        Label copyright = new Label(I18n.t("about.copyright"));
        copyright.getStyleClass().add("about-footer");
        copyright.setAlignment(Pos.CENTER);
        copyright.setMaxWidth(Double.MAX_VALUE);

        Button ok = new Button(I18n.t("common.ok"));
        ok.getStyleClass().add(UiStyles.BTN_PRIMARY);
        ok.setMinWidth(76);
        ok.setOnAction(e -> onConfirm.run());
        HBox actions = new HBox(ok);
        actions.getStyleClass().add(UiStyles.DIALOG_ACTIONS);

        body.getChildren().addAll(hero, intro, info, openSource, copyright, actions);
    }

    /** 信息行（可点击值）：键名 + 链接样式值，点击经系统浏览器打开目标地址。 */
    private static HBox linkRow(String key, String display, String url) {
        Label k = new Label(key);
        k.getStyleClass().add("about-info-key");
        Label v = new Label(display);
        v.getStyleClass().add("about-link");
        v.setTooltip(new Tooltip(url));
        v.setOnMouseClicked(e -> openUrl(url));
        v.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(12, k, v);
        row.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(v, Priority.ALWAYS);
        return row;
    }

    /** 经系统浏览器打开链接；环境不支持或失败仅告警，不影响关于页其余信息。 */
    private static void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            log.warn("打开链接失败: {}", url, e);
        }
    }

    /** 信息行：左侧固定宽键名 + 右侧可换行值（值撑满剩余宽度）。 */
    private static HBox infoRow(String key, String value) {
        Label k = new Label(key);
        k.getStyleClass().add("about-info-key");
        Label v = new Label(value);
        v.getStyleClass().add("about-info-val");
        v.setWrapText(true);
        v.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(12, k, v);
        row.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(v, Priority.ALWAYS);
        return row;
    }
}
