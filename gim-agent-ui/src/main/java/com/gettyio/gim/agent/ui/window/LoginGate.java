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

package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.domain.security.LocalAuthService;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.theme.UiTheme;
import com.gettyio.gim.agent.ui.chat.MenuPopup;
import com.gettyio.gim.agent.ui.window.settings.SettingsLayout;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 登录门（认证小窗）：独立于主窗的轻量 TRANSPARENT 小窗——启动首帧、托盘唤回与会话锁定
 * 时弹出；认证通过后关闭并回调，取消（关闭按钮 / Alt+F4）同样回调，由调用方决定后续
 * （退出程序 / 回托盘 / 显示主窗）。
 * <p>窗口结构：与主窗同款圆角壳（投影留白 + 圆角裁剪 + 自绘标题栏），标题栏右侧含语言切换
 * （三语言单选；切换后就地重译本窗文案，保留已输入内容）；主区为模式大标题、说明文字与
 * 认证表单构成的单列内容（水平垂直居中）。窗口固定尺寸、不可缩放，居中于主屏。</p>
 * <ul>
 *   <li><b>首次设置</b>：账号名 + 密码 + 确认，创建即开启会话（无跳过入口，取消即退出/回托盘）。</li>
 * <li><b>登录</b>：账号只读展示 + 密码（右侧含明文查看切换）
 * +「忘记密码？」展开重置指引（编辑 auth.json 的 reset 标志）。</li>
 *   <li><b>重设密码</b>：reset 标志流程，账号名保持不变，仅输入新密码。</li>
 * </ul>
 *
 * @author gogym
 */
public final class LoginGate {

    /** 关闭按钮字形（Segoe Fluent Icons）。 */
    private static final String GLYPH_CLOSE = "\uE8BB";
    /** 密码「明文查看」按钮字形：睁眼 RedEye（密文态，点击查看）/ 闭眼 Hide（明文态，点击隐藏）。 */
    private static final String GLYPH_REVEAL = "\uE7B3";
    private static final String GLYPH_HIDE = "\uED1A";
    /** 内容列宽度上限（px）：欢迎语与表单卡片的主视觉列。 */
    private static final double CONTENT_WIDTH = 360;
    /** 登录窗尺寸（px）：小巧独立窗（外层透明投影留白 10px 由 CSS 承担）。 */
    private static final double WINDOW_WIDTH = 480;
    private static final double WINDOW_HEIGHT = 660;
    /** 圆角半径（px），与 CSS 中 .window-shell 的 -fx-background-radius 保持一致。 */
    private static final double CORNER_RADIUS = 10;

    private enum Mode { SETUP, LOGIN, RESET }

    /** 当前活动的登录窗（防重入：已显示时再次请求只前置，不重复开窗、不回调）。 */
    private static LoginGate active;

    private final LocalAuthService auth;
    private final Mode mode;
    /** 认证结果回调：true=已通过，false=取消（两者均在窗口关闭后触发）。 */
    private final Consumer<Boolean> onDone;
    private final Label errorLabel = new Label();
    /** 语言切换后的文案刷新任务：就地重译本窗文本，不重建节点（表单状态不丢）。 */
    private final List<Runnable> langRefreshers = new ArrayList<>();
    /** 最近一次错误行的渲染器：语言切换后若错误行可见，按新语言重绘。 */
    private Supplier<String> lastError;

    private Stage loginStage;
    private Button submitButton;
    /** 标题栏语言切换按钮（图标 + 当前语言本族语名）。 */
    private Button langButton;
    /** 语言菜单（复用实例）与其最近一次收起时刻（识别「再点收起」手势）。 */
    private ContextMenu langMenu;
    private long langMenuHiddenAt;
    /** 「忘记密码？」按钮（仅登录模式），在操作行右侧参与布局。 */
    private Button forgotButton;
    /** 聚焦目标：窗口显示后统一排队聚焦。 */
    private Node focusTarget;

    /**
     * 认证门入口：会话有效直接放行（并续期）；否则弹出三模式登录小窗，完成后回调结果。
     * <p>防重入：登录窗已显示（如启动认证进行中托盘又被唤回）时只把窗口置前，不重复开窗、不回调。</p>
     */
    public static void ensureAuthenticated(LocalAuthService auth, Consumer<Boolean> onDone) {
        LocalAuthService.Status status = auth.status();
        if (status == LocalAuthService.Status.AUTHENTICATED) {
            auth.touch();
            onDone.accept(true);
            return;
        }
        if (active != null) {
            if (active.loginStage != null) {
                active.loginStage.toFront();
            }
            return;
        }
        Mode mode = switch (status) {
            case SETUP_REQUIRED -> Mode.SETUP;
            case RESET_REQUIRED -> Mode.RESET;
            default -> Mode.LOGIN;
        };
        new LoginGate(auth, mode, onDone).show();
    }

    private LoginGate(LocalAuthService auth, Mode mode, Consumer<Boolean> onDone) {
        this.auth = auth;
        this.mode = mode;
        this.onDone = onDone;
    }

    // ===================== 装配与显示 =====================

    private void show() {
        loginStage = new Stage();
        // 窗口图标（任务栏/Alt+Tab）：与主窗同源的应用徽标
        UiTheme.applyAppIcon(loginStage);
        loginStage.initStyle(StageStyle.TRANSPARENT);
        loginStage.setResizable(false);
        loginStage.setTitle(I18n.t("app.title"));

        Scene scene = new Scene(build(), WINDOW_WIDTH, WINDOW_HEIGHT, Color.TRANSPARENT);
        UiTheme.apply(scene);
        loginStage.setScene(scene);
        loginStage.setOnCloseRequest(e -> finish(false));
        loginStage.setOnShown(e -> Platform.runLater(this::requestFocus));
        centerOnScreen();
        active = this;
        loginStage.show();
    }

    /** 收尾：复位场景默认按钮、关窗、清活动引用，最后回调结果。 */
    private void finish(boolean authed) {
        // 场景级默认按钮：窗口移除后必须复位，避免继续拦截 Enter
        submitButton.setDefaultButton(false);
        active = null;
        if (loginStage != null) {
            loginStage.close();
            loginStage = null;
        }
        onDone.accept(authed);
    }

    /** 主屏可视区居中（避开任务栏）。 */
    private void centerOnScreen() {
        Rectangle2D visual = Screen.getPrimary().getVisualBounds();
        loginStage.setX(visual.getMinX() + (visual.getWidth() - WINDOW_WIDTH) / 2);
        loginStage.setY(visual.getMinY() + (visual.getHeight() - WINDOW_HEIGHT) / 2);
    }

    /** 圆角壳：透明投影留白 + 圆角裁剪 + 自绘标题栏，与主窗/对话框同款观感。 */
    private Parent build() {
        BorderPane shellPane = new BorderPane();
        shellPane.getStyleClass().add("window-shell");
        shellPane.setTop(buildTitleBar());
        shellPane.setCenter(buildMain());

        Rectangle clip = new Rectangle();
        clip.setArcWidth(CORNER_RADIUS * 2);
        clip.setArcHeight(CORNER_RADIUS * 2);
        clip.widthProperty().bind(shellPane.widthProperty());
        clip.heightProperty().bind(shellPane.heightProperty());
        shellPane.setClip(clip);

        StackPane root = new StackPane(shellPane);
        root.getStyleClass().add("window-root-shell");
        return root;
    }

    /** 标题栏：品牌标识与主窗同款（应用徽标 + 应用名）、语言切换、关闭 = 取消认证；空白区可拖动。 */
    private HBox buildTitleBar() {
        HBox bar = new HBox();
        bar.getStyleClass().add("title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        HBox brand = new HBox(8);
        brand.getStyleClass().add("title-brand");
        ImageView logo = UiTheme.brandBadge(20);
        Label text = trLabel("app.title", "title-text");
        Region spacer = new Region();
        spacer.getStyleClass().add("title-spacer");
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox.setHgrow(brand, Priority.ALWAYS);
        brand.getChildren().addAll(logo, text, spacer);

        Button close = new Button();
        close.getStyleClass().addAll("win-btn", "win-close");
        Label closeIcon = new Label(GLYPH_CLOSE);
        closeIcon.getStyleClass().add("win-btn-icon");
        close.setGraphic(closeIcon);
        close.setFocusTraversable(false);
        close.setOnAction(e -> finish(false));

        bar.getChildren().addAll(brand, buildLangButton(), close);
        installDrag(bar);
        return bar;
    }

    /** 语言切换按钮：图标 + 当前语言本族语名；点击弹出三语言单选菜单。 */
    private Button buildLangButton() {
        langButton = new Button();
        langButton.getStyleClass().add("lang-btn");
        langButton.setFocusTraversable(false);
        renderLangButton();
        langButton.setOnAction(e -> showLangMenu());
        return langButton;
    }

    /** 语言按钮闭合态：图标 + 当前语言名（恒以本族语显示，任何界面语言下均可辨识）。 */
    private void renderLangButton() {
        Label icon = UiTheme.icon("\uF2B7");
        Label name = new Label(I18n.current().nativeName());
        name.getStyleClass().add("lang-btn-text");
        HBox box = new HBox(6, icon, name);
        box.setAlignment(Pos.CENTER_LEFT);
        langButton.setGraphic(box);
    }

    /** 语言菜单：三语言单选（本族语名 + 当前项圆点），显示中再点按钮为收起（与工具条菜单同款防抖）。 */
    private void showLangMenu() {
        if (langMenu == null) {
            langMenu = new ContextMenu();
            langMenu.setOnHiding(e -> langMenuHiddenAt = System.currentTimeMillis());
        }
        if (langMenu.isShowing()) {
            langMenu.hide();
            return;
        }
        if (MenuPopup.inHideDebounce(langMenuHiddenAt)) {
            return;
        }
        langMenu.getItems().clear();
        ToggleGroup group = new ToggleGroup();
        for (I18n.Language lang : I18n.Language.values()) {
            RadioMenuItem item = new RadioMenuItem(lang.nativeName());
            item.setToggleGroup(group);
            item.setSelected(lang == I18n.current());
            item.setOnAction(e -> switchLanguage(lang));
            langMenu.getItems().add(item);
        }
        MenuPopup.showBelow(langMenu, langButton);
    }

    /**
     * 切换界面语言：持久化并广播（其他已打开界面由 {@link I18n} 通知刷新），本窗不重建节点，
     * 仅按新语言就地重译全部文案——输入内容、「忘记密码？」展开态与可见错误行均原样保留。
     */
    private void switchLanguage(I18n.Language lang) {
        if (lang == I18n.current()) {
            return;
        }
        I18n.setLanguage(lang);
        renderLangButton();
        for (Runnable refresh : langRefreshers) {
            refresh.run();
        }
        if (loginStage != null) {
            loginStage.setTitle(I18n.t("app.title"));
        }
        if (errorLabel.isVisible() && lastError != null) {
            errorLabel.setText(lastError.get());
        }
    }

    /** 可重译 Label：创建即定型样式，语言切换时就地改文本（节点复用，表单状态不丢）。 */
    private Label trLabel(String key, String... styleClasses) {
        Label label = new Label(I18n.t(key));
        label.getStyleClass().addAll(styleClasses);
        langRefreshers.add(() -> label.setText(I18n.t(key)));
        return label;
    }

    /** 主区：品牌区与表单卡片构成的单列内容，整体水平垂直居中。 */
    private VBox buildMain() {
        VBox column = new VBox(26);
        column.setMaxWidth(CONTENT_WIDTH);
        column.getChildren().addAll(buildBrand(), buildForm());

        VBox main = new VBox(column);
        main.getStyleClass().add("auth-main");
        main.setAlignment(Pos.CENTER);
        return main;
    }

    /** 品牌区：放大版图形标（独立标志，与标题栏徽标同源）+ 模式欢迎语 + 三行产品介绍。 */
    private VBox buildBrand() {
        ImageView logo = UiTheme.brandMark(56);

        Label title = trLabel(heroKey(), "auth-hero-title");
        Label intro = new Label(introText());
        intro.getStyleClass().add("auth-intro");
        intro.setWrapText(true);
        langRefreshers.add(() -> intro.setText(introText()));
        VBox texts = new VBox(8, title, intro);

        VBox brand = new VBox(14);
        brand.getChildren().addAll(logo, texts);
        return brand;
    }

    /** 三行产品介绍（换行拼接）；语言切换时重取。 */
    private String introText() {
        return I18n.t("auth.intro.1") + "\n"
                + I18n.t("auth.intro.2") + "\n" + I18n.t("auth.intro.3");
    }

    /** 表单卡片：白底圆角承载模式说明（原 header / notice 文案）、表单、错误行与操作行。 */
    private VBox buildForm() {
        Label notice = trLabel(subKey(), "auth-notice");
        notice.setWrapText(true);

        errorLabel.getStyleClass().addAll("dialog-message", "status-bad");
        errorLabel.setWrapText(true);
        errorLabel.setManaged(false);
        errorLabel.setVisible(false);

        VBox form = new VBox(14);
        form.getStyleClass().add("auth-form-card");
        form.getChildren().addAll(notice, buildContent(), errorLabel, buildActions());
        return form;
    }

    private String heroKey() {
        return switch (mode) {
            case SETUP -> "auth.hero.setup";
            case RESET -> "auth.hero.reset";
            case LOGIN -> "auth.hero.login";
        };
    }

    /** 大标题下的模式说明文字（表单上方）：复用各模式原有的 header / notice 文案键。 */
    private String subKey() {
        return switch (mode) {
            case SETUP -> "auth.setup.header";
            case RESET -> "auth.reset.notice";
            case LOGIN -> "auth.login.header";
        };
    }

    // ===================== 模式内容 =====================

    /** 说明文字（支持 MessageFormat 参数）：随语言切换就地重译。 */
    private Label hintLabel(String key, Object... args) {
        Label hint = new Label(I18n.t(key, args));
        hint.getStyleClass().add("dialog-message");
        hint.setWrapText(true);
        langRefreshers.add(() -> hint.setText(I18n.t(key, args)));
        return hint;
    }

    /** 表单字段行：复用设置页 labeledField 观感，字段标签随语言切换就地重译。 */
    private VBox labeledField(String key, Node field) {
        VBox box = SettingsLayout.labeledField(I18n.t(key), field);
        Label label = (Label) box.getChildren().get(0);
        langRefreshers.add(() -> label.setText(I18n.t(key)));
        return box;
    }

    /**
     * 密码框 + 内嵌右缘的「明文查看」小按钮：默认密文显示（睁眼），点击在密文/明文间切换（闭眼）。
     * <p>明文用同款 TextField 与 PasswordField 叠放在 StackPane 内、文本双向绑定，
     * 切换只换可见节点，输入内容不丢；提交逻辑始终读 PasswordField 的文本，无需感知切换。
     * 按钮叠放字段内部右缘，两个字段加宽右内衬避让按钮。</p>
     */
    private StackPane passwordWithReveal(PasswordField password) {
        TextField plain = new TextField();
        plain.textProperty().bindBidirectional(password.textProperty());
        plain.setVisible(false);
        plain.setManaged(false);

        Label eye = UiTheme.icon(GLYPH_REVEAL);
        Button reveal = new Button();
        reveal.getStyleClass().add("auth-reveal-btn");
        reveal.setFocusTraversable(false);
        reveal.setGraphic(eye);
        Tooltip tooltip = new Tooltip(I18n.t("auth.password.reveal.show"));
        reveal.setTooltip(tooltip);
        reveal.setOnAction(e -> {
            boolean show = !plain.isVisible();
            password.setVisible(!show);
            password.setManaged(!show);
            plain.setVisible(show);
            plain.setManaged(show);
            TextInputControl editing = show ? plain : password;
            editing.positionCaret(editing.getText() == null ? 0 : editing.getText().length());
            editing.requestFocus();
            eye.setText(show ? GLYPH_HIDE : GLYPH_REVEAL);
            reveal.getStyleClass().remove("auth-reveal-on");
            if (show) {
                reveal.getStyleClass().add("auth-reveal-on");
            }
            tooltip.setText(I18n.t(show ? "auth.password.reveal.hide" : "auth.password.reveal.show"));
        });
        // 语言切换后就地重译提示：按当前明/密文状态取键
        langRefreshers.add(() -> tooltip.setText(
                I18n.t(plain.isVisible() ? "auth.password.reveal.hide" : "auth.password.reveal.show")));

        password.getStyleClass().add("has-reveal");
        plain.getStyleClass().add("has-reveal");
        StackPane box = new StackPane(password, plain, reveal);
        StackPane.setAlignment(reveal, Pos.CENTER_RIGHT);
        StackPane.setMargin(reveal, new Insets(0, 5, 0, 0));
        return box;
    }

    /** 账号行（登录/重设模式）：首字圆徽 + 账号名的浅色行，软锁定位下账号名非机密，直接展示便于确认。 */
    private HBox accountReadOnly() {
        String name = auth.username() == null ? "-" : auth.username();
        Label avatar = new Label(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
        avatar.getStyleClass().add("auth-account-avatar");
        Label value = new Label(name);
        value.getStyleClass().add("auth-account-name");
        HBox row = new HBox(10, avatar, value);
        row.getStyleClass().add("auth-account");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** 各模式表单装配；提交回调内 finish(true) 表示认证完成。 */
    private VBox buildContent() {
        VBox content = new VBox(12);
        switch (mode) {
            case SETUP -> {
                TextField username = new TextField();
                PasswordField password = new PasswordField();
                PasswordField confirm = new PasswordField();
                content.getChildren().addAll(
                        labeledField("auth.setup.username", username),
                        labeledField("auth.setup.password", password),
                        labeledField("auth.setup.confirm", confirm),
                        hintLabel("auth.setup.hint", LocalAuthService.MIN_PASSWORD_LENGTH));
                focusTarget = username;
                installSubmit(() -> {
                    String name = username.getText() == null ? "" : username.getText().trim();
                    if (name.isEmpty()) {
                        showError("auth.error.username.empty");
                        return;
                    }
                    if (password.getText() == null
                            || password.getText().length() < LocalAuthService.MIN_PASSWORD_LENGTH) {
                        showError("auth.error.password.tooShort", LocalAuthService.MIN_PASSWORD_LENGTH);
                        return;
                    }
                    if (!password.getText().equals(confirm.getText())) {
                        showError("auth.error.password.mismatch");
                        return;
                    }
                    auth.setupAccount(name, password.getText());
                    finish(true);
                });
            }
            case LOGIN -> {
                PasswordField password = new PasswordField();
                content.getChildren().addAll(
                        accountReadOnly(),
                        labeledField("auth.login.password", passwordWithReveal(password)));
                Label forgotHint = hintLabel("auth.login.forgot.hint",
                        auth.authFile().toAbsolutePath().toString());
                forgotHint.setManaged(false);
                forgotHint.setVisible(false);
                content.getChildren().add(forgotHint);
                focusTarget = password;
                installSubmit(() -> {
                    if (auth.cooldownRemainingSeconds() > 0) {
                        showError(cooldownError());
                        return;
                    }
                    if (auth.verifyAndLogin(password.getText())) {
                        finish(true);
                        return;
                    }
                    showError(auth.cooldownRemainingSeconds() > 0
                            ? cooldownError()
                            : () -> I18n.t("auth.error.password.wrong"));
                    password.clear();
                    password.requestFocus();
                });
                // 「忘记密码？」在操作行右侧展开指引
                forgotButton = new Button(I18n.t("auth.login.forgot"));
                forgotButton.getStyleClass().add("link-btn");
                forgotButton.setFocusTraversable(false);
                forgotButton.setOnAction(e -> {
                    boolean show = !forgotHint.isVisible();
                    forgotHint.setVisible(show);
                    forgotHint.setManaged(show);
                });
                langRefreshers.add(() -> forgotButton.setText(I18n.t("auth.login.forgot")));
            }
            case RESET -> {
                PasswordField password = new PasswordField();
                PasswordField confirm = new PasswordField();
                content.getChildren().addAll(
                        accountReadOnly(),
                        labeledField("auth.reset.password", password),
                        labeledField("auth.setup.confirm", confirm));
                focusTarget = password;
                installSubmit(() -> {
                    if (password.getText() == null
                            || password.getText().length() < LocalAuthService.MIN_PASSWORD_LENGTH) {
                        showError("auth.error.password.tooShort", LocalAuthService.MIN_PASSWORD_LENGTH);
                        return;
                    }
                    if (!password.getText().equals(confirm.getText())) {
                        showError("auth.error.password.mismatch");
                        return;
                    }
                    auth.resetPassword(password.getText());
                    finish(true);
                });
            }
        }
        return content;
    }

    /** 提交按钮：绑定表单提交、作为默认按钮（Enter 提交）。 */
    private void installSubmit(Runnable action) {
        submitButton = new Button(I18n.t(submitKey()));
        submitButton.getStyleClass().addAll("btn-primary", "auth-submit");
        submitButton.setMinWidth(96);
        submitButton.setDefaultButton(true);
        submitButton.setOnAction(e -> action.run());
        langRefreshers.add(() -> submitButton.setText(I18n.t(submitKey())));
    }

    private String submitKey() {
        return switch (mode) {
            case SETUP -> "auth.setup.submit";
            case RESET -> "auth.reset.submit";
            case LOGIN -> "auth.login.submit";
        };
    }

    /** 操作区：主按钮通栏；登录模式的「忘记密码？」居中于按钮下方。 */
    private VBox buildActions() {
        submitButton.setMaxWidth(Double.MAX_VALUE);
        VBox actions = new VBox(10, submitButton);
        if (mode == Mode.LOGIN && forgotButton != null) {
            HBox forgotRow = new HBox(forgotButton);
            forgotRow.setAlignment(Pos.CENTER);
            actions.getChildren().add(forgotRow);
        }
        return actions;
    }

    // ===================== 层级交互 =====================

    /** 错误行（键 + MessageFormat 参数）：渲染器保留至语言切换后按新语言重绘。 */
    private void showError(String key, Object... args) {
        showError(() -> I18n.t(key, args));
    }

    private void showError(Supplier<String> renderer) {
        lastError = renderer;
        errorLabel.setText(renderer.get());
        errorLabel.setManaged(true);
        errorLabel.setVisible(true);
    }

    /** 冷却提示渲染器：重译时按剩余秒数重取，倒计时不因语言切换僵在旧值。 */
    private Supplier<String> cooldownError() {
        return () -> I18n.t("auth.error.cooldown", auth.cooldownRemainingSeconds());
    }

    private void requestFocus() {
        if (focusTarget != null) {
            focusTarget.requestFocus();
        }
    }

    /** 标题栏空白区按住拖动移动登录窗（按钮及其图形子节点上的操作不触发拖动）。 */
    private void installDrag(HBox bar) {
        double[] drag = new double[2];
        bar.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY && !isOnButton(e.getTarget())) {
                drag[0] = e.getScreenX() - loginStage.getX();
                drag[1] = e.getScreenY() - loginStage.getY();
            }
        });
        bar.setOnMouseDragged(e -> {
            if (e.getButton() == MouseButton.PRIMARY && !isOnButton(e.getTarget())) {
                loginStage.setX(e.getScreenX() - drag[0]);
                loginStage.setY(e.getScreenY() - drag[1]);
            }
        });
    }

    /** 命中链上是否落在按钮内：按钮的图形/文字子节点也会成为事件目标，需回溯父链判定。 */
    private boolean isOnButton(Object target) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node instanceof Button) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }
}
